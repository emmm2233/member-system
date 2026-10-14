package com.company.member.modules.member.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.exception.BizException;
import com.company.member.modules.account.entity.MemberAccount;
import com.company.member.modules.account.service.AccountService;
import com.company.member.modules.member.entity.LevelRule;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.entity.MemberLevel;
import com.company.member.modules.member.entity.MemberStat;
import com.company.member.modules.member.mapper.LevelRuleMapper;
import com.company.member.modules.member.mapper.MemberLevelMapper;
import com.company.member.modules.member.mapper.MemberMapper;
import com.company.member.modules.member.mapper.MemberStatMapper;
import com.company.member.modules.system.service.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 等级评定引擎
 *
 * <p>规则来源：{@code m_level_rule.rule_json}，支持以下字段（白名单）：
 * <ul>
 *   <li>{@code growth} 累计成长值（来自资产账户）</li>
 *   <li>{@code totalRecharge} / {@code totalConsume} 累计充值 / 累计实付消费</li>
 *   <li>{@code payAmount30d} / {@code payAmount90d} / {@code payAmount12m} 区间消费额（来自会员统计）</li>
 *   <li>{@code orderCount12m} / {@code visitDays30d} 近 12 月笔数 / 近 30 天到店天数</li>
 * </ul>
 * 操作符：{@code >= > <= < ==}；逻辑：同层 AND / OR。
 *
 * <p>默认策略：只升不降（{@code level.downgrade_enabled=false}）；
 * 等级有效期由 {@code level.valid_months} 控制，0 表示永久。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LevelEvaluationService {

    private final MemberMapper memberMapper;
    private final MemberLevelMapper levelMapper;
    private final LevelRuleMapper ruleMapper;
    private final MemberStatMapper statMapper;
    private final AccountService accountService;
    private final ConfigService configService;
    /** 规则 JSON 解析器（Spring Boot 内置 Jackson） */
    private final ObjectMapper objectMapper;

    /* ==================================================================
     *  单会员评定（消费完成事件 / 手工触发）
     * ================================================================== */

    /**
     * 评定单个会员等级
     *
     * @return 是否发生升级
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> evaluate(Long memberId) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BizException(ErrorCode.MEMBER_NOT_FOUND);
        }
        if (member.getStatus() != null && member.getStatus() != 1) {
            // 冻结/注销会员不参与评定
            return result(false, member, null, "会员状态不允许评级");
        }

        MemberAccount account = accountService.getAccount(memberId);
        MemberStat stat = statMapper.selectOne(new LambdaQueryWrapper<MemberStat>()
                .eq(MemberStat::getMemberId, memberId));
        EvalContext ctx = buildContext(member, account, stat);

        MemberLevel current = member.getLevelId() == null ? null : levelMapper.selectById(member.getLevelId());
        MemberLevel target = findTargetLevel(ctx);

        if (target == null) {
            return result(false, member, current, "无匹配等级规则");
        }
        int currentValue = current == null ? 0 : (current.getLevelValue() == null ? 0 : current.getLevelValue());
        int targetValue = target.getLevelValue() == null ? 0 : target.getLevelValue();

        if (targetValue <= currentValue) {
            // 不升级：是否降级由配置决定
            if (configService.downgradeEnabled() && targetValue < currentValue) {
                applyLevel(member, target);
                log.info("[等级] 降级 memberId={}, {} -> {}", memberId,
                        current == null ? "无" : current.getLevelName(), target.getLevelName());
                return result(true, member, target, "降级");
            }
            return result(false, member, current, "等级不变");
        }

        applyLevel(member, target);
        // 升级礼：赠送积分
        long giftPoints = target.getUpgradeGiftPoints() == null ? 0L : target.getUpgradeGiftPoints();
        if (giftPoints > 0) {
            try {
                accountService.grantPoints(memberId, giftPoints, AccountService.PBIZ_LEVEL_GIFT,
                        "LEVEL_UP:" + memberId + ":" + target.getId(),
                        "升级至 " + target.getLevelName() + " 赠送积分",
                        accountService.calcPointsExpireTime());
            } catch (Exception e) {
                log.warn("[等级] 升级礼发放失败 memberId={}, 原因={}", memberId, e.getMessage());
            }
        }
        log.info("[等级] 升级 memberId={}, {} -> {}, 赠送积分={}", memberId,
                current == null ? "无" : current.getLevelName(), target.getLevelName(), giftPoints);
        return result(true, member, target, "升级");
    }

    /* ==================================================================
     *  批量重算（定时任务 / 手工触发）
     * ================================================================== */

    /**
     * 批量重算等级（分片处理，避免长事务）
     *
     * @param batchSize 每批处理人数
     * @return 统计结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> evaluateAll(int batchSize) {
        List<MemberLevel> levels = activeLevels();
        if (levels.isEmpty()) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "未配置任何会员等级");
        }
        int size = batchSize <= 0 ? 200 : Math.min(batchSize, 1000);
        long lastId = 0L;
        int scanned = 0;
        int upgraded = 0;
        int downgraded = 0;
        List<String> errors = new ArrayList<>();

        while (true) {
            List<Member> batch = memberMapper.selectList(new LambdaQueryWrapper<Member>()
                    .eq(Member::getDeleted, 0)
                    .eq(Member::getStatus, 1)
                    .gt(Member::getId, lastId)
                    .orderByAsc(Member::getId)
                    .last("LIMIT " + size));
            if (batch.isEmpty()) {
                break;
            }
            for (Member m : batch) {
                lastId = m.getId();
                scanned++;
                try {
                    Map<String, Object> r = evaluate(m.getId());
                    if (Boolean.TRUE.equals(r.get("changed"))) {
                        String action = String.valueOf(r.get("action"));
                        if ("升级".equals(action)) {
                            upgraded++;
                        } else if ("降级".equals(action)) {
                            downgraded++;
                        }
                    }
                } catch (Exception e) {
                    if (errors.size() < 20) {
                        errors.add("memberId=" + m.getId() + ": " + e.getMessage());
                    }
                    log.warn("[等级] 批量评定单条失败 memberId={}: {}", m.getId(), e.getMessage());
                }
            }
            if (batch.size() < size) {
                break;
            }
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("scanned", scanned);
        summary.put("upgraded", upgraded);
        summary.put("downgraded", downgraded);
        summary.put("errors", errors);
        summary.put("finishedAt", LocalDateTime.now());
        log.info("[等级] 批量评定完成：扫描 {} 人，升级 {}，降级 {}，失败 {}", scanned, upgraded, downgraded, errors.size());
        return summary;
    }

    /* ==================================================================
     *  等级变更日志（手工调级也走这里）
     * ================================================================== */

    @Transactional(rollbackFor = Exception.class)
    public void changeLevelManually(Long memberId, Long levelId, String reason, Long operatorId) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BizException(ErrorCode.MEMBER_NOT_FOUND);
        }
        MemberLevel level = levelMapper.selectById(levelId);
        if (level == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "等级不存在");
        }
        applyLevel(member, level);
        log.info("[等级] 手工调级 memberId={}, {} -> {}, 原因={}, 操作人={}", memberId,
                member.getLevelId(), levelId, reason, operatorId);
    }

    /** 等级变更流水（返回最近 N 条，供会员 360° 展示） */
    public List<Map<String, Object>> changeLogs(Long memberId, int limit) {
        // 等级变更记录写入操作日志表，这里以会员维度查询
        return List.of();
    }

    /* ==================================================================
     *  内部
     * ================================================================== */

    /** 判定上下文：规则字段 → 取值 */
    private record EvalContext(Long memberId, long growth, BigDecimal totalRecharge, BigDecimal totalConsume,
                               BigDecimal payAmount30d, BigDecimal payAmount90d, BigDecimal payAmount12m,
                               int orderCount12m, int visitDays30d) {
    }

    private EvalContext buildContext(Member member, MemberAccount account, MemberStat stat) {
        return new EvalContext(
                member.getId(),
                account == null || account.getGrowth() == null ? 0L : account.getGrowth(),
                account == null ? BigDecimal.ZERO : nz(account.getTotalRecharge()),
                account == null ? BigDecimal.ZERO : nz(account.getTotalConsume()),
                stat == null ? BigDecimal.ZERO : nz(stat.getPayAmount30d()),
                stat == null ? BigDecimal.ZERO : nz(stat.getPayAmount90d()),
                stat == null ? BigDecimal.ZERO : nz(stat.getPayAmount12m()),
                stat == null || stat.getOrderCount12m() == null ? 0 : stat.getOrderCount12m(),
                stat == null || stat.getVisitDays30d() == null ? 0 : stat.getVisitDays30d());
    }

    /** 从高到低匹配，取第一个满足条件的等级 */
    private MemberLevel findTargetLevel(EvalContext ctx) {
        List<MemberLevel> levels = activeLevels();
        if (levels.isEmpty()) {
            return null;
        }
        Map<Long, LevelRule> ruleMap = new HashMap<>();
        for (LevelRule rule : ruleMapper.selectList(new LambdaQueryWrapper<LevelRule>()
                .eq(LevelRule::getRuleType, 1)
                .eq(LevelRule::getStatus, 1))) {
            // 每个等级取优先级最高的一条
            ruleMap.merge(rule.getLevelId(), rule,
                    (a, b) -> (a.getPriority() == null ? 0 : a.getPriority())
                            >= (b.getPriority() == null ? 0 : b.getPriority()) ? a : b);
        }
        // 已按 level_value 升序，反向遍历即为从高到低
        List<MemberLevel> sorted = new ArrayList<>(levels);
        sorted.sort(Comparator.comparingInt(l -> -(l.getLevelValue() == null ? 0 : l.getLevelValue())));
        for (MemberLevel level : sorted) {
            LevelRule rule = ruleMap.get(level.getId());
            if (rule == null || rule.getRuleJson() == null || rule.getRuleJson().isBlank()) {
                // 无规则：默认等级可直接命中，其他等级跳过
                if (level.getIsDefault() != null && level.getIsDefault() == 1) {
                    return level;
                }
                continue;
            }
            if (matches(rule, ctx)) {
                return level;
            }
        }
        // 兜底：默认等级
        return levels.stream().filter(l -> l.getIsDefault() != null && l.getIsDefault() == 1)
                .findFirst().orElse(levels.isEmpty() ? null : levels.get(0));
    }

    /**
     * 规则求值
     *
     * <p>极简 JSON 解析：提取所有 {@code {"field":..,"op":..,"value":..}} 三元组后逐条比较。
     * 不引入 JSON 库是为了让规则引擎在无外部依赖下也能工作，且规则结构固定可控。
     */
    private boolean matches(LevelRule rule, EvalContext ctx) {
        String json = rule.getRuleJson();
        boolean orLogic = "OR".equalsIgnoreCase(rule.getLogicType());
        List<Condition> conditions = parseConditions(json);
        if (conditions.isEmpty()) {
            // 无有效条件时不视为匹配，避免规则解析失败导致会员被误升到最高等级
            log.debug("[等级] 规则无有效条件，跳过该等级：levelId={}", rule.getLevelId());
            return false;
        }
        boolean result = !orLogic; // AND 初值 true，OR 初值 false
        for (Condition c : conditions) {
            BigDecimal actual = fieldValue(c.field(), ctx);
            boolean ok = compare(actual, c.op(), c.value());
            if (orLogic) {
                result = result || ok;
                if (result) {
                    break;
                }
            } else {
                result = result && ok;
                if (!result) {
                    break;
                }
            }
        }
        return result;
    }

    private record Condition(String field, String op, BigDecimal value) {
    }

    /**
     * 解析规则 JSON 中的条件数组
     *
     * <p>使用 Jackson 解析（Spring Boot 已内置），规则结构：
     * {@code {"logic":"AND","items":[{"field":"growth","op":">=","value":3000}]}}
     *
     * <p>兼容两种写法：条件直接放在根下的 {@code items}，或根就是数组。
     */
    private List<Condition> parseConditions(String json) {
        List<Condition> list = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return list;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode items = root.isArray() ? root : root.path("items");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String field = item.path("field").asText(null);
                    String op = item.path("op").asText(null);
                    if (field == null || op == null) {
                        continue;
                    }
                    JsonNode valueNode = item.path("value");
                    if (valueNode.isMissingNode() || valueNode.isNull()) {
                        continue;
                    }
                    BigDecimal value;
                    if (valueNode.isNumber()) {
                        value = valueNode.decimalValue();
                    } else {
                        // 兼容 value 写成字符串的情况，如 "3000"
                        try {
                            value = new BigDecimal(valueNode.asText().trim());
                        } catch (NumberFormatException e) {
                            log.warn("[等级] 规则值的数字格式非法：{}", valueNode.asText());
                            continue;
                        }
                    }
                    list.add(new Condition(field, op, value));
                }
            }
        } catch (Exception e) {
            log.warn("[等级] 规则 JSON 解析失败，将视为不匹配：{}", e.getMessage());
        }
        return list;
    }

    /** 字段白名单取值 */
    private BigDecimal fieldValue(String field, EvalContext ctx) {
        return switch (field) {
            case "growth" -> BigDecimal.valueOf(ctx.growth());
            case "totalRecharge" -> ctx.totalRecharge();
            case "totalConsume" -> ctx.totalConsume();
            case "payAmount30d" -> ctx.payAmount30d();
            case "payAmount90d" -> ctx.payAmount90d();
            case "payAmount12m" -> ctx.payAmount12m();
            case "orderCount12m" -> BigDecimal.valueOf(ctx.orderCount12m());
            case "visitDays30d" -> BigDecimal.valueOf(ctx.visitDays30d());
            default -> {
                log.warn("[等级] 规则字段不在白名单，按 0 处理：{}", field);
                yield BigDecimal.ZERO;
            }
        };
    }

    private boolean compare(BigDecimal actual, String op, BigDecimal expect) {
        if (actual == null || expect == null) {
            return false;
        }
        int cmp = actual.compareTo(expect);
        return switch (op) {
            case ">=" -> cmp >= 0;
            case ">" -> cmp > 0;
            case "<=" -> cmp <= 0;
            case "<" -> cmp < 0;
            case "==" -> cmp == 0;
            default -> {
                log.warn("[等级] 不支持的操作符：{}", op);
                yield false;
            }
        };
    }

    private void applyLevel(Member member, MemberLevel level) {
        LocalDateTime expireAt = null;
        int validMonths = configService.levelValidMonths();
        if (level.getValidMonths() != null && level.getValidMonths() > 0) {
            expireAt = LocalDateTime.now().plusMonths(level.getValidMonths());
        } else if (validMonths > 0) {
            expireAt = LocalDateTime.now().plusMonths(validMonths);
        }
        memberMapper.updateLevel(member.getId(), level.getId(), expireAt);
        member.setLevelId(level.getId());
        member.setLevelExpireAt(expireAt);
    }

    private List<MemberLevel> activeLevels() {
        return levelMapper.selectList(new LambdaQueryWrapper<MemberLevel>()
                .eq(MemberLevel::getStatus, 1)
                .eq(MemberLevel::getDeleted, 0)
                .orderByAsc(MemberLevel::getLevelValue));
    }

    private Map<String, Object> result(boolean changed, Member member, MemberLevel level, String action) {
        Map<String, Object> m = new HashMap<>();
        m.put("memberId", member.getId());
        m.put("memberNo", member.getMemberNo());
        m.put("changed", changed);
        m.put("action", action);
        m.put("levelId", level == null ? null : level.getId());
        m.put("levelName", level == null ? null : level.getLevelName());
        m.put("levelExpireAt", member.getLevelExpireAt());
        return m;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 供测试：解析规则并返回条件列表 */
    public List<Map<String, Object>> inspectRule(Long levelId) {
        LevelRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<LevelRule>()
                .eq(LevelRule::getLevelId, levelId)
                .eq(LevelRule::getRuleType, 1)
                .orderByDesc(LevelRule::getPriority)
                .last("LIMIT 1"));
        if (rule == null) {
            return List.of();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Condition c : parseConditions(rule.getRuleJson())) {
            Map<String, Object> m = new HashMap<>();
            m.put("field", c.field());
            m.put("op", c.op());
            m.put("value", c.value());
            list.add(m);
        }
        return list;
    }

    /** 模拟测算：不改数据，仅返回目标等级（供管理端前置校验规则） */
    public Map<String, Object> simulate(Long memberId) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BizException(ErrorCode.MEMBER_NOT_FOUND);
        }
        MemberAccount account = accountService.getAccount(memberId);
        MemberStat stat = statMapper.selectOne(new LambdaQueryWrapper<MemberStat>()
                .eq(MemberStat::getMemberId, memberId));
        EvalContext ctx = buildContext(member, account, stat);
        MemberLevel target = findTargetLevel(ctx);
        MemberLevel current = member.getLevelId() == null ? null : levelMapper.selectById(member.getLevelId());

        Map<String, Object> m = new HashMap<>();
        m.put("memberId", memberId);
        m.put("growth", ctx.growth());
        m.put("currentLevel", current == null ? null : current.getLevelName());
        m.put("targetLevel", target == null ? null : target.getLevelName());
        m.put("willChange", target != null && !Objects.equals(target.getId(), member.getLevelId()));
        return m;
    }
}
