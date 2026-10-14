package com.company.member.modules.cashier.service;

import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.util.BizNoGenerator;
import com.company.member.common.util.MoneyUtil;
import com.company.member.modules.account.entity.MemberAccount;
import com.company.member.modules.account.service.AccountService;
import com.company.member.modules.account.vo.AccountChangeResult;
import com.company.member.modules.cashier.dto.CashierOrderCmd;
import com.company.member.modules.cashier.dto.SettleResult;
import com.company.member.modules.cashier.entity.Order;
import com.company.member.modules.cashier.entity.OrderItem;
import com.company.member.modules.cashier.entity.OrderPayment;
import com.company.member.modules.cashier.mapper.OrderItemMapper;
import com.company.member.modules.cashier.mapper.OrderMapper;
import com.company.member.modules.cashier.mapper.OrderPaymentMapper;
import com.company.member.modules.marketing.entity.MemberCoupon;
import com.company.member.modules.marketing.service.CouponService;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.entity.MemberLevel;
import com.company.member.modules.member.mapper.MemberLevelMapper;
import com.company.member.modules.member.mapper.MemberMapper;
import com.company.member.modules.product.entity.Product;
import com.company.member.modules.product.mapper.ProductMapper;
import com.company.member.modules.system.service.ConfigService;
import com.company.member.modules.member.event.ConsumeCompletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 收银结算服务 —— 消费闭环的核心
 *
 * <p><b>金额计算链（严格按序）</b>：
 * <pre>
 *   ① 原价合计   = Σ(单价 × 数量)
 *   ② 会员折扣   = Σ(参与折扣项小计 × (1 − 等级折扣率))；有会员价时优先用会员价
 *   ③ 券抵扣     = 券基准(①−②) 上按券规则计算，受门槛与封顶约束
 *   ④ 积分抵扣   = min(账户积分, floor((①−②−③) × 最高抵扣比例 × 兑换比例))
 *   ⑤ 应收       = ① − ② − ③ − ④  (≥ 0)
 *   ⑥ 实收       = Σ 支付明细（必须等于应收）
 *   ⑦ 获得积分   = floor(Σ 参与积分项实付分摊额 × 品类倍率 × 等级积分倍率)
 *   ⑧ 获得成长值 = floor(实付中的非赠送部分 × 成长值换算率)
 * </pre>
 *
 * <p><b>关键约束</b>：赠送余额抵扣部分不计积分与成长值（防止"充1000送1000刷等级"）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashierService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ProductMapper productMapper;
    private final MemberMapper memberMapper;
    private final MemberLevelMapper levelMapper;
    private final AccountService accountService;
    private final CouponService couponService;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderPaymentMapper orderPaymentMapper;
    private final BizNoGenerator noGenerator;
    private final ConfigService configService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.company.member.modules.staff.service.StaffService staffService;

    /* ==================================================================
     *  试算（不落库）
     * ================================================================== */

    public SettleResult preview(CashierOrderCmd cmd) {
        return calculate(cmd, false);
    }

    /* ==================================================================
     *  正式开单结算
     * ================================================================== */

    @Transactional(rollbackFor = Exception.class)
    public SettleResult settle(CashierOrderCmd cmd) {
        // 1. 服务端重算金额（不信任前端）
        SettleResult calc = calculate(cmd, true);

        // 2. 支付明细校验
        if (cmd.getPayments() == null || cmd.getPayments().isEmpty()) {
            throw new BizException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, "请提供支付明细");
        }
        BigDecimal paySum = BigDecimal.ZERO;
        for (CashierOrderCmd.Payment p : cmd.getPayments()) {
            if (p.getAmount() == null || MoneyUtil.lt(p.getAmount(), BigDecimal.ZERO)) {
                throw new BizException(ErrorCode.AMOUNT_INVALID, "支付金额不能为负");
            }
            if (p.getPayMethod() == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "支付方式不能为空");
            }
            if (p.getPayMethod() != OrderPayment.METHOD_POINT) {
                paySum = paySum.add(MoneyUtil.scale(p.getAmount()));
            }
        }
        if (!MoneyUtil.equalsAmount(paySum, calc.getPayableAmount())) {
            throw new BizException(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                    "支付金额合计 " + paySum + " 与应收 " + calc.getPayableAmount() + " 不一致");
        }

        // 3. 散客模式：必须无会员
        Member member = null;
        if (cmd.getMemberId() != null) {
            member = memberMapper.selectById(cmd.getMemberId());
            if (member == null) {
                throw new BizException(ErrorCode.MEMBER_NOT_FOUND);
            }
            if (member.isCancelled()) {
                throw new BizException(ErrorCode.MEMBER_CANCELLED);
            }
            if (member.isFrozen()) {
                throw new BizException(ErrorCode.MEMBER_FROZEN);
            }
        }
        boolean couponUsed = !calc.getCouponResults().isEmpty();
        boolean pointUsed = calc.getPointDeductPoints() > 0;
        if ((couponUsed || pointUsed) && member == null) {
            throw new BizException(ErrorCode.ORDER_NEED_MEMBER, "使用券或积分必须选择会员");
        }

        Long storeId = cmd.getStoreId() != null ? cmd.getStoreId() : (member != null ? member.getStoreId() : null);

        // 4. 生成订单（唯一键冲突时自动重试）
        String orderNo = noGenerator.generateWithRetry(noGenerator::consumeOrderNo,
                no -> orderMapper.selectByOrderNo(no) != null);
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setOrderType(cmd.getOrderType() == null ? Order.TYPE_CONSUME : cmd.getOrderType());
        order.setMemberId(cmd.getMemberId() == null ? 0L : cmd.getMemberId());
        order.setStoreId(storeId);
        order.setStaffId(resolveStaffId(cmd.getStaffId(), storeId));
        order.setTotalAmount(calc.getTotalAmount());
        order.setDiscountAmount(calc.getDiscountAmount());
        order.setCouponAmount(calc.getCouponAmount());
        order.setPointDeductAmount(calc.getPointDeductAmount());
        order.setPointDeductPoints(calc.getPointDeductPoints());
        order.setPayableAmount(calc.getPayableAmount());
        order.setPaidAmount(calc.getPaidAmount());
        order.setGiftAmount(BigDecimal.ZERO);
        order.setPointsGained(calc.getPointsGained());
        order.setGrowthGained(calc.getGrowthGained());
        order.setRefundAmount(BigDecimal.ZERO);
        order.setStatus(Order.STATUS_PAID);
        order.setShiftNo(cmd.getShiftNo());
        order.setIdempotencyKey(cmd.getIdempotencyKey() == null || cmd.getIdempotencyKey().isBlank()
                ? orderNo : cmd.getIdempotencyKey());
        order.setRemark(cmd.getRemark());
        order.setPaidAt(LocalDateTime.now());
        order.setTenantId(0L);
        order.setCashierId(com.company.member.common.constant.OperatorContext.userId());
        orderMapper.insert(order);

        // 5. 明细落库（快照）
        for (SettleResult.ItemResult ir : calc.getItems()) {
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setOrderNo(orderNo);
            item.setItemType(ir.getItemType());
            item.setProductId(ir.getProductId());
            item.setProductName(ir.getProductName());
            item.setProductCode(ir.getProductCode());
            item.setPrice(ir.getPrice());
            item.setQty(ir.getQty());
            item.setTotalAmount(ir.getTotalAmount());
            item.setDiscountAmount(ir.getDiscountAmount());
            item.setActualAmount(ir.getActualAmount());
            item.setJoinDiscount(Boolean.TRUE.equals(ir.getJoinDiscount()) ? 1 : 0);
            item.setJoinPoints(Boolean.TRUE.equals(ir.getJoinPoints()) ? 1 : 0);
            item.setPointsRate(ir.getPointsRate());
            orderItemMapper.insert(item);
        }

        // 6. 余额扣减（逐笔支付）
        BigDecimal principalPaid = BigDecimal.ZERO;
        BigDecimal giftPaid = BigDecimal.ZERO;
        BigDecimal cashPaid = BigDecimal.ZERO;
        BigDecimal offlinePaid = BigDecimal.ZERO;
        List<OrderPayment> paymentRows = new ArrayList<>();
        boolean balancePaid = false;

        for (CashierOrderCmd.Payment p : cmd.getPayments()) {
            if (p.getPayMethod() == OrderPayment.METHOD_POINT) {
                continue;
            }
            OrderPayment row = new OrderPayment();
            row.setOrderId(order.getId());
            row.setOrderNo(orderNo);
            row.setPayMethod(p.getPayMethod());
            row.setAmount(MoneyUtil.scale(p.getAmount()));
            row.setOutTradeNo(p.getOutTradeNo());
            row.setIdempotencyKey(orderNo + ":" + p.getPayMethod());
            switch (p.getPayMethod()) {
                case OrderPayment.METHOD_BALANCE -> {
                    if (balancePaid) {
                        throw new BizException(ErrorCode.PARAM_INVALID, "储值余额支付只能有一笔");
                    }
                    balancePaid = true;
                    if (member == null) {
                        throw new BizException(ErrorCode.ORDER_NEED_MEMBER, "余额支付必须选择会员");
                    }
                    // 服务端按优先级拆分本金/赠送
                    AccountService.SplitResult split = accountService.consumeSplit(member.getId(), p.getAmount());
                    principalPaid = split.principal();
                    giftPaid = split.gift();
                    // 关键：本金与赠送**分别落一条支付明细**（account_type 区分）。
                    // 退单时需要据此把退款退回正确的账户类型，否则会把赠送的钱退成本金（资金性质错误）。
                    if (principalPaid.signum() > 0) {
                        OrderPayment pr = new OrderPayment();
                        pr.setOrderId(order.getId());
                        pr.setOrderNo(orderNo);
                        pr.setPayMethod(OrderPayment.METHOD_BALANCE);
                        pr.setAccountType(1);
                        pr.setAmount(principalPaid);
                        pr.setIdempotencyKey(orderNo + ":BALANCE:P");
                        row = pr;
                    } else {
                        // 全部由赠送余额支付：row 直接改为赠送流水
                        row.setAccountType(2);
                        row.setAmount(giftPaid);
                        row.setIdempotencyKey(orderNo + ":BALANCE:G");
                        paymentRows.add(row);
                        row = null;
                    }
                    if (giftPaid.signum() > 0 && principalPaid.signum() > 0) {
                        OrderPayment gr = new OrderPayment();
                        gr.setOrderId(order.getId());
                        gr.setOrderNo(orderNo);
                        gr.setPayMethod(OrderPayment.METHOD_BALANCE);
                        gr.setAccountType(2);
                        gr.setAmount(giftPaid);
                        gr.setIdempotencyKey(orderNo + ":BALANCE:G");
                        paymentRows.add(gr);
                    }
                }
                case OrderPayment.METHOD_CASH -> cashPaid = cashPaid.add(MoneyUtil.scale(p.getAmount()));
                case OrderPayment.METHOD_OFFLINE_CODE -> offlinePaid = offlinePaid.add(MoneyUtil.scale(p.getAmount()));
                default -> {
                }
            }
            if (row != null) {
                paymentRows.add(row);
            }
        }
        // 余额扣减走统一入口（写流水 + 累计消费）
        AccountChangeResult accResult = null;
        if (member != null && balancePaid) {
            BigDecimal total = MoneyUtil.add(principalPaid, giftPaid);
            accResult = accountService.consume(member.getId(), principalPaid, giftPaid, orderNo, order.getId(),
                    storeId, order.getCashierId(), cmd.getShiftNo(), "消费");
        } else if (member != null) {
            // 非余额支付：仅累计消费统计与笔数
            accResult = accountService.consume(member.getId(), BigDecimal.ZERO, BigDecimal.ZERO,
                    orderNo, order.getId(), storeId, order.getCashierId(), cmd.getShiftNo(), "消费");
        }

        // 积分抵扣：扣积分
        if (member != null && calc.getPointDeductPoints() > 0) {
            accountService.deductPointsForConsume(member.getId(), calc.getPointDeductPoints(), orderNo);
            OrderPayment pointRow = new OrderPayment();
            pointRow.setOrderId(order.getId());
            pointRow.setOrderNo(orderNo);
            pointRow.setPayMethod(OrderPayment.METHOD_POINT);
            pointRow.setAmount(calc.getPointDeductAmount());
            pointRow.setIdempotencyKey(orderNo + ":POINT");
            pointRow.setRemark("积分抵扣 " + calc.getPointDeductPoints() + " 分");
            paymentRows.add(pointRow);
        }
        for (OrderPayment row : paymentRows) {
            orderPaymentMapper.insert(row);
        }

        // 7. 券核销
        if (member != null && couponUsed) {
            for (SettleResult.CouponResult cr : calc.getCouponResults()) {
                MemberCoupon coupon = couponService.getByCouponNo(cr.getCouponNo());
                couponService.use(coupon.getId(), order.getId(), storeId);
            }
        }

        // 8. 获得积分与成长值
        //    积分按「实付 − 赠送余额部分」计算；成长值同理
        BigDecimal pointsBase = MoneyUtil.sub(calc.getPaidAmount(), giftPaid);
        long pointsGained = 0L;
        long growthGained = 0L;
        if (member != null && pointsBase.signum() > 0) {
            MemberLevel level = member.getLevelId() == null ? null : levelMapper.selectById(member.getLevelId());
            BigDecimal levelPointsRate = level == null ? BigDecimal.ONE : level.pointsRateOrOne();
            // ⑦ 积分：按明细分摊计算，再乘等级倍率
            BigDecimal weighted = BigDecimal.ZERO;
            for (SettleResult.ItemResult ir : calc.getItems()) {
                if (!Boolean.TRUE.equals(ir.getJoinPoints())) {
                    continue;
                }
                BigDecimal share = calc.getPaidAmount().signum() == 0 ? BigDecimal.ZERO
                        : MoneyUtil.mul(ir.getActualAmount(),
                        calc.getPaidAmount().divide(calc.getPayableAmount().signum() == 0 ? BigDecimal.ONE : calc.getPayableAmount(),
                                6, RoundingMode.HALF_UP));
                weighted = weighted.add(MoneyUtil.mul(share,
                        ir.getPointsRate() == null ? BigDecimal.ONE : ir.getPointsRate()));
            }
            BigDecimal perYuan = configService.pointsPerYuan();
            pointsGained = MoneyUtil.floorToLong(MoneyUtil.mul(weighted, perYuan).multiply(levelPointsRate));
            growthGained = MoneyUtil.floorToLong(MoneyUtil.mul(pointsBase, configService.growthPerYuan()));
        }

        if (member != null && (pointsGained > 0 || growthGained > 0)) {
            accResult = accountService.gainByConsume(member.getId(), pointsGained, growthGained,
                    orderNo, order.getId(), storeId, "消费获得");
        }

        // 9. 回填订单实收与积分快照
        order.setPaidAmount(MoneyUtil.add(MoneyUtil.add(principalPaid, giftPaid), MoneyUtil.add(cashPaid, offlinePaid)));
        order.setPointsGained(pointsGained);
        order.setGrowthGained(growthGained);
        orderMapper.updateById(order);

        // 10. 组装返回
        calc.setOrderNo(orderNo);
        calc.setStatus(order.getStatus());
        calc.setPaidAmount(order.getPaidAmount());
        calc.setPointsGained(pointsGained);
        calc.setGrowthGained(growthGained);
        calc.setAccount(accResult);
        if (cmd.getReceivedAmount() != null && cashPaid.signum() > 0) {
            calc.setChangeAmount(MoneyUtil.atLeastZero(MoneyUtil.sub(cmd.getReceivedAmount(), cashPaid)));
        }

        // 11. 库存扣减（启用库存管理的商品）
        for (SettleResult.ItemResult ir : calc.getItems()) {
            if (ir.getProductId() != null && ir.getQty() != null) {
                productMapper.deductStock(ir.getProductId(), ir.getQty().intValue());
            }
        }

        // 12. 发布消费完成事件（事务提交后触发等级评定、推荐奖励、消息推送等）
        if (member != null) {
            eventPublisher.publishEvent(new ConsumeCompletedEvent(
                    member.getId(), order.getId(), orderNo, storeId,
                    order.getPaidAmount(),
                    BigDecimal.valueOf(pointsGained), BigDecimal.valueOf(growthGained)));
        }

        log.info("[收银] 结算成功 orderNo={}, memberId={}, 应收={}, 实收={}, 获得积分={}, 余额付={}, 赠送付={}, 现金={}, 扫码={}",
                orderNo, cmd.getMemberId(), calc.getPayableAmount(), order.getPaidAmount(),
                pointsGained, principalPaid, giftPaid, cashPaid, offlinePaid);
        return calc;
    }

    /* ==================================================================
     *  金额计算核心
     * ================================================================== */

    private SettleResult calculate(CashierOrderCmd cmd, boolean strict) {
        SettleResult r = new SettleResult();

        // ---------- 会员与等级 ----------
        Member member = null;
        MemberLevel level = null;
        MemberAccount account = null;
        if (cmd.getMemberId() != null) {
            member = memberMapper.selectById(cmd.getMemberId());
            if (member == null) {
                throw new BizException(ErrorCode.MEMBER_NOT_FOUND);
            }
            account = accountService.getAccount(member.getId());
            if (member.getLevelId() != null) {
                level = levelMapper.selectById(member.getLevelId());
            }
            SettleResult.MemberSnapshot ms = new SettleResult.MemberSnapshot();
            ms.setMemberId(member.getId());
            ms.setMemberNo(member.getMemberNo());
            ms.setNickname(member.getNickname());
            ms.setLevelName(level == null ? null : level.getLevelName());
            ms.setDiscountRate(level == null ? BigDecimal.ONE : level.discountRateOrOne());
            ms.setPointsRate(level == null ? BigDecimal.ONE : level.pointsRateOrOne());
            ms.setBalance(account.getBalance());
            ms.setGiftBalance(account.getGiftBalance());
            ms.setPoints(account.getPoints());
            ms.setGrowth(account.getGrowth());
            r.setMemberSnapshot(ms);
        }
        BigDecimal discountRate = level == null ? BigDecimal.ONE : level.discountRateOrOne();

        // ---------- ① 原价合计 + ② 会员折扣 ----------
        List<SettleResult.ItemResult> itemResults = new ArrayList<>();
        List<Long> productIds = new ArrayList<>();
        for (CashierOrderCmd.Item it : cmd.getItems()) {
            if (it.getQty() == null || it.getQty().signum() <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "商品数量必须大于 0");
            }
            SettleResult.ItemResult ir = new SettleResult.ItemResult();
            ir.setItemType(it.getItemType() == null ? OrderItem.TYPE_PRODUCT : it.getItemType());
            ir.setQty(it.getQty());
            ir.setJoinDiscount(true);
            ir.setJoinPoints(true);
            ir.setPointsRate(BigDecimal.ONE);
            ir.setProductId(it.getProductId());

            BigDecimal unitPrice;
            if (it.getProductId() != null) {
                Product p = productMapper.selectById(it.getProductId());
                if (p == null) {
                    throw new BizException(ErrorCode.DATA_NOT_FOUND, "商品不存在：" + it.getProductId());
                }
                if (!p.onShelf()) {
                    throw new BizException(ErrorCode.PRODUCT_OFF_SHELF, "商品已下架：" + p.getProductName());
                }
                ir.setProductName(p.getProductName());
                ir.setProductCode(p.getProductCode());
                ir.setJoinDiscount(p.discountJoin());
                ir.setJoinPoints(p.pointsJoin());
                ir.setPointsRate(p.pointsRateOrOne());
                productIds.add(p.getId());
                // 会员价优先，其次标准价
                unitPrice = p.getMemberPrice() != null && member != null ? p.getMemberPrice() : p.getPrice();
                if (p.getMemberPrice() != null && member != null) {
                    ir.setJoinDiscount(false); // 已用会员价，不再叠加等级折扣
                }
            } else {
                // 自由金额（服务类临时定价）
                if (!MoneyUtil.isPositive(it.getPrice())) {
                    throw new BizException(ErrorCode.AMOUNT_INVALID, "自由金额必须大于 0");
                }
                unitPrice = it.getPrice();
                ir.setProductName(it.getProductName() == null ? "自由金额" : it.getProductName());
                ir.setItemType(OrderItem.TYPE_FREE_AMOUNT);
                ir.setJoinDiscount(false);
            }
            ir.setPrice(MoneyUtil.scale(unitPrice));
            BigDecimal lineTotal = MoneyUtil.mul(unitPrice, it.getQty());
            ir.setTotalAmount(lineTotal);

            BigDecimal lineDiscount = BigDecimal.ZERO;
            if (Boolean.TRUE.equals(ir.getJoinDiscount()) && member != null && discountRate.compareTo(BigDecimal.ONE) < 0) {
                lineDiscount = MoneyUtil.mul(lineTotal, BigDecimal.ONE.subtract(discountRate));
            }
            ir.setDiscountAmount(lineDiscount);
            ir.setActualAmount(MoneyUtil.sub(lineTotal, lineDiscount));
            itemResults.add(ir);
        }
        r.setItems(itemResults);

        BigDecimal totalAmount = itemResults.stream()
                .map(SettleResult.ItemResult::getTotalAmount)
                .reduce(BigDecimal.ZERO, MoneyUtil::add);
        BigDecimal discountAmount = itemResults.stream()
                .map(SettleResult.ItemResult::getDiscountAmount)
                .reduce(BigDecimal.ZERO, MoneyUtil::add);
        r.setTotalAmount(MoneyUtil.scale(totalAmount));
        r.setDiscountAmount(MoneyUtil.scale(discountAmount));
        BigDecimal afterDiscount = MoneyUtil.sub(totalAmount, discountAmount);

        // ---------- ③ 券抵扣 ----------
        BigDecimal couponAmount = BigDecimal.ZERO;
        if (cmd.getCouponNos() != null && !cmd.getCouponNos().isEmpty()) {
            if (member == null) {
                throw new BizException(ErrorCode.ORDER_NEED_MEMBER, "使用优惠券必须选择会员");
            }
            List<SettleResult.CouponResult> applied = new ArrayList<>();
            for (String couponNo : cmd.getCouponNos()) {
                MemberCoupon coupon = couponService.getByCouponNo(couponNo);
                SettleResult.CouponResult cr = new SettleResult.CouponResult();
                cr.setCouponNo(couponNo);
                cr.setTplId(coupon.getTplId());
                cr.setTplName(coupon.getCouponName());
                cr.setCouponType(coupon.getCouponType());
                cr.setValidEnd(coupon.getValidEnd() == null ? null : coupon.getValidEnd().format(DT));
                // 归属校验：券必须属于该会员
                if (!coupon.getMemberId().equals(member.getId())) {
                    cr.setUsable(false);
                    cr.setReason("该券不属于当前会员");
                    r.getUnavailableCoupons().add(cr);
                    if (strict) {
                        throw new BizException(ErrorCode.COUPON_UNAVAILABLE, "该券不属于当前会员");
                    }
                    continue;
                }
                // 不可叠加校验
                if (applied.size() >= 1 && (coupon.getAllowStack() == null || coupon.getAllowStack() == 0)) {
                    cr.setUsable(false);
                    cr.setReason("该券不可与其他券叠加使用");
                    r.getUnavailableCoupons().add(cr);
                    if (strict) {
                        throw new BizException(ErrorCode.COUPON_STACK_NOT_ALLOWED);
                    }
                    continue;
                }
                BigDecimal base = MoneyUtil.sub(afterDiscount, couponAmount);
                try {
                    BigDecimal d = couponService.calcDiscount(coupon, base, cmd.getStoreId(), productIds);
                    cr.setDiscountAmount(d);
                    cr.setUsable(true);
                    couponAmount = MoneyUtil.add(couponAmount, d);
                    applied.add(cr);
                } catch (BizException e) {
                    cr.setUsable(false);
                    cr.setReason(e.getMessage());
                    r.getUnavailableCoupons().add(cr);
                    if (strict) {
                        throw e;
                    }
                }
            }
            // 券抵扣不得超过折后金额
            if (MoneyUtil.gt(couponAmount, afterDiscount)) {
                couponAmount = afterDiscount;
            }
            r.setCouponResults(applied);
        }
        r.setCouponAmount(MoneyUtil.scale(couponAmount));

        // ---------- ④ 积分抵扣 ----------
        BigDecimal beforePoints = MoneyUtil.sub(afterDiscount, couponAmount);
        BigDecimal maxRatio = configService.pointsMaxDeductRatio();
        int exchangeRate = configService.pointsExchangeRate();
        long minKeep = configService.pointsMinKeep();
        BigDecimal maxDeductByRule = MoneyUtil.mul(beforePoints, maxRatio);
        long usablePoints = 0L;
        BigDecimal maxDeductAmount = BigDecimal.ZERO;
        if (member != null && account != null) {
            long available = Math.max((account.getPoints() == null ? 0L : account.getPoints()) - minKeep, 0L);
            maxDeductByRule = MoneyUtil.min(maxDeductByRule, beforePoints);
            long pointsByRule = maxDeductByRule.multiply(BigDecimal.valueOf(exchangeRate))
                    .setScale(0, RoundingMode.FLOOR).longValueExact();
            usablePoints = Math.min(available, pointsByRule);
            maxDeductAmount = BigDecimal.valueOf(usablePoints)
                    .divide(BigDecimal.valueOf(exchangeRate), 2, RoundingMode.FLOOR);
        }
        r.setMaxUsablePoints(usablePoints);
        r.setMaxPointDeductAmount(MoneyUtil.scale(maxDeductAmount));

        long usePoints = cmd.getUsePoints() == null ? 0L : cmd.getUsePoints();
        if (usePoints < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "使用积分不能为负");
        }
        if (usePoints > usablePoints) {
            if (strict) {
                throw new BizException(ErrorCode.POINTS_NOT_ENOUGH,
                        "可用积分不足：本单最多可用 " + usablePoints + " 积分");
            }
            usePoints = usablePoints;
        }
        BigDecimal pointDeduct = BigDecimal.ZERO;
        if (usePoints > 0) {
            pointDeduct = BigDecimal.valueOf(usePoints)
                    .divide(BigDecimal.valueOf(exchangeRate), 2, RoundingMode.FLOOR);
        }
        // 积分抵扣不得超过剩余应付
        pointDeduct = MoneyUtil.min(pointDeduct, beforePoints);
        if (pointDeduct.signum() > 0) {
            usePoints = pointDeduct.multiply(BigDecimal.valueOf(exchangeRate))
                    .setScale(0, RoundingMode.CEILING).longValueExact();
            if (usePoints > usablePoints) {
                usePoints = usablePoints;
                pointDeduct = BigDecimal.valueOf(usePoints)
                        .divide(BigDecimal.valueOf(exchangeRate), 2, RoundingMode.FLOOR);
            }
        } else {
            usePoints = 0L;
        }
        r.setPointDeductPoints(usePoints);
        r.setPointDeductAmount(MoneyUtil.scale(pointDeduct));

        // ---------- ⑤ 应收 ----------
        BigDecimal payable = MoneyUtil.sub(MoneyUtil.sub(beforePoints, pointDeduct), BigDecimal.ZERO);
        payable = MoneyUtil.atLeastZero(payable);
        r.setPayableAmount(MoneyUtil.scale(payable));
        r.setPaidAmount(MoneyUtil.scale(payable));

        // ---------- ⑦⑧ 预估获得积分/成长值（正式结算会按实际支付覆盖） ----------
        if (member != null && payable.signum() > 0) {
            BigDecimal grossWeighted = BigDecimal.ZERO;
            for (SettleResult.ItemResult ir : itemResults) {
                if (!Boolean.TRUE.equals(ir.getJoinPoints())) {
                    continue;
                }
                // 按行小计占比分摊实付
                BigDecimal ratio = totalAmount.signum() == 0 ? BigDecimal.ZERO
                        : ir.getTotalAmount().divide(totalAmount, 6, RoundingMode.HALF_UP);
                grossWeighted = grossWeighted.add(MoneyUtil.mul(MoneyUtil.mul(payable, ratio), ir.getPointsRate()));
            }
            BigDecimal levelRate = level == null ? BigDecimal.ONE : level.pointsRateOrOne();
            r.setPointsGained(MoneyUtil.floorToLong(
                    MoneyUtil.mul(grossWeighted, configService.pointsPerYuan()).multiply(levelRate)));
            r.setGrowthGained(MoneyUtil.floorToLong(
                    MoneyUtil.mul(payable, configService.growthPerYuan())));
        }
        return r;
    }

    /* ==================================================================
     *  订单查询
     * ================================================================== */

    /**
     * 解析业绩归属员工
     *
     * <p>优先用调用方显式传入的 {@code staffId}（收银台选人，代班/交接场景）；
     * 未传时回落到「当前登录账号所绑定的员工」。
     *
     * <p><b>为什么要这个回落</b>：{@code m_order.staff_id} 是业绩归属的唯一依据，
     * 而它此前完全依赖前端传值 —— 前端漏传就永久丢失归属，事后无法补。
     * 有了回落，只要账号挂了员工档案，业绩就不会丢。
     *
     * <p>刻意<b>不校验</b>传入的 staffId 是否属于当前门店：连锁门店常有跨店支援，
     * 强行限制会把真实业务挡在门外。归属是否合理，由「业绩排行」的门店筛选去发现。
     */
    private Long resolveStaffId(Long cmdStaffId, Long storeId) {
        if (cmdStaffId != null) {
            return cmdStaffId;
        }
        com.company.member.modules.staff.entity.Staff bound =
                staffService.findByAccount(com.company.member.common.constant.OperatorContext.userId());
        if (bound != null) {
            log.debug("[收银] 未指定业绩归属，自动取当前账号绑定员工 staffId={}, storeId={}", bound.getId(), storeId);
            return bound.getId();
        }
        return null;
    }

    public Order getByOrderNo(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    public List<OrderItem> items(Long orderId) {
        return orderItemMapper.selectByOrderId(orderId);
    }

    public List<OrderPayment> payments(Long orderId) {
        return orderPaymentMapper.selectByOrderId(orderId);
    }
}
