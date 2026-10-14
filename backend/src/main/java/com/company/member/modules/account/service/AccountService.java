package com.company.member.modules.account.service;

import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.util.BizNoGenerator;
import com.company.member.common.util.MoneyUtil;
import com.company.member.modules.account.dto.AccountChangeCmd;
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
import com.company.member.modules.account.vo.AccountChangeResult;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.mapper.MemberMapper;
import com.company.member.modules.system.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 资产账务服务 —— <b>系统内唯一允许变动会员资产的地方</b>
 *
 * <p>铁律：
 * <ol>
 *   <li>所有资产变动必须走 {@link #change}，其他模块禁止直接 update 账户表</li>
 *   <li>变动与流水在同一事务内完成</li>
 *   <li>账户读取使用行锁（SELECT ... FOR UPDATE），SQL 层再做余额充足校验</li>
 * </ol>
 *
 * <p>四类资产：本金余额、赠送余额、积分（含 FIFO 批次）、成长值。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    /** 账户类型 */
    public static final int ACC_PRINCIPAL = 1;
    public static final int ACC_GIFT = 2;

    /** 业务类型：余额 */
    public static final int BIZ_RECHARGE = 1;
    public static final int BIZ_CONSUME = 2;
    public static final int BIZ_REFUND = 3;
    public static final int BIZ_ADJUST = 4;
    public static final int BIZ_GIFT = 5;
    public static final int BIZ_EXPIRE = 6;
    public static final int BIZ_MERGE_IN = 7;
    public static final int BIZ_MERGE_OUT = 8;

    /** 业务类型：积分 */
    public static final int PBIZ_CONSUME_GAIN = 1;
    public static final int PBIZ_RECHARGE_GIFT = 2;
    public static final int PBIZ_EXCHANGE = 3;
    public static final int PBIZ_EXPIRE = 4;
    public static final int PBIZ_ADJUST = 5;
    public static final int PBIZ_REFUND_DEDUCT = 6;
    public static final int PBIZ_LEVEL_GIFT = 7;
    /** 推荐有礼奖励积分 */
    public static final int PBIZ_REFERRAL = 8;
    /** 生日礼赠送积分 */
    public static final int PBIZ_BIRTHDAY = 9;

    /** 业务类型：成长值 */
    public static final int GBIZ_CONSUME = 1;
    public static final int GBIZ_ADJUST = 2;
    public static final int GBIZ_RESET = 3;
    public static final int GBIZ_REFUND_DEDUCT = 4;
    public static final int GBIZ_ACTIVITY = 5;
    /** 推荐有礼奖励成长值 */
    public static final int GBIZ_REFERRAL = 6;
    /** 生日礼赠送成长值 */
    public static final int GBIZ_BIRTHDAY = 7;

    private final MemberAccountMapper accountMapper;
    private final BalanceFlowMapper balanceFlowMapper;
    private final PointFlowMapper pointFlowMapper;
    private final PointBatchMapper pointBatchMapper;
    private final GrowthFlowMapper growthFlowMapper;
    private final MemberMapper memberMapper;
    private final BizNoGenerator noGenerator;
    private final ConfigService configService;

    /* ==================================================================
     *  统一入口
     * ================================================================== */

    /**
     * 资产变动统一入口
     *
     * @return 变动后的账户快照
     */
    @Transactional(rollbackFor = Exception.class)
    public AccountChangeResult change(AccountChangeCmd cmd) {
        // 1. 基础校验
        validate(cmd);

        // 2. 会员状态校验
        Member member = memberMapper.selectById(cmd.getMemberId());
        if (member == null) {
            throw new BizException(ErrorCode.MEMBER_NOT_FOUND);
        }
        if (member.isCancelled()) {
            throw new BizException(ErrorCode.MEMBER_CANCELLED);
        }
        if (member.isFrozen() && cmd.getBizType() != null && cmd.getBizType() != BIZ_ADJUST) {
            throw new BizException(ErrorCode.MEMBER_FROZEN);
        }

        // 3. 幂等读侧：同幂等键的同类流水已存在则直接返回（幂等兜底第一层）
        //
        //    三张流水表都要查，不能只查余额：
        //    纯积分/纯成长值调整没有余额变动，若跳过这一步，重复请求会走到
        //    INSERT 撞唯一索引抛 DuplicateKeyException —— 结果虽然安全（不会重复加分），
        //    但用户看到的是「系统繁忙」，而不是「请勿重复提交」。
        if (cmd.getIdempotencyKey() != null && !cmd.getIdempotencyKey().isBlank()) {
            boolean duplicated = false;
            if (cmd.hasBalanceChange()) {
                duplicated = balanceFlowMapper.countByIdempotency(cmd.getIdempotencyKey(), cmd.getBizType()) > 0;
            }
            if (!duplicated && cmd.hasPointsChange()) {
                duplicated = pointFlowMapper.countByIdempotency(cmd.getIdempotencyKey(), cmd.getBizType()) > 0;
            }
            if (!duplicated && cmd.hasGrowthChange()) {
                duplicated = growthFlowMapper.countByIdempotency(cmd.getIdempotencyKey(), cmd.getBizType()) > 0;
            }
            if (duplicated) {
                log.info("[资产] 命中幂等，跳过变动：key={}, bizType={}", cmd.getIdempotencyKey(), cmd.getBizType());
                return idempotentResult(cmd);
            }
        }

        // 4. 行锁读取账户
        MemberAccount acc = accountMapper.selectForUpdate(cmd.getMemberId());
        if (acc == null) {
            acc = initAccount(cmd.getMemberId());
        }

        AccountChangeResult result = new AccountChangeResult();
        result.setBalanceChange(cmd.balanceDeltaOrZero());
        result.setGiftChange(cmd.giftDeltaOrZero());
        result.setPointsChange(cmd.pointsDeltaOrZero());
        result.setGrowthChange(cmd.growthDeltaOrZero());

        // 5. 余额变动
        if (cmd.hasBalanceChange()) {
            applyBalance(acc, cmd);
        }

        // 6. 积分变动（含批次）
        if (cmd.hasPointsChange()) {
            Long batchId = applyPoints(acc, cmd);
            result.setPointBatchId(batchId);
        }

        // 7. 成长值变动
        if (cmd.hasGrowthChange()) {
            applyGrowth(acc, cmd);
        }

        // 8. 返回最新快照
        MemberAccount latest = accountMapper.selectById(acc.getId());
        AccountChangeResult snap = AccountChangeResult.of(latest);
        snap.setBalanceChange(result.getBalanceChange());
        snap.setGiftChange(result.getGiftChange());
        snap.setPointsChange(result.getPointsChange());
        snap.setGrowthChange(result.getGrowthChange());
        snap.setPointBatchId(result.getPointBatchId());
        return snap;
    }

    /* ==================================================================
     *  语义化封装（供各业务模块调用）
     * ================================================================== */

    /** 充值入账：本金 + 赠送（赠送可为 0） */
    @Transactional(rollbackFor = Exception.class)
    public AccountChangeResult recharge(Long memberId, BigDecimal principal, BigDecimal gift, String orderNo,
                                        Long orderId, Long storeId, Long operatorId, String shiftNo, String remark) {
        MoneyUtil.requirePositive(principal, "充值本金");
        MoneyUtil.requireNonNegative(gift, "赠送金额");
        // 赠送比例保护（防配置错误导致超额赠送）
        BigDecimal maxGift = MoneyUtil.mul(principal, configService.giftRatioLimit());
        if (MoneyUtil.gt(gift, maxGift)) {
            throw new BizException(ErrorCode.GIFT_AMOUNT_EXCEED,
                    "赠送金额 " + gift + " 超过允许上限 " + maxGift + "（本金 × " + configService.giftRatioLimit() + "）");
        }
        // 余额上限保护
        BigDecimal maxBalance = configService.maxBalance();
        if (MoneyUtil.gt(maxBalance, BigDecimal.ZERO)) {
            MemberAccount acc = accountMapper.selectByMemberId(memberId);
            if (acc != null && MoneyUtil.gt(acc.available().add(principal).add(gift), maxBalance)) {
                throw new BizException(ErrorCode.BALANCE_EXCEED_LIMIT,
                        "充值后余额将超过上限 " + maxBalance + " 元");
            }
        }
        return change(AccountChangeCmd.builder()
                .memberId(memberId)
                .bizType(BIZ_RECHARGE)
                .bizNo(orderNo)
                .bizId(orderId)
                .idempotencyKey(orderNo)
                .balanceDelta(principal)
                .giftDelta(gift)
                .storeId(storeId)
                .operatorId(operatorId)
                .shiftNo(shiftNo)
                .remark(remark == null ? "充值" : remark)
                .build());
    }

    /** 消费扣减：按拆分好的本金/赠送扣减，并累计消费统计 */
    @Transactional(rollbackFor = Exception.class)
    public AccountChangeResult consume(Long memberId, BigDecimal principalPart, BigDecimal giftPart,
                                       String orderNo, Long orderId, Long storeId, Long operatorId,
                                       String shiftNo, String remark) {
        MoneyUtil.requireNonNegative(principalPart, "本金扣减额");
        MoneyUtil.requireNonNegative(giftPart, "赠送扣减额");
        BigDecimal total = MoneyUtil.add(principalPart, giftPart);
        if (!MoneyUtil.isPositive(total)) {
            // 全额券抵扣（实付 0）无需扣减余额，但仍要累计消费笔数
            return recordZeroConsume(memberId, orderNo, orderId, storeId, operatorId, shiftNo, remark);
        }
        return change(AccountChangeCmd.builder()
                .memberId(memberId)
                .bizType(BIZ_CONSUME)
                .bizNo(orderNo)
                .bizId(orderId)
                .idempotencyKey(orderNo + ":CONSUME")
                .balanceDelta(principalPart.negate())
                .giftDelta(giftPart.negate())
                .storeId(storeId)
                .operatorId(operatorId)
                .shiftNo(shiftNo)
                .remark(remark == null ? "消费" : remark)
                .build());
    }

    /**
     * 消费获得积分与成长值
     *
     * @param points 获得积分（可为 0）
     * @param growth 获得成长值（可为 0）
     */
    @Transactional(rollbackFor = Exception.class)
    public AccountChangeResult gainByConsume(Long memberId, long points, long growth, String orderNo, Long orderId,
                                             Long storeId, String remark) {
        if (points == 0 && growth == 0) {
            return AccountChangeResult.of(accountMapper.selectByMemberId(memberId));
        }
        return change(AccountChangeCmd.builder()
                .memberId(memberId)
                .bizType(PBIZ_CONSUME_GAIN)
                .bizNo(orderNo)
                .bizId(orderId)
                .idempotencyKey(orderNo + ":GAIN")
                .pointsDelta(points == 0 ? null : points)
                .growthDelta(growth == 0 ? null : growth)
                .storeId(storeId)
                .remark(remark == null ? "消费获得" : remark)
                .build());
    }

    /** 退款：原路退回余额（本金部分），并可扣回赠送 */
    @Transactional(rollbackFor = Exception.class)
    public AccountChangeResult refundBalance(Long memberId, BigDecimal principal, BigDecimal gift,
                                             String refundNo, String orderNo, Long orderId, Long storeId,
                                             Long operatorId, String remark) {
        MoneyUtil.requireNonNegative(principal, "退款本金");
        MoneyUtil.requireNonNegative(gift, "退款赠送");
        return change(AccountChangeCmd.builder()
                .memberId(memberId)
                .bizType(BIZ_REFUND)
                .bizNo(refundNo)
                .bizId(orderId)
                .idempotencyKey(refundNo)
                .balanceDelta(principal)
                .giftDelta(gift)
                .storeId(storeId)
                .operatorId(operatorId)
                .remark(remark == null ? ("退单 " + orderNo) : remark)
                .build());
    }

    /** 充值撤销：扣回本金与赠送（不可扣成负数） */
    @Transactional(rollbackFor = Exception.class)
    public AccountChangeResult revokeRecharge(Long memberId, BigDecimal principal, BigDecimal gift,
                                              String orderNo, Long orderId, Long storeId, Long operatorId,
                                              String remark) {
        MoneyUtil.requireNonNegative(principal, "撤销本金");
        MoneyUtil.requireNonNegative(gift, "撤销赠送");
        // 用 adjust 语义（允许扣到 0 不为负），bizType 记退款
        return change(AccountChangeCmd.builder()
                .memberId(memberId)
                .bizType(BIZ_REFUND)
                .bizNo(orderNo)
                .bizId(orderId)
                .idempotencyKey(orderNo + ":REVOKE")
                .balanceDelta(principal.negate())
                .giftDelta(gift.negate())
                .storeId(storeId)
                .operatorId(operatorId)
                .remark(remark == null ? "充值撤销" : remark)
                .build());
    }

    /** 手动调整（必填原因，权限与审计由上层保证） */
    @Transactional(rollbackFor = Exception.class)
    public AccountChangeResult adjust(Long memberId, BigDecimal balanceDelta, BigDecimal giftDelta,
                                      Long pointsDelta, Long growthDelta, String reason,
                                      Long operatorId, Long storeId) {
        return adjust(memberId, balanceDelta, giftDelta, pointsDelta, growthDelta, reason,
                operatorId, storeId, null);
    }

    /**
     * 手动调整（带客户端幂等键）
     *
     * <p>{@code idempotencyKey} 由客户端生成、重复提交时保持不变。
     * 它作为流水的幂等键落到 {@code uk_idem(idempotency_key, biz_type)} 上，
     * 因此即便缓存里的幂等占位已过期，重复请求也会在读侧被识别并返回原结果。
     *
     * <p>不传时退化为业务单号 {@code ADJ+时间戳} —— 这在语义上是「每次都算一次新调整」，
     * 无法防重放，仅用于内部脚本/补偿场景。**管理端必须传**。
     */
    @Transactional(rollbackFor = Exception.class)
    public AccountChangeResult adjust(Long memberId, BigDecimal balanceDelta, BigDecimal giftDelta,
                                      Long pointsDelta, Long growthDelta, String reason,
                                      Long operatorId, Long storeId, String idempotencyKey) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.ADJUST_REASON_REQUIRED);
        }
        if (balanceDelta == null && giftDelta == null && pointsDelta == null && growthDelta == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "至少需要一项调整内容");
        }
        String bizNo = noGenerator.custom("ADJ");
        String idemKey = idempotencyKey == null || idempotencyKey.isBlank() ? bizNo : idempotencyKey.trim();
        return change(AccountChangeCmd.builder()
                .memberId(memberId)
                // 余额调整记 BIZ_ADJUST；纯积分/成长值调整走各自业务类型
                .bizType(balanceDelta != null || giftDelta != null ? BIZ_ADJUST
                        : (pointsDelta != null ? PBIZ_ADJUST : GBIZ_ADJUST))
                .bizNo(bizNo)
                .idempotencyKey(idemKey)
                .balanceDelta(balanceDelta)
                .giftDelta(giftDelta)
                .pointsDelta(pointsDelta)
                .growthDelta(growthDelta)
                .operatorId(operatorId)
                .storeId(storeId)
                .remark(reason)
                .allowNegativePoints(pointsDelta != null && pointsDelta < 0)
                .build());
    }

    /** 活动赠送积分 */
    @Transactional(rollbackFor = Exception.class)
    public AccountChangeResult grantPoints(Long memberId, long points, Integer bizType, String bizNo,
                                           String remark, LocalDateTime expireTime) {
        if (points <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "赠送积分必须大于 0");
        }
        return change(AccountChangeCmd.builder()
                .memberId(memberId)
                .bizType(bizType == null ? PBIZ_RECHARGE_GIFT : bizType)
                .bizNo(bizNo)
                .idempotencyKey(bizNo)
                .pointsDelta(points)
                .pointsExpireTime(expireTime)
                .remark(remark)
                .build());
    }

    /** 退单扣回积分（允许账户积分为负） */
    @Transactional(rollbackFor = Exception.class)
    public AccountChangeResult deductPointsForRefund(Long memberId, long points, String bizNo, String remark) {
        if (points <= 0) {
            return AccountChangeResult.of(accountMapper.selectByMemberId(memberId));
        }
        return change(AccountChangeCmd.builder()
                .memberId(memberId)
                .bizType(PBIZ_REFUND_DEDUCT)
                .bizNo(bizNo)
                .idempotencyKey(bizNo + ":PDEDUCT")
                .pointsDelta(-points)
                .allowNegativePoints(true)
                .checkPoints(false)
                .remark(remark == null ? "退单扣回积分" : remark)
                .build());
    }

    /** 退单扣回成长值（不允许为负） */
    @Transactional(rollbackFor = Exception.class)
    public AccountChangeResult deductGrowthForRefund(Long memberId, long growth, String bizNo, String remark) {
        if (growth <= 0) {
            return AccountChangeResult.of(accountMapper.selectByMemberId(memberId));
        }
        return change(AccountChangeCmd.builder()
                .memberId(memberId)
                .bizType(GBIZ_REFUND_DEDUCT)
                .bizNo(bizNo)
                .idempotencyKey(bizNo + ":GDEDUCT")
                .growthDelta(-growth)
                .remark(remark == null ? "退单扣回成长值" : remark)
                .build());
    }

    /** 消费抵扣积分占用（写流水 + FIFO 扣批次），不产生余额变动 */
    @Transactional(rollbackFor = Exception.class)
    public AccountChangeResult deductPointsForConsume(Long memberId, long points, String orderNo) {
        if (points <= 0) {
            return AccountChangeResult.of(accountMapper.selectByMemberId(memberId));
        }
        return change(AccountChangeCmd.builder()
                .memberId(memberId)
                .bizType(PBIZ_EXCHANGE)
                .bizNo(orderNo)
                .idempotencyKey(orderNo + ":POINT_USE")
                .pointsDelta(-points)
                .checkPoints(true)
                .remark("消费积分抵扣")
                .build());
    }

    /**
     * 计算余额扣减的本金/赠送拆分（只读，不落库）
     *
     * <p>供收银结算在扣减前确定拆分比例（用于判断赠送抵扣部分不计积分），
     * 并在余额不足时提前抛错。
     */
    public SplitResult consumeSplit(Long memberId, BigDecimal amount) {
        MoneyUtil.requirePositive(amount, "扣减金额");
        MemberAccount acc = accountMapper.selectByMemberId(memberId);
        if (acc == null) {
            throw new BizException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
        BigDecimal gift = acc.getGiftBalance() == null ? BigDecimal.ZERO : acc.getGiftBalance();
        BigDecimal principal = acc.getBalance() == null ? BigDecimal.ZERO : acc.getBalance();

        BigDecimal fromGift;
        BigDecimal fromPrincipal;
        if (giftFirst()) {
            fromGift = MoneyUtil.min(gift, amount);
            fromPrincipal = MoneyUtil.sub(amount, fromGift);
        } else {
            fromPrincipal = MoneyUtil.min(principal, amount);
            fromGift = MoneyUtil.sub(amount, fromPrincipal);
        }
        if (MoneyUtil.gt(fromPrincipal, principal) || MoneyUtil.gt(fromGift, gift)) {
            BigDecimal shortfall = MoneyUtil.sub(amount, MoneyUtil.add(principal, gift));
            throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH,
                    "余额不足，还差 " + shortfall + " 元（本金 " + principal + " + 赠送 " + gift + "）");
        }
        return new SplitResult(MoneyUtil.scale(fromPrincipal), MoneyUtil.scale(fromGift));
    }

    private boolean giftFirst() {
        return configService.giftFirst();
    }

    /** 余额拆分结果 */
    public record SplitResult(BigDecimal principal, BigDecimal gift) {
    }

    /* ==================================================================
     *  查询
     * ================================================================== */

    public MemberAccount getAccount(Long memberId) {
        MemberAccount acc = accountMapper.selectByMemberId(memberId);
        if (acc == null) {
            return initAccount(memberId);
        }
        return acc;
    }

    /** 初始化账户（会员建档时调用，幂等） */
    @Transactional(rollbackFor = Exception.class)
    public MemberAccount initAccount(Long memberId) {
        MemberAccount existing = accountMapper.selectByMemberId(memberId);
        if (existing != null) {
            return existing;
        }
        MemberAccount acc = new MemberAccount();
        acc.setMemberId(memberId);
        acc.setBalance(BigDecimal.ZERO);
        acc.setGiftBalance(BigDecimal.ZERO);
        acc.setFrozenAmount(BigDecimal.ZERO);
        acc.setPoints(0L);
        acc.setGrowth(0L);
        acc.setTotalRecharge(BigDecimal.ZERO);
        acc.setTotalGift(BigDecimal.ZERO);
        acc.setTotalConsume(BigDecimal.ZERO);
        acc.setTotalConsumeCount(0);
        acc.setVersion(0);
        try {
            accountMapper.insert(acc);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发建档，返回已存在账户
            return accountMapper.selectByMemberId(memberId);
        }
        log.info("[资产] 初始化账户 memberId={}", memberId);
        return acc;
    }

    /* ==================================================================
     *  内部实现
     * ================================================================== */

    private void validate(AccountChangeCmd cmd) {
        if (cmd.getMemberId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "memberId 不能为空");
        }
        if (cmd.getBizType() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "bizType 不能为空");
        }
        // 余额变动额按「大小」校验（扣减场景传入的是负值，合法）
        MoneyUtil.requireNonNegative(cmd.balanceDeltaOrZero().abs(), "本金变动额");
        MoneyUtil.requireNonNegative(cmd.giftDeltaOrZero().abs(), "赠送变动额");
        // 余额变动必须有幂等键（资金安全要求）
        if (cmd.hasBalanceChange() && (cmd.getIdempotencyKey() == null || cmd.getIdempotencyKey().isBlank())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "余额变动必须提供幂等键");
        }
    }

    /** 余额变动：分本金与赠送两条流水 */
    private void applyBalance(MemberAccount acc, AccountChangeCmd cmd) {
        BigDecimal principalDelta = cmd.balanceDeltaOrZero();
        BigDecimal giftDelta = cmd.giftDeltaOrZero();
        BigDecimal beforePrincipal = acc.getBalance();
        BigDecimal beforeGift = acc.getGiftBalance();

        BigDecimal afterPrincipal = beforePrincipal.add(principalDelta);
        BigDecimal afterGift = beforeGift.add(giftDelta);

        if (afterPrincipal.signum() < 0) {
            throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH,
                    "余额不足：本金余额 " + beforePrincipal + "，需扣减 " + principalDelta.negate());
        }
        if (afterGift.signum() < 0) {
            throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH,
                    "赠送余额不足：赠送余额 " + beforeGift + "，需扣减 " + giftDelta.negate());
        }

        boolean isDeduct = principalDelta.signum() < 0 || giftDelta.signum() < 0;
        boolean isRecharge = cmd.getBizType() != null && cmd.getBizType() == BIZ_RECHARGE;

        int rows;
        if (isRecharge) {
            rows = accountMapper.recharge(acc.getId(), principalDelta, giftDelta, acc.getVersion());
        } else if (isDeduct) {
            BigDecimal deductPrincipal = principalDelta.signum() < 0 ? principalDelta.negate() : BigDecimal.ZERO;
            BigDecimal deductGift = giftDelta.signum() < 0 ? giftDelta.negate() : BigDecimal.ZERO;
            rows = accountMapper.deduct(acc.getId(), deductPrincipal, deductGift, acc.getVersion());
        } else {
            rows = accountMapper.adjustBalance(acc.getId(), principalDelta, giftDelta, acc.getVersion());
        }
        if (rows == 0) {
            throw new BizException(ErrorCode.CONCURRENT_MODIFY);
        }

        // 两条流水（本金 / 赠送），before/after 为各自账户的快照
        if (principalDelta.signum() != 0) {
            insertBalanceFlow(acc.getMemberId(), ACC_PRINCIPAL, cmd, principalDelta, beforePrincipal, afterPrincipal);
        }
        if (giftDelta.signum() != 0) {
            insertBalanceFlow(acc.getMemberId(), ACC_GIFT, cmd, giftDelta, beforeGift, afterGift);
        }

        acc.setBalance(MoneyUtil.scale(afterPrincipal));
        acc.setGiftBalance(MoneyUtil.scale(afterGift));
        acc.setVersion(acc.getVersion() + 1);
    }

    private void insertBalanceFlow(Long memberId, int accountType, AccountChangeCmd cmd, BigDecimal change,
                                   BigDecimal before, BigDecimal after) {
        BalanceFlow flow = new BalanceFlow();
        flow.setFlowNo(noGenerator.balanceFlowNo());
        flow.setMemberId(memberId);
        flow.setAccountType(accountType);
        flow.setBizType(cmd.getBizType());
        flow.setChangeAmount(MoneyUtil.scale(change));
        flow.setBeforeAmount(MoneyUtil.scale(before));
        flow.setAfterAmount(MoneyUtil.scale(after));
        flow.setBizNo(cmd.getBizNo());
        flow.setBizId(cmd.getBizId());
        flow.setStoreId(cmd.getStoreId());
        flow.setOperatorId(cmd.getOperatorId());
        flow.setShiftNo(cmd.getShiftNo());
        // 关键：同一笔业务可能同时产生「本金」与「赠送」两条流水，二者 biz_type 相同，
        // 而 uk_idem 为 (idempotency_key, biz_type)，因此幂等键必须按账户类型区分，
        // 否则第二条插入会触发唯一键冲突（曾导致"仅在有赠送金额时充值失败"的隐蔽缺陷）。
        String baseKey = cmd.getIdempotencyKey();
        flow.setIdempotencyKey(baseKey == null ? null
                : baseKey + (accountType == ACC_GIFT ? ":G" : ":P"));
        flow.setRemark(cmd.getRemark());
        flow.setOccurTime(cmd.occurTimeOrNow());
        balanceFlowMapper.insert(flow);
    }

    /** 积分变动：账户 + 流水 + 批次（FIFO） */
    private Long applyPoints(MemberAccount acc, AccountChangeCmd cmd) {
        long delta = cmd.pointsDeltaOrZero();
        long before = acc.getPoints() == null ? 0L : acc.getPoints();
        long after = before + delta;

        if (after < 0 && !cmd.isAllowNegativePoints()) {
            throw new BizException(ErrorCode.POINTS_NOT_ENOUGH,
                    "积分不足：当前 " + before + "，需扣减 " + Math.abs(delta));
        }
        if (!cmd.isCheckPoints() && after < 0 && !cmd.isAllowNegativePoints()) {
            throw new BizException(ErrorCode.POINTS_NOT_ENOUGH);
        }

        int rows = accountMapper.changePoints(acc.getId(), delta, acc.getVersion());
        if (rows == 0) {
            throw new BizException(ErrorCode.CONCURRENT_MODIFY);
        }

        Long batchId = null;
        LocalDateTime expireTime = null;
        if (delta > 0) {
            // 获得积分：生成批次（FIFO 过期依据）
            expireTime = cmd.getPointsExpireTime() != null ? cmd.getPointsExpireTime() : calcPointsExpireTime();
            PointBatch batch = new PointBatch();
            batch.setMemberId(acc.getMemberId());
            batch.setBizType(cmd.getBizType());
            batch.setTotalPoints(delta);
            batch.setUsedPoints(0L);
            batch.setRemainPoints(delta);
            batch.setExpireTime(expireTime);
            batch.setStatus(1);
            pointBatchMapper.insert(batch);
            batchId = batch.getId();
        } else if (delta < 0) {
            // 扣减积分：FIFO 消耗批次
            consumePointBatches(acc.getMemberId(), Math.abs(delta));
        }

        PointFlow flow = new PointFlow();
        flow.setFlowNo(noGenerator.pointFlowNo());
        flow.setMemberId(acc.getMemberId());
        flow.setBizType(cmd.getBizType());
        flow.setChangePoints(delta);
        flow.setBeforePoints(before);
        flow.setAfterPoints(after);
        flow.setBizNo(cmd.getBizNo());
        flow.setBizId(cmd.getBizId());
        flow.setStoreId(cmd.getStoreId());
        flow.setOperatorId(cmd.getOperatorId());
        flow.setExpireTime(expireTime);
        flow.setBatchId(batchId);
        flow.setIdempotencyKey(cmd.getIdempotencyKey());
        flow.setRemark(cmd.getRemark());
        flow.setOccurTime(cmd.occurTimeOrNow());
        pointFlowMapper.insert(flow);

        // 回填批次的来源流水号（便于溯源）
        if (batchId != null) {
            PointBatch b = new PointBatch();
            b.setId(batchId);
            b.setSourceFlowNo(flow.getFlowNo());
            pointBatchMapper.updateById(b);
        }

        acc.setPoints(after);
        acc.setVersion(acc.getVersion() + 1);
        return batchId;
    }

    /** FIFO 扣减批次：按到期时间从早到晚 */
    private void consumePointBatches(Long memberId, long points) {
        List<PointBatch> batches = pointBatchMapper.selectUsableForUpdate(memberId);
        long remain = points;
        for (PointBatch batch : batches) {
            if (remain <= 0) {
                break;
            }
            long use = Math.min(batch.getRemainPoints(), remain);
            if (use <= 0) {
                continue;
            }
            int rows = pointBatchMapper.consume(batch.getId(), use);
            if (rows > 0) {
                remain -= use;
            }
        }
        if (remain > 0) {
            // 批次余量不足以覆盖扣减（历史数据异常），记录告警但不阻断业务
            log.warn("[资产] 积分批次余量不足：memberId={}, 需扣减={}, 缺口={}", memberId, points, remain);
        }
    }

    private void applyGrowth(MemberAccount acc, AccountChangeCmd cmd) {
        long delta = cmd.growthDeltaOrZero();
        long before = acc.getGrowth() == null ? 0L : acc.getGrowth();
        long after = before + delta;
        if (after < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "成长值不能为负");
        }
        int rows = accountMapper.changeGrowth(acc.getId(), delta, acc.getVersion());
        if (rows == 0) {
            throw new BizException(ErrorCode.CONCURRENT_MODIFY);
        }
        GrowthFlow flow = new GrowthFlow();
        flow.setFlowNo(noGenerator.growthFlowNo());
        flow.setMemberId(acc.getMemberId());
        flow.setBizType(cmd.getBizType());
        flow.setChangeGrowth(delta);
        flow.setBeforeGrowth(before);
        flow.setAfterGrowth(after);
        flow.setBizNo(cmd.getBizNo());
        // 成长值没有本金/赠送之分，所以幂等键直接用原值（余额流水才需要 :P/:G 后缀）
        flow.setIdempotencyKey(cmd.getIdempotencyKey());
        flow.setOperatorId(cmd.getOperatorId());
        flow.setRemark(cmd.getRemark());
        flow.setOccurTime(cmd.occurTimeOrNow());
        growthFlowMapper.insert(flow);

        acc.setGrowth(after);
        acc.setVersion(acc.getVersion() + 1);
    }

    /** 实付 0 元的消费（全额券/积分抵扣）仍需累计消费笔数 */
    private AccountChangeResult recordZeroConsume(Long memberId, String orderNo, Long orderId, Long storeId,
                                                  Long operatorId, String shiftNo, String remark) {
        MemberAccount acc = accountMapper.selectForUpdate(memberId);
        if (acc == null) {
            acc = initAccount(memberId);
        }
        BalanceFlow flow = new BalanceFlow();
        flow.setFlowNo(noGenerator.balanceFlowNo());
        flow.setMemberId(memberId);
        flow.setAccountType(ACC_PRINCIPAL);
        flow.setBizType(BIZ_CONSUME);
        flow.setChangeAmount(BigDecimal.ZERO);
        flow.setBeforeAmount(acc.getBalance());
        flow.setAfterAmount(acc.getBalance());
        flow.setBizNo(orderNo);
        flow.setBizId(orderId);
        flow.setStoreId(storeId);
        flow.setOperatorId(operatorId);
        flow.setShiftNo(shiftNo);
        flow.setIdempotencyKey(orderNo + ":CONSUME");
        flow.setRemark((remark == null ? "消费" : remark) + "（券/积分全额抵扣，实付 0）");
        flow.setOccurTime(LocalDateTime.now());
        balanceFlowMapper.insert(flow);
        return AccountChangeResult.of(acc);
    }

    /** 积分过期时间：按系统配置的月数计算；0 表示永久 */
    public LocalDateTime calcPointsExpireTime() {
        int months = configService.pointsExpireMonths();
        if (months <= 0) {
            return null;
        }
        return LocalDateTime.now().plusMonths(months);
    }

    private AccountChangeResult idempotentResult(AccountChangeCmd cmd) {
        MemberAccount acc = accountMapper.selectByMemberId(cmd.getMemberId());
        AccountChangeResult r = acc == null ? new AccountChangeResult() : AccountChangeResult.of(acc);
        r.setIdempotentHit(true);
        return r;
    }
}
