package com.company.member.modules.member.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.modules.member.entity.LevelRule;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.entity.MemberLevel;
import com.company.member.modules.member.mapper.LevelRuleMapper;
import com.company.member.modules.member.mapper.MemberLevelMapper;
import com.company.member.modules.member.mapper.MemberMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 等级管理服务（管理端）
 *
 * <p>负责等级定义的增删改，以及**结构化规则**的读写。
 * 规则以 {@code rule_json} 存储，形如：
 * {@code {"logic":"AND","items":[{"field":"growth","op":">=","value":3000}]}}
 *
 * <p>字段白名单与 {@link LevelEvaluationService} 保持一致，避免保存了引擎不认的字段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LevelAdminService {

    /** 引擎支持的规则字段（白名单） */
    private static final Map<String, String> RULE_FIELDS = new java.util.LinkedHashMap<>() {{
        put("growth", "累计成长值");
        put("totalRecharge", "累计充值本金");
        put("totalConsume", "累计实付消费");
        put("payAmount30d", "近 30 天消费额");
        put("payAmount90d", "近 90 天消费额");
        put("payAmount12m", "近 12 个月消费额");
        put("orderCount12m", "近 12 个月消费笔数");
        put("visitDays30d", "近 30 天到店天数");
    }};

    private static final List<String> RULE_OPS = List.of(">=", ">", "<=", "<", "==");

    private final MemberLevelMapper levelMapper;
    private final LevelRuleMapper ruleMapper;
    private final MemberMapper memberMapper;
    private final ObjectMapper objectMapper;

    /* ==================================================================
     *  等级定义
     * ================================================================== */

    public List<Map<String, Object>> list() {
        List<MemberLevel> levels = levelMapper.selectList(new LambdaQueryWrapper<MemberLevel>()
                .eq(MemberLevel::getDeleted, 0)
                .orderByAsc(MemberLevel::getLevelValue));
        // 一次 GROUP BY 取回各等级人数，替代「每个等级发一条 COUNT」的 N+1。
        // 等级数通常个位数，但这个接口会被等级页与会员详情反复调用。
        Map<Long, Long> countByLevel = new HashMap<>();
        for (Map<String, Object> row : memberMapper.countGroupByLevel()) {
            Object id = row.get("level_id");
            Object cnt = row.get("cnt");
            if (id != null && cnt instanceof Number n) {
                countByLevel.put(((Number) id).longValue(), n.longValue());
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (MemberLevel l : levels) {
            long memberCount = countByLevel.getOrDefault(l.getId(), 0L);
            Map<String, Object> m = new HashMap<>();
            m.put("id", l.getId());
            m.put("levelCode", l.getLevelCode());
            m.put("levelName", l.getLevelName());
            m.put("levelValue", l.getLevelValue());
            m.put("discountRate", l.getDiscountRate());
            m.put("discountText", l.getDiscountRate() == null || l.getDiscountRate().compareTo(java.math.BigDecimal.ONE) >= 0
                    ? "无折扣" : com.company.member.common.util.MoneyUtil.toPercent(l.getDiscountRate()));
            m.put("pointsRate", l.getPointsRate());
            m.put("cardDiscountRate", l.getCardDiscountRate());
            m.put("upgradeGiftPoints", l.getUpgradeGiftPoints());
            m.put("birthdayCouponTplId", l.getBirthdayCouponTplId());
            m.put("isDefault", l.getIsDefault());
            m.put("validMonths", l.getValidMonths());
            m.put("status", l.getStatus());
            m.put("sortNo", l.getSortNo());
            m.put("remark", l.getRemark());
            m.put("memberCount", memberCount);
            m.put("rule", readRule(l.getId()));
            result.add(m);
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Long id, Object cmd) {
        try {
            JsonNode node = objectMapper.valueToTree(cmd);
            MemberLevel level = id == null ? new MemberLevel() : levelMapper.selectById(id);
            if (level == null) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND, "等级不存在");
            }
            if (node.hasNonNull("levelName")) {
                level.setLevelName(node.get("levelName").asText());
            }
            if (node.hasNonNull("levelCode")) {
                level.setLevelCode(node.get("levelCode").asText());
            } else if (id == null) {
                level.setLevelCode("LEVEL" + System.currentTimeMillis() % 100000L);
            }
            if (node.hasNonNull("levelValue")) {
                level.setLevelValue(node.get("levelValue").asInt());
            } else if (id == null) {
                Integer max = levelMapper.selectList(new LambdaQueryWrapper<MemberLevel>()
                                .orderByDesc(MemberLevel::getLevelValue).last("LIMIT 1"))
                        .stream().findFirst().map(MemberLevel::getLevelValue).orElse(0);
                level.setLevelValue(max + 1);
            }
            if (node.hasNonNull("discountRate")) {
                level.setDiscountRate(node.get("discountRate").decimalValue());
            } else if (id == null) {
                level.setDiscountRate(java.math.BigDecimal.ONE);
            }
            if (node.hasNonNull("pointsRate")) {
                level.setPointsRate(node.get("pointsRate").decimalValue());
            } else if (id == null) {
                level.setPointsRate(java.math.BigDecimal.ONE);
            }
            if (node.hasNonNull("cardDiscountRate")) {
                level.setCardDiscountRate(node.get("cardDiscountRate").decimalValue());
            }
            if (node.hasNonNull("upgradeGiftPoints")) {
                level.setUpgradeGiftPoints(node.get("upgradeGiftPoints").asLong());
            } else if (id == null) {
                level.setUpgradeGiftPoints(0L);
            }
            if (node.hasNonNull("birthdayCouponTplId")) {
                level.setBirthdayCouponTplId(node.get("birthdayCouponTplId").asLong());
            }
            if (node.hasNonNull("isDefault")) {
                level.setIsDefault(node.get("isDefault").asInt());
            } else if (id == null) {
                level.setIsDefault(0);
            }
            if (node.hasNonNull("validMonths")) {
                level.setValidMonths(node.get("validMonths").asInt());
            }
            if (node.hasNonNull("status")) {
                level.setStatus(node.get("status").asInt());
            } else if (id == null) {
                level.setStatus(1);
            }
            if (node.hasNonNull("sortNo")) {
                level.setSortNo(node.get("sortNo").asInt());
            }
            if (node.hasNonNull("remark")) {
                level.setRemark(node.get("remark").asText());
            }
            level.setDeleted(0);

            // 默认等级唯一性：把其他等级置为非默认
            if (level.getIsDefault() != null && level.getIsDefault() == 1) {
                for (MemberLevel other : levelMapper.selectList(new LambdaQueryWrapper<MemberLevel>()
                        .eq(MemberLevel::getIsDefault, 1))) {
                    if (id == null || !other.getId().equals(id)) {
                        MemberLevel upd = new MemberLevel();
                        upd.setId(other.getId());
                        upd.setIsDefault(0);
                        levelMapper.updateById(upd);
                    }
                }
            }

            if (id == null) {
                levelMapper.insert(level);
            } else {
                levelMapper.updateById(level);
            }
            log.warn("[等级] {} 等级 {} (id={}), 折扣={}, 倍率={}", id == null ? "新增" : "修改",
                    level.getLevelName(), level.getId(), level.getDiscountRate(), level.getPointsRate());
            return list().stream().filter(m -> m.get("id").equals(level.getId())).findFirst()
                    .orElse(Map.of("id", level.getId()));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[等级] 保存失败", e);
            throw new BizException(ErrorCode.OPERATION_FAILED, "保存失败：" + e.getMessage());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        MemberLevel level = levelMapper.selectById(id);
        if (level == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "等级不存在");
        }
        long count = memberMapper.selectCount(new LambdaQueryWrapper<Member>()
                .eq(Member::getDeleted, 0).eq(Member::getLevelId, id));
        if (count > 0) {
            throw new BizException(ErrorCode.DATA_IN_USE, "该等级下仍有 " + count + " 名会员，不可删除");
        }
        if (level.getIsDefault() != null && level.getIsDefault() == 1) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "默认等级不可删除");
        }
        MemberLevel update = new MemberLevel();
        update.setId(id);
        update.setDeleted(1);
        levelMapper.updateById(update);
        log.warn("[等级] 删除等级 {}", level.getLevelName());
    }

    /* ==================================================================
     *  规则读写
     * ================================================================== */

    public Map<String, Object> getRule(Long levelId) {
        return readRule(levelId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveRule(Long levelId, Object cmd) {
        MemberLevel level = levelMapper.selectById(levelId);
        if (level == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "等级不存在");
        }
        try {
            JsonNode node = objectMapper.valueToTree(cmd);
            String logic = node.hasNonNull("logicType") ? node.get("logicType").asText() : "AND";
            JsonNode items = node.path("items");
            if (!items.isArray() || items.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "请至少配置一个条件");
            }
            // 校验字段与操作符白名单，避免保存引擎不认的规则
            List<Map<String, Object>> normalized = new ArrayList<>();
            for (JsonNode item : items) {
                String field = item.path("field").asText(null);
                String op = item.path("op").asText(null);
                if (field == null || !RULE_FIELDS.containsKey(field)) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "不支持的规则字段：" + field);
                }
                if (op == null || !RULE_OPS.contains(op)) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "不支持的操作符：" + op);
                }
                if (item.path("value").isMissingNode() || item.path("value").isNull()) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "条件值不能为空：" + field);
                }
                Map<String, Object> one = new HashMap<>();
                one.put("field", field);
                one.put("op", op);
                one.put("value", item.get("value").isNumber()
                        ? item.get("value").decimalValue() : item.get("value").asText());
                normalized.add(one);
            }
            Map<String, Object> ruleJson = new HashMap<>();
            ruleJson.put("logic", "OR".equalsIgnoreCase(logic) ? "OR" : "AND");
            ruleJson.put("items", normalized);
            if (node.hasNonNull("keepRule") && !node.get("keepRule").isNull()) {
                ruleJson.put("keepRule", objectMapper.convertValue(node.get("keepRule"), Map.class));
            }

            LevelRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<LevelRule>()
                    .eq(LevelRule::getLevelId, levelId)
                    .eq(LevelRule::getRuleType, 1)
                    .orderByDesc(LevelRule::getPriority)
                    .last("LIMIT 1"));
            String json = objectMapper.writeValueAsString(ruleJson);
            if (rule == null) {
                rule = new LevelRule();
                rule.setLevelId(levelId);
                rule.setRuleType(1);
                rule.setLogicType(ruleJson.get("logic").toString());
                rule.setRuleJson(json);
                rule.setPriority(level.getLevelValue() == null ? 0 : level.getLevelValue() * 10);
                rule.setStatus(1);
                ruleMapper.insert(rule);
            } else {
                rule.setLogicType(ruleJson.get("logic").toString());
                rule.setRuleJson(json);
                rule.setStatus(1);
                ruleMapper.updateById(rule);
            }

            // 等级自身的有效期同步
            if (node.hasNonNull("validMonths")) {
                MemberLevel upd = new MemberLevel();
                upd.setId(levelId);
                upd.setValidMonths(node.get("validMonths").asInt());
                levelMapper.updateById(upd);
            }
            log.warn("[等级] 规则保存 levelId={}, 条件 {} 项, 逻辑 {}", levelId, normalized.size(), ruleJson.get("logic"));
            return readRule(levelId);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[等级] 规则保存失败", e);
            throw new BizException(ErrorCode.OPERATION_FAILED, "保存失败：" + e.getMessage());
        }
    }

    /** 读取规则并转为结构体（供前端构造器回显） */
    private Map<String, Object> readRule(Long levelId) {
        LevelRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<LevelRule>()
                .eq(LevelRule::getLevelId, levelId)
                .eq(LevelRule::getRuleType, 1)
                .eq(LevelRule::getStatus, 1)
                .orderByDesc(LevelRule::getPriority)
                .last("LIMIT 1"));
        Map<String, Object> result = new HashMap<>();
        result.put("configured", rule != null);
        if (rule == null) {
            result.put("logicType", "AND");
            result.put("items", List.of());
            return result;
        }
        result.put("logicType", rule.getLogicType());
        try {
            JsonNode json = objectMapper.readTree(rule.getRuleJson());
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode item : json.path("items")) {
                Map<String, Object> one = new HashMap<>();
                String field = item.path("field").asText();
                one.put("field", field);
                one.put("fieldLabel", RULE_FIELDS.getOrDefault(field, field));
                one.put("op", item.path("op").asText());
                one.put("value", item.path("value").isNumber()
                        ? item.path("value").decimalValue() : item.path("value").asText());
                items.add(one);
            }
            result.put("items", items);
            if (json.has("keepRule")) {
                result.put("keepRule", objectMapper.convertValue(json.get("keepRule"), Map.class));
            }
        } catch (Exception e) {
            log.warn("[等级] 规则解析失败 levelId={}: {}", levelId, e.getMessage());
            result.put("items", List.of());
        }
        return result;
    }

    /** 前端构造器可用的字段与操作符 */
    public List<Map<String, Object>> ruleFields() {
        List<Map<String, Object>> list = new ArrayList<>();
        RULE_FIELDS.forEach((k, v) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("field", k);
            m.put("label", v);
            m.put("ops", RULE_OPS);
            list.add(m);
        });
        return list;
    }
}
