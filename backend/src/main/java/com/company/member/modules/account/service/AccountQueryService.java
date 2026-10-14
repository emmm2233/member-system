package com.company.member.modules.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.member.common.result.PageResult;
import com.company.member.modules.account.entity.BalanceFlow;
import com.company.member.modules.account.entity.GrowthFlow;
import com.company.member.modules.account.entity.MemberAccount;
import com.company.member.modules.account.entity.PointBatch;
import com.company.member.modules.account.entity.PointFlow;
import com.company.member.modules.account.mapper.BalanceFlowMapper;
import com.company.member.modules.account.mapper.GrowthFlowMapper;
import com.company.member.modules.account.mapper.MemberAccountMapper;
import com.company.member.modules.account.mapper.PointBatchMapper;
import com.company.member.modules.account.mapper.PointFlowMapper;
import com.company.member.modules.store.mapper.StoreMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 资产查询与对账服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountQueryService {

    private final MemberAccountMapper accountMapper;
    private final BalanceFlowMapper balanceFlowMapper;
    private final PointFlowMapper pointFlowMapper;
    private final GrowthFlowMapper growthFlowMapper;
    private final PointBatchMapper pointBatchMapper;
    private final StoreMapper storeMapper;

    /* ==================== 余额流水 ==================== */

    public PageResult<Map<String, Object>> balanceFlows(Long memberId, Integer accountType, Integer bizType,
                                                        LocalDate startDate, LocalDate endDate,
                                                        int pageNum, int pageSize) {
        LambdaQueryWrapper<BalanceFlow> w = new LambdaQueryWrapper<BalanceFlow>()
                .eq(memberId != null, BalanceFlow::getMemberId, memberId)
                .eq(accountType != null, BalanceFlow::getAccountType, accountType)
                .eq(bizType != null, BalanceFlow::getBizType, bizType)
                .ge(startDate != null, BalanceFlow::getOccurTime, startDate == null ? null : startDate.atStartOfDay())
                .lt(endDate != null, BalanceFlow::getOccurTime, endDate == null ? null : endDate.plusDays(1).atStartOfDay())
                .orderByDesc(BalanceFlow::getId);
        Page<BalanceFlow> page = balanceFlowMapper.selectPage(new Page<>(pageNum, normalizeSize(pageSize)), w);
        Map<Long, String> storeNames = loadStoreNames(page.getRecords().stream()
                .map(BalanceFlow::getStoreId).filter(java.util.Objects::nonNull).toList());
        List<Map<String, Object>> list = new ArrayList<>();
        for (BalanceFlow f : page.getRecords()) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", f.getId());
            m.put("flowNo", f.getFlowNo());
            m.put("memberId", f.getMemberId());
            m.put("accountType", f.getAccountType());
            m.put("accountTypeText", f.getAccountType() != null && f.getAccountType() == 1 ? "本金" : "赠送");
            m.put("bizType", f.getBizType());
            m.put("bizTypeText", balanceBizText(f.getBizType()));
            m.put("changeAmount", f.getChangeAmount());
            m.put("beforeAmount", f.getBeforeAmount());
            m.put("afterAmount", f.getAfterAmount());
            m.put("bizNo", f.getBizNo());
            // 幂等键：对账/排查时要回答「这笔变动的唯一凭据是什么」。
            // 流水上是 (idempotency_key, biz_type) 唯一索引，排查重复入账/漏入账
            // 必须能直接从接口看到这个键，否则只能翻数据库。
            m.put("idempotencyKey", f.getIdempotencyKey());
            m.put("storeId", f.getStoreId());
            m.put("storeName", storeNames.get(f.getStoreId()));
            m.put("operatorId", f.getOperatorId());
            m.put("remark", f.getRemark());
            m.put("occurTime", f.getOccurTime());
            list.add(m);
        }
        return PageResult.of(list, page.getTotal(), pageNum, page.getSize());
    }

    /* ==================== 积分流水 ==================== */

    public PageResult<Map<String, Object>> pointFlows(Long memberId, Integer bizType,
                                                      LocalDate startDate, LocalDate endDate,
                                                      int pageNum, int pageSize) {
        LambdaQueryWrapper<PointFlow> w = new LambdaQueryWrapper<PointFlow>()
                .eq(memberId != null, PointFlow::getMemberId, memberId)
                .eq(bizType != null, PointFlow::getBizType, bizType)
                .ge(startDate != null, PointFlow::getOccurTime, startDate == null ? null : startDate.atStartOfDay())
                .lt(endDate != null, PointFlow::getOccurTime, endDate == null ? null : endDate.plusDays(1).atStartOfDay())
                .orderByDesc(PointFlow::getId);
        Page<PointFlow> page = pointFlowMapper.selectPage(new Page<>(pageNum, normalizeSize(pageSize)), w);
        Map<Long, String> storeNames = loadStoreNames(page.getRecords().stream()
                .map(PointFlow::getStoreId).filter(java.util.Objects::nonNull).toList());
        List<Map<String, Object>> list = new ArrayList<>();
        for (PointFlow f : page.getRecords()) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", f.getId());
            m.put("flowNo", f.getFlowNo());
            m.put("memberId", f.getMemberId());
            m.put("bizType", f.getBizType());
            m.put("bizTypeText", pointBizText(f.getBizType()));
            m.put("changePoints", f.getChangePoints());
            m.put("beforePoints", f.getBeforePoints());
            m.put("afterPoints", f.getAfterPoints());
            m.put("bizNo", f.getBizNo());
            m.put("expireTime", f.getExpireTime());
            m.put("storeId", f.getStoreId());
            m.put("storeName", storeNames.get(f.getStoreId()));
            m.put("remark", f.getRemark());
            m.put("occurTime", f.getOccurTime());
            list.add(m);
        }
        return PageResult.of(list, page.getTotal(), pageNum, page.getSize());
    }

    /* ==================== 成长值流水 ==================== */

    public PageResult<GrowthFlow> growthFlows(Long memberId, int pageNum, int pageSize) {
        LambdaQueryWrapper<GrowthFlow> w = new LambdaQueryWrapper<GrowthFlow>()
                .eq(memberId != null, GrowthFlow::getMemberId, memberId)
                .orderByDesc(GrowthFlow::getId);
        return PageResult.of(growthFlowMapper.selectPage(new Page<>(pageNum, normalizeSize(pageSize)), w));
    }

    /* ==================== 积分批次（到期日历） ==================== */

    public List<PointBatch> pointBatches(Long memberId, boolean onlyValid) {
        LambdaQueryWrapper<PointBatch> w = new LambdaQueryWrapper<PointBatch>()
                .eq(PointBatch::getMemberId, memberId)
                .eq(onlyValid, PointBatch::getStatus, 1)
                .orderByAsc(PointBatch::getExpireTime);
        return pointBatchMapper.selectList(w);
    }

    public List<PointBatch> expiringBatches(Long memberId, int days) {
        return pointBatchMapper.selectExpiringSoon(memberId, LocalDateTime.now().plusDays(days));
    }

    /* ==================== 对账 ==================== */

    /**
     * 对账：账户余额/积分与流水、批次比对
     *
     * @return 差异报告（diffCount = 0 表示账实相符）
     */
    public Map<String, Object> reconcile() {
        long balanceDiff = accountMapper.countBalanceDiff();
        long pointDiff = accountMapper.countPointDiff();
        Map<String, Object> result = new HashMap<>();
        result.put("balanceDiffCount", balanceDiff);
        result.put("pointDiffCount", pointDiff);
        result.put("diffCount", balanceDiff + pointDiff);
        result.put("checkTime", LocalDateTime.now());
        result.put("conclusion", (balanceDiff + pointDiff) == 0 ? "账实相符" : "存在差异，请核查");
        if (balanceDiff + pointDiff > 0) {
            log.warn("[对账] 发现差异 balanceDiff={}, pointDiff={}", balanceDiff, pointDiff);
        }
        return result;
    }

    /** 储值/积分负债汇总 */
    public Map<String, Object> liability() {
        List<MemberAccount> all = accountMapper.selectList(new LambdaQueryWrapper<>());
        BigDecimal principal = BigDecimal.ZERO;
        BigDecimal gift = BigDecimal.ZERO;
        long points = 0L;
        for (MemberAccount a : all) {
            principal = principal.add(a.getBalance() == null ? BigDecimal.ZERO : a.getBalance());
            gift = gift.add(a.getGiftBalance() == null ? BigDecimal.ZERO : a.getGiftBalance());
            points += a.getPoints() == null ? 0L : a.getPoints();
        }
        Map<String, Object> m = new HashMap<>();
        m.put("memberCount", all.size());
        m.put("balanceLiability", principal);
        m.put("giftBalanceLiability", gift);
        m.put("pointsLiability", points);
        return m;
    }

    /** 单会员账实核对（用于排障） */
    public Map<String, Object> reconcileOne(Long memberId) {
        MemberAccount acc = accountMapper.selectByMemberId(memberId);
        Map<String, Object> m = new HashMap<>();
        if (acc == null) {
            m.put("exists", false);
            return m;
        }
        BigDecimal principalFlow = balanceFlowMapper.sumByMemberAndType(memberId, 1);
        BigDecimal giftFlow = balanceFlowMapper.sumByMemberAndType(memberId, 2);
        Long pointFlow = pointFlowMapper.sumByMember(memberId);
        Long pointBatch = pointBatchMapper.sumRemainByMember(memberId);
        Long growthFlow = growthFlowMapper.sumByMember(memberId);
        m.put("exists", true);
        m.put("balance", acc.getBalance());
        m.put("balanceFlowSum", principalFlow);
        m.put("balanceMatch", acc.getBalance().compareTo(principalFlow) == 0);
        m.put("giftBalance", acc.getGiftBalance());
        m.put("giftFlowSum", giftFlow);
        m.put("giftMatch", acc.getGiftBalance().compareTo(giftFlow) == 0);
        m.put("points", acc.getPoints());
        m.put("pointsFlowSum", pointFlow);
        m.put("pointsBatchSum", pointBatch);
        m.put("pointsMatch", java.util.Objects.equals(acc.getPoints(), pointFlow));
        m.put("pointsBatchMatch", java.util.Objects.equals(acc.getPoints(), pointBatch));
        m.put("growth", acc.getGrowth());
        m.put("growthFlowSum", growthFlow);
        m.put("growthMatch", java.util.Objects.equals(acc.getGrowth(), growthFlow));
        return m;
    }

    /* ==================== 工具 ==================== */

    private Map<Long, String> loadStoreNames(List<Long> storeIds) {
        if (storeIds.isEmpty()) {
            return Map.of();
        }
        return storeMapper.selectBatchIds(storeIds.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(s -> s.getId(), s -> s.getStoreName(), (a, b) -> a));
    }

    private static int normalizeSize(int pageSize) {
        return pageSize < 1 ? 20 : Math.min(pageSize, 200);
    }

    public static String balanceBizText(Integer bizType) {
        if (bizType == null) {
            return "";
        }
        return switch (bizType) {
            case 1 -> "充值";
            case 2 -> "消费";
            case 3 -> "退款";
            case 4 -> "调整";
            case 5 -> "赠送";
            case 6 -> "过期";
            case 7 -> "转入";
            case 8 -> "转出";
            case 9 -> "合并";
            default -> "其他";
        };
    }

    public static String pointBizText(Integer bizType) {
        if (bizType == null) {
            return "";
        }
        return switch (bizType) {
            case 1 -> "消费获得";
            case 2 -> "充值赠送";
            case 3 -> "兑换扣减";
            case 4 -> "过期";
            case 5 -> "调整";
            case 6 -> "退单扣回";
            case 7 -> "升级赠送";
            default -> "其他";
        };
    }
}
