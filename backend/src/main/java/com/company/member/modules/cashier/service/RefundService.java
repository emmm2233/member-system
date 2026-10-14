package com.company.member.modules.cashier.service;

import com.company.member.common.annotation.Idempotent;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.util.BizNoGenerator;
import com.company.member.common.util.MoneyUtil;
import com.company.member.modules.account.entity.MemberAccount;
import com.company.member.modules.account.service.AccountService;
import com.company.member.modules.cashier.dto.CashierOrderCmd;
import com.company.member.modules.cashier.entity.Order;
import com.company.member.modules.cashier.entity.OrderPayment;
import com.company.member.modules.cashier.entity.OrderRefund;
import com.company.member.modules.cashier.mapper.OrderItemMapper;
import com.company.member.modules.cashier.mapper.OrderMapper;
import com.company.member.modules.cashier.mapper.OrderPaymentMapper;
import com.company.member.modules.cashier.mapper.OrderRefundMapper;
import com.company.member.modules.marketing.entity.MemberCoupon;
import com.company.member.modules.marketing.mapper.MemberCouponMapper;
import com.company.member.modules.marketing.service.CouponService;
import com.company.member.modules.product.mapper.ProductMapper;
import com.company.member.modules.system.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 退单服务（UC-07）
 *
 * <p>规则：
 * <ul>
 *   <li>按支付来源区分退款路径：余额付 → 退回余额；现金/扫码 → 只记退款单，门店线下退现</li>
 *   <li>只退本金；赠送抵扣部分默认不退</li>
 *   <li>扣回本单获得积分与成长值</li>
 *   <li>券：未过期恢复可用，已过期置过期</li>
 *   <li>默认需审批（{@code order.auto_approve_refund=false}）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final OrderMapper orderMapper;
    private final OrderPaymentMapper orderPaymentMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderRefundMapper refundMapper;
    private final AccountService accountService;
    private final CouponService couponService;
    private final MemberCouponMapper memberCouponMapper;
    private final ProductMapper productMapper;
    private final BizNoGenerator noGenerator;
    private final ConfigService configService;

    /* ==================== 申请 ==================== */

    @Transactional(rollbackFor = Exception.class)
    public OrderRefund apply(String orderNo, BigDecimal refundAmount, String reason,
                             Long operatorId, String idempotencyKey) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!order.isPaid()) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID, "仅已支付订单可退单");
        }
        MoneyUtil.requirePositive(refundAmount, "退款金额");
        BigDecimal refunded = MoneyUtil.nullToZero(order.getRefundAmount());
        BigDecimal refundable = MoneyUtil.sub(order.getPaidAmount(), refunded);
        if (MoneyUtil.gt(refundAmount, refundable)) {
            throw new BizException(ErrorCode.REFUND_AMOUNT_EXCEED,
                    "本次退款 " + refundAmount + " 元超过可退金额 " + refundable + " 元");
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "退单原因必填");
        }

        // 计算扣回比例
        BigDecimal ratio = order.getPaidAmount().signum() == 0 ? BigDecimal.ZERO
                : refundAmount.divide(order.getPaidAmount(), 6, RoundingMode.HALF_UP);
        long deductPoints = order.getPointsGained() == null ? 0L
                : MoneyUtil.floorToLong(BigDecimal.valueOf(order.getPointsGained()).multiply(ratio));
        long deductGrowth = order.getGrowthGained() == null ? 0L
                : MoneyUtil.floorToLong(BigDecimal.valueOf(order.getGrowthGained()).multiply(ratio));

        OrderRefund refund = new OrderRefund();
        refund.setRefundNo(noGenerator.refundNo());
        refund.setOrderId(order.getId());
        refund.setOrderNo(orderNo);
        refund.setMemberId(order.getMemberId());
        refund.setStoreId(order.getStoreId());
        refund.setRefundAmount(MoneyUtil.scale(refundAmount));
        refund.setRefundGift(BigDecimal.ZERO);
        refund.setDeductGift(BigDecimal.ZERO);
        refund.setDeductPoints(deductPoints);
        refund.setRefundMethod(1);
        refund.setCouponHandle(1);
        refund.setReason(reason);
        refund.setOperatorId(operatorId);
        refund.setIdempotencyKey(idempotencyKey == null ? refund.getRefundNo() : idempotencyKey);
        refund.setStatus(OrderRefund.STATUS_PENDING);
        // 成长值扣回量暂存到 approveRemark 之外的字段不可用，这里通过 deductPoints + 日志体现；
        // 成长值扣回在审批通过时按比例重新计算，保证与申请时一致。

        boolean autoApprove = configService.autoApproveRefund();
        refundMapper.insert(refund);

        if (autoApprove) {
            log.info("[退单] 配置为免审，直接执行 refundNo={}", refund.getRefundNo());
            doApprove(refund, operatorId, "系统自动通过");
        } else {
            log.info("[退单] 已提交待审 refundNo={}, orderNo={}, 金额={}, 原因={}",
                    refund.getRefundNo(), orderNo, refundAmount, reason);
        }
        return refundMapper.selectById(refund.getId());
    }

    /* ==================== 审批 ==================== */

    @Transactional(rollbackFor = Exception.class)
    public void approve(String refundNo, boolean pass, String remark, Long approverId) {
        OrderRefund refund = refundMapper.selectByRefundNo(refundNo);
        if (refund == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "退款单不存在");
        }
        if (refund.getStatus() == null || refund.getStatus() != OrderRefund.STATUS_PENDING) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID, "该退款单已处理");
        }
        if (!pass) {
            int rows = refundMapper.changeStatus(refund.getId(), OrderRefund.STATUS_PENDING,
                    OrderRefund.STATUS_REJECTED, approverId, remark);
            if (rows == 0) {
                throw new BizException(ErrorCode.CONCURRENT_MODIFY);
            }
            log.info("[退单] 驳回 refundNo={}, 审批人={}, 意见={}", refundNo, approverId, remark);
            return;
        }
        doApprove(refund, approverId, remark);
    }

    /** 执行冲正：更新退款单状态 → 退回余额 → 扣回积分/成长值 → 处理券 → 更新订单状态 */
    private void doApprove(OrderRefund refund, Long approverId, String remark) {
        int rows = refundMapper.changeStatus(refund.getId(), OrderRefund.STATUS_PENDING,
                OrderRefund.STATUS_APPROVED, approverId, remark);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONCURRENT_MODIFY, "退款单状态已变更，请刷新");
        }

        Order order = orderMapper.selectById(refund.getOrderId());
        BigDecimal refundAmount = refund.getRefundAmount();

        // 1. 判断资金来源：只有「本金」支付的部分退回本金；赠送支付的部分退回赠送；
        //    现金/扫码支付的部分只记账，由门店线下退现。
        //    关键：不能简单用「退款额 vs 余额支付总额」取 min —— 余额支付可能全部来自赠送，
        //    那样会把赠送的钱退成本金（资金性质错误）。
        List<OrderPayment> payments = orderPaymentMapper.selectByOrderId(order.getId());
        BigDecimal balancePayAmount = BigDecimal.ZERO;
        BigDecimal principalPayAmount = BigDecimal.ZERO;
        BigDecimal giftPayAmount = BigDecimal.ZERO;
        for (OrderPayment p : payments) {
            if (p.getPayMethod() == null || p.getPayMethod() != OrderPayment.METHOD_BALANCE) {
                continue;
            }
            balancePayAmount = balancePayAmount.add(p.getAmount());
            boolean isGift = p.getAccountType() != null && p.getAccountType() == 2;
            if (isGift) {
                giftPayAmount = giftPayAmount.add(p.getAmount());
            } else {
                principalPayAmount = principalPayAmount.add(p.getAmount());
            }
        }
        // 按比例拆分本次退款：本金部分 / 赠送部分（部分退款时同样成立）
        BigDecimal backToPrincipal = BigDecimal.ZERO;
        BigDecimal backToGift = BigDecimal.ZERO;
        if (balancePayAmount.signum() > 0) {
            BigDecimal ratio = refundAmount.divide(balancePayAmount, 8, RoundingMode.HALF_UP);
            backToPrincipal = MoneyUtil.min(MoneyUtil.mul(principalPayAmount, ratio), principalPayAmount);
            backToGift = MoneyUtil.min(MoneyUtil.mul(giftPayAmount, ratio), giftPayAmount);
            // 尾差归入本金，保证 本金+赠送 == 应退余额部分
            BigDecimal backToBalanceTotal = MoneyUtil.min(refundAmount, balancePayAmount);
            BigDecimal allocated = MoneyUtil.add(backToPrincipal, backToGift);
            if (MoneyUtil.lt(allocated, backToBalanceTotal)) {
                backToPrincipal = MoneyUtil.add(backToPrincipal,
                        MoneyUtil.sub(backToBalanceTotal, allocated));
            }
        }

        // 2. 退回余额（本金与赠送分别写流水，保证各自账实相符）
        if (backToPrincipal.signum() > 0) {
            accountService.refundBalance(refund.getMemberId(), backToPrincipal, BigDecimal.ZERO,
                    refund.getRefundNo() + ":P", order.getOrderNo(), order.getId(), order.getStoreId(),
                    approverId, "退单退款(本金) " + order.getOrderNo());
        }
        if (backToGift.signum() > 0) {
            accountService.refundBalance(refund.getMemberId(), BigDecimal.ZERO, backToGift,
                    refund.getRefundNo() + ":G", order.getOrderNo(), order.getId(), order.getStoreId(),
                    approverId, "退单退款(赠送) " + order.getOrderNo());
        }

        // 3. 扣回积分（允许为负，后续获得补回）
        if (refund.getDeductPoints() != null && refund.getDeductPoints() > 0) {
            accountService.deductPointsForRefund(refund.getMemberId(), refund.getDeductPoints(),
                    refund.getRefundNo(), "退单扣回积分");
        }

        // 4. 扣回成长值（按退款比例）
        BigDecimal ratio = order.getPaidAmount().signum() == 0 ? BigDecimal.ZERO
                : refundAmount.divide(order.getPaidAmount(), 6, RoundingMode.HALF_UP);
        long deductGrowth = order.getGrowthGained() == null ? 0L
                : MoneyUtil.floorToLong(BigDecimal.valueOf(order.getGrowthGained()).multiply(ratio));
        if (deductGrowth > 0) {
            accountService.deductGrowthForRefund(refund.getMemberId(), deductGrowth,
                    refund.getRefundNo(), "退单扣回成长值");
        }

        // 5. 券处理：本单使用的券恢复
        List<MemberCoupon> usedCoupons = memberCouponMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MemberCoupon>()
                        .eq(MemberCoupon::getOrderId, order.getId())
                        .eq(MemberCoupon::getStatus, MemberCoupon.STATUS_USED));
        for (MemberCoupon c : usedCoupons) {
            couponService.restoreOnRefund(c.getId());
        }

        // 6. 库存回补
        for (var item : orderItemMapper.selectByOrderId(order.getId())) {
            if (item.getProductId() != null) {
                productMapper.restoreStock(item.getProductId(), item.getQty().intValue());
            }
        }

        // 7. 订单状态：累计退款额，达到实付则全额退
        int orderRows = orderMapper.applyRefund(order.getId(), refundAmount);
        if (orderRows == 0) {
            throw new BizException(ErrorCode.CONCURRENT_MODIFY, "订单状态已变更，请刷新");
        }

        log.warn("[退单] 通过 refundNo={}, orderNo={}, 退款={}, 退回本金={}, 退回赠送={}, 扣回积分={}, 扣回成长值={}, 恢复券={}",
                refund.getRefundNo(), order.getOrderNo(), refundAmount, backToPrincipal, backToGift,
                refund.getDeductPoints(), deductGrowth, usedCoupons.size());
    }

    /** 防重复提交用的幂等键校验（由 Controller 的 @Idempotent 处理，这里保留语义） */
    public void assertNotDuplicated(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        Long count = refundMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OrderRefund>()
                        .eq(OrderRefund::getIdempotencyKey, idempotencyKey));
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.REPEAT_SUBMIT, "该退单已提交，请勿重复操作");
        }
    }

    public OrderRefund getByRefundNo(String refundNo) {
        OrderRefund r = refundMapper.selectByRefundNo(refundNo);
        if (r == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "退款单不存在");
        }
        return r;
    }

    /** 可退金额（供前端展示） */
    public BigDecimal refundableAmount(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return MoneyUtil.atLeastZero(MoneyUtil.sub(order.getPaidAmount(), MoneyUtil.nullToZero(order.getRefundAmount())));
    }
}
