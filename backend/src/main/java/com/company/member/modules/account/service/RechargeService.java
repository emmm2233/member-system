package com.company.member.modules.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.util.BizNoGenerator;
import com.company.member.common.util.MoneyUtil;
import com.company.member.modules.account.dto.RechargeCmd;
import com.company.member.modules.account.dto.RechargeResult;
import com.company.member.modules.account.entity.MemberAccount;
import com.company.member.modules.account.entity.RechargePlan;
import com.company.member.modules.account.mapper.BalanceFlowMapper;
import com.company.member.modules.account.mapper.RechargePlanMapper;
import com.company.member.modules.account.vo.AccountChangeResult;
import com.company.member.modules.cashier.entity.Order;
import com.company.member.modules.cashier.entity.OrderPayment;
import com.company.member.modules.cashier.mapper.OrderMapper;
import com.company.member.modules.cashier.mapper.OrderPaymentMapper;
import com.company.member.modules.marketing.entity.MemberCoupon;
import com.company.member.modules.marketing.service.CouponService;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.mapper.MemberMapper;
import com.company.member.modules.system.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 充值服务
 *
 * <p>收银员手工充值与在线支付成功入账**共用本服务**，保证两条链路账务完全一致。
 * 差异仅在于：在线支付多一张支付单（paymentNo），由支付模块处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeService {

    private final MemberMapper memberMapper;
    private final AccountService accountService;
    private final RechargePlanMapper planMapper;
    private final BalanceFlowMapper balanceFlowMapper;
    private final OrderMapper orderMapper;
    private final OrderPaymentMapper orderPaymentMapper;
    private final CouponService couponService;
    private final BizNoGenerator noGenerator;
    private final ConfigService configService;

    /**
     * 创建**待支付**充值单（小程序在线充值第一步）
     *
     * <p>此时不产生任何资产变动；资产入账发生在支付成功回调
     * （见 {@link #rechargeExistingOrder}）。下单时把方案赠送/送分固化到订单，
     * 保证回调入账时赠送规则与下单时一致（避免下单后被改方案）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createPendingRechargeOrder(Long memberId, Long planId, BigDecimal customAmount,
                                                          Long storeId, String idempotencyKey) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BizException(ErrorCode.MEMBER_NOT_FOUND);
        }
        if (member.isCancelled()) {
            throw new BizException(ErrorCode.MEMBER_CANCELLED);
        }
        if (member.isFrozen()) {
            throw new BizException(ErrorCode.MEMBER_FROZEN);
        }

        BigDecimal amount;
        BigDecimal giftAmount = BigDecimal.ZERO;
        long giftPoints = 0L;
        RechargePlan plan = null;

        if (planId != null) {
            plan = planMapper.selectById(planId);
            if (plan == null || (plan.getStatus() != null && plan.getStatus() == 0)) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND, "充值方案不存在或已停用");
            }
            if (plan.getValidEnd() != null && plan.getValidEnd().isBefore(LocalDateTime.now())) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND, "该充值方案已结束");
            }
            amount = MoneyUtil.scale(plan.getRechargeAmount());
            giftAmount = MoneyUtil.nullToZero(plan.getGiftAmount());
            giftPoints = plan.getGiftPoints() == null ? 0L : plan.getGiftPoints();
        } else {
            if (customAmount == null || customAmount.signum() <= 0) {
                throw new BizException(ErrorCode.AMOUNT_INVALID, "请选择充值方案或输入充值金额");
            }
            amount = MoneyUtil.scale(customAmount);
        }

        // 复用未支付的同额充值单，避免重复下单
        Order existed = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getMemberId, memberId)
                .eq(Order::getOrderType, Order.TYPE_RECHARGE)
                .eq(Order::getTotalAmount, amount)
                .in(Order::getStatus, Order.STATUS_UNPAID, Order.STATUS_PAYING)
                .orderByDesc(Order::getId).last("LIMIT 1"));
        if (existed != null) {
            log.info("[充值] 复用待支付充值单 orderNo={}", existed.getOrderNo());
            return pendingOrderView(existed, plan);
        }

        String orderNo = noGenerator.generateWithRetry(noGenerator::rechargeOrderNo,
                no -> orderMapper.selectByOrderNo(no) != null
                        || balanceFlowMapper.countByIdempotency(no, AccountService.BIZ_RECHARGE) > 0);

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setOrderType(Order.TYPE_RECHARGE);
        order.setMemberId(memberId);
        order.setStoreId(storeId != null ? storeId : member.getStoreId());
        order.setTotalAmount(amount);
        order.setPayableAmount(amount);
        order.setPaidAmount(BigDecimal.ZERO);
        order.setGiftAmount(MoneyUtil.scale(giftAmount));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setCouponAmount(BigDecimal.ZERO);
        order.setPointDeductAmount(BigDecimal.ZERO);
        order.setPointDeductPoints(0L);
        order.setPointsGained(giftPoints);
        order.setGrowthGained(0L);
        order.setRefundAmount(BigDecimal.ZERO);
        order.setStatus(Order.STATUS_UNPAID);
        order.setIdempotencyKey(idempotencyKey == null || idempotencyKey.isBlank() ? orderNo : idempotencyKey);
        String remark = plan == null ? "在线充值（自定义金额）" : "在线充值：" + plan.getPlanName();
        // 赠券配置随订单固化（回调入账时无需再查方案，避免方案被改动导致赠券不一致）
        if (plan != null && plan.getGiftCouponTplIds() != null && !plan.getGiftCouponTplIds().isBlank()) {
            remark = remark + " [GIFT_COUPONS:" + plan.getGiftCouponTplIds() + "]";
        }
        order.setRemark(remark);
        order.setExpireTime(LocalDateTime.now().plusMinutes(15));
        order.setTenantId(0L);
        orderMapper.insert(order);

        log.info("[充值] 创建待支付充值单 orderNo={}, memberId={}, 金额={}, 赠送={}, 送分={}",
                orderNo, memberId, amount, giftAmount, giftPoints);

        Map<String, Object> result = pendingOrderView(order, plan);
        if (plan != null && plan.getGiftCouponTplIds() != null && !plan.getGiftCouponTplIds().isBlank()) {
            result.put("giftCouponTplIds", plan.getGiftCouponTplIds());
        }
        return result;
    }

    private Map<String, Object> pendingOrderView(Order order, RechargePlan plan) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("orderNo", order.getOrderNo());
        m.put("rechargeAmount", order.getTotalAmount());
        m.put("giftAmount", order.getGiftAmount());
        m.put("giftPoints", order.getPointsGained());
        m.put("planId", plan == null ? null : plan.getId());
        m.put("planName", plan == null ? null : plan.getPlanName());
        m.put("status", order.getStatus());
        m.put("statusText", Order.statusText(order.getStatus()));
        m.put("expireTime", order.getExpireTime() == null ? null
                : order.getExpireTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return m;
    }

    /**
     * 对**已存在的充值单**执行入账（在线支付回调专用）
     *
     * <p>与 {@link #recharge} 共用同一套资产入账逻辑，差异仅在于订单已由下单阶段创建。
     * 幂等键使用支付单号，回调重放不会重复入账。
     */
    @Transactional(rollbackFor = Exception.class)
    public RechargeResult rechargeExistingOrder(Order order, RechargeCmd cmd, String paymentNo, int payMethod) {
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        BigDecimal principal = order.getTotalAmount();
        BigDecimal gift = MoneyUtil.nullToZero(order.getGiftAmount());
        long giftPoints = order.getPointsGained() == null ? 0L : order.getPointsGained();

        // 1. 资产入账（幂等键 = 支付单号）
        AccountChangeResult accResult = accountService.recharge(cmd.getMemberId(), principal, gift,
                order.getOrderNo(), order.getId(), order.getStoreId(), null, order.getShiftNo(),
                "在线充值入账");

        // 2. 补写支付明细（在线支付成功后的资金明细，供账单展示与交班对账使用）
        OrderPayment payment = new OrderPayment();
        payment.setOrderId(order.getId());
        payment.setOrderNo(order.getOrderNo());
        payment.setPayMethod(payMethod);
        payment.setAmount(MoneyUtil.scale(principal));
        payment.setPaymentNo(paymentNo);
        payment.setTradeNo(paymentNo);
        payment.setTradeStatus("SUCCESS");
        payment.setPayTime(LocalDateTime.now());
        payment.setIdempotencyKey(order.getOrderNo() + ":" + payMethod);
        orderPaymentMapper.insert(payment);

        // 3. 赠送积分
        if (giftPoints > 0) {
            accountService.grantPoints(cmd.getMemberId(), giftPoints,
                    AccountService.PBIZ_RECHARGE_GIFT, order.getOrderNo() + ":GIFT_POINT",
                    "充值赠送积分", accountService.calcPointsExpireTime());
        }

        // 4. 赠券（下单时已把配置固化在订单备注中，此处解析并发放）
        List<RechargeResult.GrantedCoupon> granted = new ArrayList<>();
        String couponConfig = extractGiftCouponConfig(order.getRemark());
        if (couponConfig != null) {
            for (RechargeCmd.GiftCoupon gc : parsePlanCoupons(couponConfig)) {
                if (gc.getTplId() == null) {
                    continue;
                }
                int qty = gc.getQty() == null || gc.getQty() < 1 ? 1 : gc.getQty();
                for (MemberCoupon c : couponService.grantForce(cmd.getMemberId(), gc.getTplId(), qty,
                        "RECHARGE", order.getOrderNo())) {
                    RechargeResult.GrantedCoupon item = new RechargeResult.GrantedCoupon();
                    item.setCouponNo(c.getCouponNo());
                    item.setTplId(c.getTplId());
                    item.setTplName(c.getCouponName());
                    granted.add(item);
                }
            }
        }

        RechargeResult result = new RechargeResult();
        result.setOrderNo(order.getOrderNo());
        result.setRechargeAmount(MoneyUtil.scale(principal));
        result.setGiftAmount(MoneyUtil.scale(gift));
        result.setGiftPoints(giftPoints);
        result.setAccount(accResult);
        result.setGrantedCoupons(granted);
        result.setIdempotentHit(accResult.isIdempotentHit());
        MemberAccount latest = accountService.getAccount(cmd.getMemberId());
        result.setPointsAfter(latest.getPoints());
        result.setBalanceAfter(latest.getBalance());
        result.setGiftBalanceAfter(latest.getGiftBalance());
        log.info("[充值] 在线支付入账 orderNo={}, paymentNo={}, 金额={}, 送券={}, 幂等命中={}",
                order.getOrderNo(), paymentNo, principal, granted.size(), accResult.isIdempotentHit());
        return result;
    }

    /** 从订单备注提取赠券配置：{@code ... [GIFT_COUPONS:2:1,1:1]} */
    private static String extractGiftCouponConfig(String remark) {
        if (remark == null) {
            return null;
        }
        int start = remark.indexOf("[GIFT_COUPONS:");
        if (start < 0) {
            return null;
        }
        int end = remark.indexOf(']', start);
        if (end < 0) {
            return null;
        }
        return remark.substring(start + "[GIFT_COUPONS:".length(), end);
    }

    /**
     * 充值（现金 / 线下扫码 / 在线支付回调）
     *
     * @param cmd         充值参数
     * @param operatorId  操作人（在线支付回调时可为 null）
     * @param paymentNo   在线支付单号（预留，手工充值为 null）
     * @param payMethod   支付方式；为空时取 cmd.payMethod
     */
    @Transactional(rollbackFor = Exception.class)
    public RechargeResult recharge(RechargeCmd cmd, Long operatorId, String paymentNo, Integer payMethod) {
        Member member = memberMapper.selectById(cmd.getMemberId());
        if (member == null) {
            throw new BizException(ErrorCode.MEMBER_NOT_FOUND);
        }
        if (member.isCancelled()) {
            throw new BizException(ErrorCode.MEMBER_CANCELLED);
        }
        if (member.isFrozen()) {
            throw new BizException(ErrorCode.MEMBER_FROZEN);
        }
        MoneyUtil.requirePositive(cmd.getRechargeAmount(), "充值金额");

        // 1. 解析方案（赠送/送分/送券/升级）
        BigDecimal giftAmount = MoneyUtil.nullToZero(cmd.getGiftAmount());
        long giftPoints = cmd.getGiftPoints() == null ? 0L : cmd.getGiftPoints();
        List<RechargeCmd.GiftCoupon> giftCoupons = cmd.getGiftCoupons() == null ? List.of() : cmd.getGiftCoupons();
        RechargePlan plan = null;
        if (cmd.getPlanId() != null) {
            plan = planMapper.selectById(cmd.getPlanId());
            if (plan == null || (plan.getStatus() != null && plan.getStatus() == 0)) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND, "充值方案不存在或已停用");
            }
            // 金额与方案必须一致（防止前端篡改赠送）
            if (MoneyUtil.gt(plan.getRechargeAmount(), BigDecimal.ZERO)
                    && !MoneyUtil.equalsAmount(plan.getRechargeAmount(), cmd.getRechargeAmount())) {
                // 允许自定义金额充值，此时不套用方案的赠送
                log.info("[充值] 自定义金额 {} 与方案 {} 不一致，忽略方案赠送",
                        cmd.getRechargeAmount(), plan.getRechargeAmount());
                plan = null;
            } else {
                giftAmount = MoneyUtil.nullToZero(plan.getGiftAmount());
                giftPoints = plan.getGiftPoints() == null ? 0L : plan.getGiftPoints();
                if (plan.getGiftCouponTplIds() != null && !plan.getGiftCouponTplIds().isBlank()) {
                    giftCoupons = parsePlanCoupons(plan.getGiftCouponTplIds());
                }
            }
        }

        // 2. 大额充值校验（阈值触发）
        BigDecimal threshold = configService.largeRechargeThreshold();
        boolean needApprove = MoneyUtil.gt(threshold, BigDecimal.ZERO)
                && MoneyUtil.gte(cmd.getRechargeAmount(), threshold);
        if (needApprove && operatorId != null) {
            log.info("[充值] 大额充值 {} 元（阈值 {}），已记录操作日志待审",
                    cmd.getRechargeAmount(), threshold);
        }

        // 3. 生成充值单
        //    注意：单号的唯一性同时体现在 m_order.order_no 与 m_balance_flow.idempotency_key
        //    （充值流水以充值单号作为幂等键，并带 biz_type 组成联合唯一索引），
        //    因此两处都要检查，否则会出现"订单号没重复但流水幂等键重复"的冲突。
        String orderNo = noGenerator.generateWithRetry(noGenerator::rechargeOrderNo,
                no -> orderMapper.selectByOrderNo(no) != null
                        || balanceFlowMapper.countByIdempotency(no, AccountService.BIZ_RECHARGE) > 0);
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setOrderType(Order.TYPE_RECHARGE);
        order.setMemberId(cmd.getMemberId());
        order.setStoreId(member.getStoreId());
        order.setCashierId(operatorId);
        order.setTotalAmount(MoneyUtil.scale(cmd.getRechargeAmount()));
        order.setPayableAmount(MoneyUtil.scale(cmd.getRechargeAmount()));
        order.setPaidAmount(MoneyUtil.scale(cmd.getRechargeAmount()));
        order.setGiftAmount(MoneyUtil.scale(giftAmount));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setCouponAmount(BigDecimal.ZERO);
        order.setPointDeductAmount(BigDecimal.ZERO);
        order.setPointDeductPoints(0L);
        order.setPointsGained(giftPoints);
        order.setGrowthGained(0L);
        order.setRefundAmount(BigDecimal.ZERO);
        order.setStatus(Order.STATUS_PAID);
        order.setShiftNo(cmd.getShiftNo());
        order.setPaymentNo(paymentNo);
        // 注意：m_order.idempotency_key 有唯一索引，未传幂等键时必须用订单号占位，
        // 否则多笔「无幂等键」订单都会写入 NULL 之外的相同值而触发唯一约束冲突。
        order.setIdempotencyKey(cmd.getIdempotencyKey() == null || cmd.getIdempotencyKey().isBlank()
                ? orderNo : cmd.getIdempotencyKey());
        order.setRemark(cmd.getRemark());
        order.setPaidAt(java.time.LocalDateTime.now());
        order.setTenantId(0L);
        orderMapper.insert(order);

        // 4. 支付明细
        int method = payMethod != null ? payMethod
                : (paymentNo != null ? OrderPayment.METHOD_WECHAT_ONLINE
                : (cmd.getPayMethod() == null ? OrderPayment.METHOD_CASH : cmd.getPayMethod()));
        OrderPayment payment = new OrderPayment();
        payment.setOrderId(order.getId());
        payment.setOrderNo(orderNo);
        payment.setPayMethod(method);
        payment.setAmount(MoneyUtil.scale(cmd.getRechargeAmount()));
        payment.setOutTradeNo(cmd.getOutTradeNo());
        payment.setPaymentNo(paymentNo);
        payment.setTradeStatus("SUCCESS");
        payment.setPayTime(java.time.LocalDateTime.now());
        orderPaymentMapper.insert(payment);

        // 5. 资产入账（统一入口，幂等键 = 订单号）
        AccountChangeResult accResult = accountService.recharge(cmd.getMemberId(),
                cmd.getRechargeAmount(), giftAmount, orderNo, order.getId(),
                member.getStoreId(), operatorId, cmd.getShiftNo(),
                cmd.getRemark() == null ? "充值" : cmd.getRemark());

        // 6. 赠送积分
        if (giftPoints > 0) {
            accountService.grantPoints(cmd.getMemberId(), giftPoints,
                    AccountService.PBIZ_RECHARGE_GIFT, orderNo + ":GIFT_POINT",
                    "充值赠送积分", accountService.calcPointsExpireTime());
        }

        // 7. 赠券（系统发放，不校验每人限领）
        List<RechargeResult.GrantedCoupon> granted = new ArrayList<>();
        for (RechargeCmd.GiftCoupon gc : giftCoupons) {
            if (gc.getTplId() == null) {
                continue;
            }
            int qty = gc.getQty() == null || gc.getQty() < 1 ? 1 : gc.getQty();
            List<MemberCoupon> coupons = couponService.grantForce(cmd.getMemberId(), gc.getTplId(), qty,
                    "RECHARGE", orderNo);
            for (MemberCoupon c : coupons) {
                RechargeResult.GrantedCoupon item = new RechargeResult.GrantedCoupon();
                item.setCouponNo(c.getCouponNo());
                item.setTplId(c.getTplId());
                item.setTplName(c.getCouponName());
                item.setValidEnd(c.getValidEnd() == null ? null
                        : c.getValidEnd().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                granted.add(item);
            }
        }

        RechargeResult result = new RechargeResult();
        result.setOrderNo(orderNo);
        result.setRechargeAmount(MoneyUtil.scale(cmd.getRechargeAmount()));
        result.setGiftAmount(MoneyUtil.scale(giftAmount));
        result.setGiftPoints(giftPoints);
        result.setAccount(accResult);
        result.setGrantedCoupons(granted);
        result.setIdempotentHit(accResult.isIdempotentHit());
        // 赠送积分与赠券是在资产入账之后追加的，账户快照需重新读取，否则返回的积分余额会漏掉赠送积分
        MemberAccount latest = accountService.getAccount(cmd.getMemberId());
        result.setPointsAfter(latest.getPoints());
        result.setBalanceAfter(latest.getBalance());
        result.setGiftBalanceAfter(latest.getGiftBalance());

        log.info("[充值] 成功 orderNo={}, memberId={}, 本金={}, 赠送={}, 送分={}, 送券={}",
                orderNo, cmd.getMemberId(), cmd.getRechargeAmount(), giftAmount, giftPoints, granted.size());
        return result;
    }

    /**
     * 充值撤销（当日、无消费）
     *
     * <p>扣回本金 + 赠送 + 赠分，作废未使用的赠券。
     */
    @Transactional(rollbackFor = Exception.class)
    public void revoke(String orderNo, Long operatorId, String reason) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (order.getOrderType() == null || order.getOrderType() != Order.TYPE_RECHARGE) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID, "仅充值单可撤销");
        }
        if (!order.isPaid()) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID, "该充值单状态不允许撤销");
        }
        // 校验：自充值后必须有足够余额（无消费）
        MemberAccount acc = accountService.getAccount(order.getMemberId());
        BigDecimal principal = order.getTotalAmount();
        BigDecimal gift = MoneyUtil.nullToZero(order.getGiftAmount());
        if (MoneyUtil.lt(acc.getBalance(), principal)) {
            throw new BizException(ErrorCode.RECHARGE_REVOKE_NOT_ALLOWED,
                    "会员本金余额 " + acc.getBalance() + " 元不足以扣回 " + principal + " 元，可能已产生消费");
        }

        accountService.revokeRecharge(order.getMemberId(), principal, gift, orderNo, order.getId(),
                order.getStoreId(), operatorId, reason == null ? "充值撤销" : reason);

        // 扣回赠分（若已使用导致不足，置负由后续补回）
        if (order.getPointsGained() != null && order.getPointsGained() > 0) {
            accountService.deductPointsForRefund(order.getMemberId(), order.getPointsGained(),
                    orderNo + ":REVOKE_POINT", "充值撤销扣回积分");
        }

        // 作废本次赠送的券（未被使用的）
        List<MemberCoupon> granted = couponService.listBySourceBizNo(orderNo);
        for (MemberCoupon c : granted) {
            if (c.getStatus() != null && c.getStatus() == MemberCoupon.STATUS_UNUSED) {
                couponService.voidCoupon(c.getId(), "充值撤销，赠券作废");
            }
        }

        orderMapper.updateStatusIfPending(order.getId(), Order.STATUS_CANCELLED);
        log.warn("[充值] 撤销 orderNo={}, 本金={}, 赠送={}, 原因={}", orderNo, principal, gift, reason);
    }

    /** 解析方案中的赠券配置：tplId:qty,tplId:qty */
    private List<RechargeCmd.GiftCoupon> parsePlanCoupons(String config) {
        List<RechargeCmd.GiftCoupon> list = new ArrayList<>();
        for (String part : config.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            String[] kv = s.split(":");
            RechargeCmd.GiftCoupon gc = new RechargeCmd.GiftCoupon();
            try {
                gc.setTplId(Long.valueOf(kv[0].trim()));
                gc.setQty(kv.length > 1 ? Integer.valueOf(kv[1].trim()) : 1);
            } catch (NumberFormatException e) {
                log.warn("[充值] 方案赠券配置格式错误，已跳过：{}", s);
                continue;
            }
            list.add(gc);
        }
        return list;
    }
}
