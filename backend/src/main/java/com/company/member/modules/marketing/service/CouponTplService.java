package com.company.member.modules.marketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.modules.marketing.dto.CouponGrantCmd;
import com.company.member.modules.marketing.dto.CouponTplSaveCmd;
import com.company.member.modules.marketing.entity.CouponTpl;
import com.company.member.modules.marketing.entity.MemberCoupon;
import com.company.member.modules.marketing.mapper.CouponTplMapper;
import com.company.member.modules.marketing.mapper.MemberCouponMapper;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.mapper.MemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 券模板管理与发券向导服务
 *
 * <p>发券支持三种模式：
 * <ul>
 *   <li>{@code MEMBER}：指定会员（前端粘贴手机号/会员号或从列表勾选）</li>
 *   <li>{@code CONDITION}：按等级/标签/时间/金额等条件筛选（可先"预估人数"再发放）</li>
 *   <li>{@code ALL}：全部正常会员（强制二次确认）</li>
 * </ul>
 *
 * <p>批量发放按 200 人一批提交，逐批记录成功/失败，返回逐条统计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponTplService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int BATCH_SIZE = 200;

    private final CouponTplMapper tplMapper;
    private final MemberCouponMapper couponMapper;
    private final MemberMapper memberMapper;
    private final CouponService couponService;

    /** 发券任务进度（内存态，任务执行完保留 30 分钟供查询） */
    private final Map<String, GrantProgress> progressMap = new ConcurrentHashMap<>();

    /* ==================================================================
     *  券模板 CRUD
     * ================================================================== */

    public List<CouponTpl> list(Boolean onlyEnabled, String keyword) {
        LambdaQueryWrapper<CouponTpl> w = new LambdaQueryWrapper<CouponTpl>()
                .eq(CouponTpl::getDeleted, 0)
                .eq(Boolean.TRUE.equals(onlyEnabled), CouponTpl::getStatus, 1)
                .like(keyword != null && !keyword.isBlank(), CouponTpl::getTplName, keyword)
                .orderByAsc(CouponTpl::getSortNo)
                .orderByDesc(CouponTpl::getId);
        return tplMapper.selectList(w);
    }

    public CouponTpl detail(Long id) {
        CouponTpl tpl = tplMapper.selectById(id);
        if (tpl == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "券模板不存在");
        }
        return tpl;
    }

    @Transactional(rollbackFor = Exception.class)
    public CouponTpl create(CouponTplSaveCmd cmd) {
        CouponTpl tpl = new CouponTpl();
        apply(tpl, cmd);
        tpl.setTplCode(cmd.getTplCode() == null || cmd.getTplCode().isBlank()
                ? "TPL" + System.currentTimeMillis() % 100000000L : cmd.getTplCode());
        // 唯一编码兜底
        if (tplMapper.selectCount(new LambdaQueryWrapper<CouponTpl>()
                .eq(CouponTpl::getTplCode, tpl.getTplCode())) > 0) {
            tpl.setTplCode(tpl.getTplCode() + "-" + (System.currentTimeMillis() % 1000));
        }
        tpl.setIssuedQty(0);
        tpl.setUsedQty(0);
        tpl.setDeleted(0);
        tpl.setTenantId(0L);
        validate(tpl);
        tplMapper.insert(tpl);
        log.info("[券模板] 新增 id={}, name={}, type={}", tpl.getId(), tpl.getTplName(), tpl.getCouponType());
        return tpl;
    }

    /**
     * 编辑券模板
     *
     * <p>注意：模板改动**不影响已发放的券**（券实例在发放时已固化快照）。
     */
    @Transactional(rollbackFor = Exception.class)
    public CouponTpl update(Long id, CouponTplSaveCmd cmd) {
        CouponTpl tpl = detail(id);
        apply(tpl, cmd);
        tpl.setId(id);
        validate(tpl);
        tplMapper.updateById(tpl);
        log.info("[券模板] 修改 id={}, name={}", id, tpl.getTplName());
        return tplMapper.selectById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status) {
        CouponTpl tpl = detail(id);
        tpl.setStatus(status);
        tplMapper.updateById(tpl);
        log.info("[券模板] 状态变更 id={}, status={}", id, status);
    }

    /** 删除（逻辑删除；已发放过的模板只允许停用，避免历史券无法追溯） */
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        CouponTpl tpl = detail(id);
        if (tpl.getIssuedQty() != null && tpl.getIssuedQty() > 0) {
            throw new BizException(ErrorCode.DATA_IN_USE,
                    "该模板已发放 " + tpl.getIssuedQty() + " 张券，不能删除，可改为停用");
        }
        CouponTpl update = new CouponTpl();
        update.setId(id);
        update.setDeleted(1);
        tplMapper.updateById(update);
        log.info("[券模板] 删除 id={}", id);
    }

    /* ==================================================================
     *  发券
     * ================================================================== */

    /** 预估目标人数（发券前展示，避免误发全量） */
    public Map<String, Object> estimate(CouponGrantCmd cmd) {
        List<Long> ids = resolveTargets(cmd, true);
        Map<String, Object> m = new HashMap<>();
        m.put("count", ids.size());
        m.put("limited", ids.size() >= limitOf(cmd));
        m.put("limit", limitOf(cmd));
        return m;
    }

    /**
     * 执行发券
     *
     * @return 任务号 + 逐条统计
     */
    public Map<String, Object> grant(CouponGrantCmd cmd) {
        CouponTpl tpl = detail(cmd.getTplId());
        if (tpl.getStatus() == null || tpl.getStatus() != 1) {
            throw new BizException(ErrorCode.COUPON_UNAVAILABLE, "券模板已停用，请先启用");
        }
        if ("ALL".equalsIgnoreCase(cmd.getTargetType()) && !Boolean.TRUE.equals(cmd.getConfirmAll())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "全量发券需二次确认（confirmAll=true）");
        }
        int qty = cmd.getQtyPerMember() == null || cmd.getQtyPerMember() < 1 ? 1 : cmd.getQtyPerMember();
        List<Long> targets = resolveTargets(cmd, false);
        if (targets.isEmpty()) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "没有符合条件的会员，请调整筛选条件");
        }

        String taskNo = "GT" + System.currentTimeMillis();
        GrantProgress progress = new GrantProgress(taskNo, targets.size());
        progressMap.put(taskNo, progress);

        int success = 0;
        int fail = 0;
        List<Map<String, Object>> errors = new ArrayList<>();

        // 按批提交，单批失败不影响已成功的部分
        for (int i = 0; i < targets.size(); i += BATCH_SIZE) {
            List<Long> batch = targets.subList(i, Math.min(i + BATCH_SIZE, targets.size()));
            for (Long memberId : batch) {
                try {
                    couponService.grantForce(memberId, tpl.getId(), qty,
                            "GRANT", "admin-grant:" + taskNo);
                    success++;
                } catch (Exception e) {
                    fail++;
                    if (errors.size() < 50) {
                        Map<String, Object> err = new HashMap<>();
                        err.put("memberId", memberId);
                        err.put("errorMsg", e.getMessage());
                        errors.add(err);
                    }
                    log.warn("[发券] 单个会员发放失败 memberId={}, 原因={}", memberId, e.getMessage());
                }
            }
            progress.done.set(i + batch.size());
        }

        progress.success = success;
        progress.fail = fail;
        progress.finishedAt = LocalDateTime.now();

        Map<String, Object> result = new HashMap<>();
        result.put("taskNo", taskNo);
        result.put("tplId", tpl.getId());
        result.put("tplName", tpl.getTplName());
        result.put("targetCount", targets.size());
        result.put("successCount", success);
        result.put("failCount", fail);
        result.put("qtyPerMember", qty);
        result.put("totalIssued", success * qty);
        result.put("errors", errors);
        result.put("reason", cmd.getReason());
        result.put("tip", fail == 0
                ? "发放完成，共 " + success + " 人 / " + (success * qty) + " 张"
                : "发放完成，成功 " + success + " 人，失败 " + fail + " 人");
        log.warn("[发券] {} -> 目标 {} 人，成功 {}，失败 {}，每人 {} 张，原因={}",
                taskNo, targets.size(), success, fail, qty, cmd.getReason());
        return result;
    }

    public Map<String, Object> grantProgress(String taskNo) {
        GrantProgress p = progressMap.get(taskNo);
        if (p == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "发券任务不存在或已过期");
        }
        Map<String, Object> m = new HashMap<>();
        m.put("taskNo", p.taskNo);
        m.put("total", p.total);
        m.put("done", p.done.get());
        m.put("successCount", p.success);
        m.put("failCount", p.fail);
        m.put("finished", p.finishedAt != null);
        m.put("finishedAt", p.finishedAt == null ? null : p.finishedAt.format(DT));
        m.put("percent", p.total == 0 ? 100 : Math.min(p.done.get() * 100 / p.total, 100));
        return m;
    }

    /** 发券任务进度（内存态） */
    private static final class GrantProgress {
        final String taskNo;
        final int total;
        final AtomicInteger done = new AtomicInteger(0);
        volatile int success;
        volatile int fail;
        volatile LocalDateTime finishedAt;

        GrantProgress(String taskNo, int total) {
            this.taskNo = taskNo;
            this.total = total;
        }
    }

    /* ==================================================================
     *  目标会员解析
     * ================================================================== */

    private int limitOf(CouponGrantCmd cmd) {
        Integer limit = cmd.getCondition() == null ? null : cmd.getCondition().getLimit();
        if (limit == null || limit <= 0) {
            return 50000;
        }
        return Math.min(limit, 50000);
    }

    private List<Long> resolveTargets(CouponGrantCmd cmd, boolean estimateOnly) {
        String type = cmd.getTargetType() == null ? "MEMBER" : cmd.getTargetType().toUpperCase();
        int limit = limitOf(cmd);
        if ("MEMBER".equals(type)) {
            List<Long> ids = cmd.getMemberIds() == null ? List.of() : cmd.getMemberIds();
            if (ids.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "请选择要发券的会员");
            }
            // 校验会员存在
            List<Member> members = memberMapper.selectBatchIds(ids);
            return members.stream().map(Member::getId).distinct().limit(limit).toList();
        }

        QueryWrapper<Member> w = new QueryWrapper<>();
        if ("ALL".equals(type)) {
            w.eq("deleted", 0).eq("status", 1);
        } else {
            CouponGrantCmd.Condition c = cmd.getCondition();
            if (c == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "请设置筛选条件");
            }
            w.eq("deleted", 0);
            w.eq("status", c.getStatus() == null ? 1 : c.getStatus());
            if (c.getLevelIds() != null && !c.getLevelIds().isEmpty()) {
                w.in("level_id", c.getLevelIds());
            }
            if (c.getStoreId() != null) {
                w.eq("store_id", c.getStoreId());
            }
            if (c.getCreatedStart() != null) {
                w.ge("created_at", c.getCreatedStart().atStartOfDay());
            }
            if (c.getCreatedEnd() != null) {
                w.lt("created_at", c.getCreatedEnd().plusDays(1).atStartOfDay());
            }
            if (c.getBirthdayMonth() != null) {
                w.apply("MONTH(birthday) = {0}", c.getBirthdayMonth());
            }
            if (c.getTagIds() != null && !c.getTagIds().isEmpty()) {
                for (Long tagId : c.getTagIds()) {
                    w.exists("SELECT 1 FROM m_member_tag_rel r WHERE r.member_id = m_member.id AND r.tag_id = {0}", tagId);
                }
            }
            // 资产/统计相关条件走子查询
            if (c.getMinTotalConsume() != null) {
                w.exists("SELECT 1 FROM m_member_account a WHERE a.member_id = m_member.id AND a.total_consume >= {0}",
                        c.getMinTotalConsume());
            }
            if (c.getMinBalance() != null) {
                w.exists("SELECT 1 FROM m_member_account a WHERE a.member_id = m_member.id AND (a.balance + a.gift_balance) >= {0}",
                        c.getMinBalance());
            }
            if (c.getLastConsumeStart() != null) {
                w.exists("SELECT 1 FROM m_member_stat s WHERE s.member_id = m_member.id AND s.last_consume_at >= {0}",
                        c.getLastConsumeStart().atStartOfDay());
            }
            if (c.getLastConsumeEnd() != null) {
                w.exists("SELECT 1 FROM m_member_stat s WHERE s.member_id = m_member.id AND s.last_consume_at < {0}",
                        c.getLastConsumeEnd().plusDays(1).atStartOfDay());
            }
        }
        w.select("id").orderByAsc("id").last("LIMIT " + limit);

        List<Object> ids = memberMapper.selectObjs(w);
        List<Long> result = new ArrayList<>(ids.size());
        for (Object o : ids) {
            if (o != null) {
                result.add(((Number) o).longValue());
            }
        }
        return result;
    }

    /* ==================================================================
     *  校验与字段映射
     * ================================================================== */

    private void validate(CouponTpl tpl) {
        if (tpl.getCouponType() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "券类型不能为空");
        }
        BigDecimal face = tpl.getFaceValue();
        if (face == null || face.signum() <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "面额/折扣值必须大于 0");
        }
        if (tpl.getCouponType() == CouponTpl.TYPE_DISCOUNT
                && (face.compareTo(BigDecimal.ONE) > 0 && face.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "折扣值应为 0-1 的小数（如 0.88）或 1-100 的百分数");
        }
        if (tpl.getValidType() != null && tpl.getValidType() == 1) {
            if (tpl.getValidStart() == null || tpl.getValidEnd() == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "绝对有效期需同时填写开始与结束时间");
            }
            if (!tpl.getValidEnd().isAfter(tpl.getValidStart())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "结束时间必须晚于开始时间");
            }
        } else {
            if (tpl.getValidDays() == null || tpl.getValidDays() <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "领取后有效天数必须大于 0");
            }
        }
    }

    private void apply(CouponTpl tpl, CouponTplSaveCmd cmd) {
        tpl.setTplName(cmd.getTplName());
        tpl.setCouponType(cmd.getCouponType());
        tpl.setFaceValue(cmd.getFaceValue());
        tpl.setMinAmount(cmd.getMinAmount() == null ? BigDecimal.ZERO : cmd.getMinAmount());
        tpl.setMaxDiscount(cmd.getMaxDiscount());
        tpl.setValidType(cmd.getValidType() == null ? 2 : cmd.getValidType());
        tpl.setValidStart(cmd.getValidStart());
        tpl.setValidEnd(cmd.getValidEnd());
        tpl.setValidDays(cmd.getValidDays());
        tpl.setTotalQty(cmd.getTotalQty() == null ? -1 : cmd.getTotalQty());
        tpl.setLimitPerMember(cmd.getLimitPerMember() == null ? 1 : cmd.getLimitPerMember());
        tpl.setScopeStore(joinIds(cmd.getScopeStoreIds()));
        tpl.setScopeProduct(joinIds(cmd.getScopeProductIds()));
        tpl.setExcludeProduct(joinIds(cmd.getExcludeProductIds()));
        tpl.setAllowStack(Boolean.TRUE.equals(cmd.getAllowStack()) ? 1 : 0);
        tpl.setStackGroup(cmd.getStackGroup());
        tpl.setClaimable(Boolean.TRUE.equals(cmd.getClaimable()) ? 1 : 0);
        tpl.setUseRuleDesc(cmd.getUseRuleDesc());
        tpl.setImage(cmd.getImage());
        tpl.setStatus(cmd.getStatus() == null ? 1 : cmd.getStatus());
        tpl.setSortNo(cmd.getSortNo() == null ? 0 : cmd.getSortNo());
    }

    private static String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "-1";
        }
        if (ids.contains(-1L)) {
            return "-1";
        }
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    /* ==================================================================
     *  券效果统计
     * ================================================================== */

    /** 单模板效果：发放量、领取量、核销量、核销率 */
    public Map<String, Object> tplStatistics(Long tplId) {
        CouponTpl tpl = detail(tplId);
        long total = couponMapper.selectCount(new LambdaQueryWrapper<MemberCoupon>()
                .eq(MemberCoupon::getTplId, tplId));
        long used = couponMapper.selectCount(new LambdaQueryWrapper<MemberCoupon>()
                .eq(MemberCoupon::getTplId, tplId).eq(MemberCoupon::getStatus, MemberCoupon.STATUS_USED));
        long unused = couponMapper.selectCount(new LambdaQueryWrapper<MemberCoupon>()
                .eq(MemberCoupon::getTplId, tplId).eq(MemberCoupon::getStatus, MemberCoupon.STATUS_UNUSED));
        long expired = couponMapper.selectCount(new LambdaQueryWrapper<MemberCoupon>()
                .eq(MemberCoupon::getTplId, tplId).eq(MemberCoupon::getStatus, MemberCoupon.STATUS_EXPIRED));
        Map<String, Object> m = new HashMap<>();
        m.put("tplId", tplId);
        m.put("tplName", tpl.getTplName());
        m.put("issuedQty", tpl.getIssuedQty());
        m.put("couponCount", total);
        m.put("usedCount", used);
        m.put("unusedCount", unused);
        m.put("expiredCount", expired);
        m.put("useRate", total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(used * 100.0 / total).setScale(2, java.math.RoundingMode.HALF_UP));
        return m;
    }
}
