package com.company.member.modules.payment.service;

import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.util.BizNoGenerator;
import com.company.member.common.util.MoneyUtil;
import com.company.member.modules.account.dto.RechargeCmd;
import com.company.member.modules.account.dto.RechargeResult;
import com.company.member.modules.account.service.RechargeService;
import com.company.member.modules.cashier.entity.Order;
import com.company.member.modules.cashier.mapper.OrderMapper;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.mapper.MemberMapper;
import com.company.member.modules.payment.channel.PayChannel;
import com.company.member.modules.payment.channel.PayChannelRegistry;
import com.company.member.modules.payment.entity.PaymentNotifyLog;
import com.company.member.modules.payment.entity.PaymentOrder;
import com.company.member.modules.payment.mapper.PaymentNotifyLogMapper;
import com.company.member.modules.payment.mapper.PaymentOrderMapper;
import com.company.member.modules.system.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 支付服务（预留：小程序在线支付）
 *
 * <p>核心约定：
 * <ol>
 *   <li>{@link #prepay} 只负责下单与落支付单，不做任何资产变动</li>
 *   <li>{@link #handlePaid} 是**唯一的入账入口**，回调与主动查单都走它，天然幂等</li>
 *   <li>资产入账复用 {@link RechargeService}，与收银员手工充值完全同一套逻辑</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayService {

    private final PaymentOrderMapper paymentMapper;
    private final PaymentNotifyLogMapper notifyLogMapper;
    private final PayChannelRegistry channelRegistry;
    private final OrderMapper orderMapper;
    private final MemberMapper memberMapper;
    private final RechargeService rechargeService;
    private final BizNoGenerator noGenerator;
    private final ConfigService configService;

    /* ==================================================================
     *  统一下单
     * ================================================================== */

    /**
     * 统一下单：为业务单创建（或复用）支付单并调渠道预下单
     *
     * @param bizType  业务类型：1充值 2积分兑换 3售卡
     * @param bizNo    业务单号（充值单号 / 订单号）
     * @param channel  渠道（空则用默认渠道）
     * @return 支付单 + 前端调起支付参数
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> prepay(Integer bizType, String bizNo, String channel, Long memberId) {
        if (!configService.payEnabled() && !configService.payMockEnabled()) {
            throw new BizException(ErrorCode.PAY_CHANNEL_NOT_READY, "在线支付未启用");
        }
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BizException(ErrorCode.MEMBER_NOT_FOUND);
        }
        if (!member.hasPhone()) {
            throw new BizException(ErrorCode.PAY_NEED_BIND_PHONE);
        }
        Order order = orderMapper.selectByOrderNo(bizNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (order.isPaid()) {
            throw new BizException(ErrorCode.PAY_DUPLICATE);
        }

        // 复用未过期的支付单（避免重复下单）
        PaymentOrder existed = paymentMapper.selectByBiz(bizType, bizNo);
        if (existed != null && (existed.getStatus() == PaymentOrder.STATUS_UNPAID
                || existed.getStatus() == PaymentOrder.STATUS_PAYING) && !existed.expired()) {
            log.info("[支付] 复用未过期支付单 paymentNo={}", existed.getPaymentNo());
            return buildPrepayResponse(existed, channel, member);
        }

        PayChannel payChannel = channelRegistry.get(channel);
        BigDecimal amount = order.getPayableAmount();

        PaymentOrder po = new PaymentOrder();
        po.setPaymentNo(noGenerator.paymentNo());
        po.setBizType(bizType);
        po.setBizNo(bizNo);
        po.setOrderId(order.getId());
        po.setMemberId(memberId);
        po.setOpenid(member.getOpenid());
        po.setPayScene(1);
        po.setChannel(payChannel.channelCode());
        po.setChannelAppid(configService.getString("pay.channel-appid", null));
        po.setAmount(MoneyUtil.scale(amount));
        po.setAmountFen(MoneyUtil.toFen(amount));
        po.setCurrency("CNY");
        po.setSubject(Order.typeText(order.getOrderType()) + " " + bizNo);
        po.setDescription("会员在线支付");
        po.setStatus(PaymentOrder.STATUS_UNPAID);
        po.setStoreId(order.getStoreId());
        po.setIdempotencyKey("prepay:" + bizType + ":" + bizNo);
        po.setExpireTime(LocalDateTime.now().plusMinutes(configService.payTimeoutMinutes()));
        po.setRefundAmount(BigDecimal.ZERO);
        po.setNotifyCount(0);
        po.setAccountCredited(0);
        po.setTenantId(0L);
        paymentMapper.insert(po);

        // 关联到订单
        order.setPaymentNo(po.getPaymentNo());
        order.setExpireTime(po.getExpireTime());
        order.setStatus(Order.STATUS_PAYING);
        orderMapper.updateById(order);

        PayChannel.PrepayResult prepay = payChannel.prepay(new PayChannel.PayRequest(
                po.getPaymentNo(), bizNo, bizType, memberId, member.getOpenid(),
                po.getAmount(), po.getSubject(), po.getDescription(),
                configService.payNotifyUrl(), null));

        if (!prepay.success()) {
            paymentMapper.markFailed(po.getId(), prepay.errorCode(), prepay.errorMsg());
            throw new BizException(ErrorCode.PAY_CHANNEL_ERROR, prepay.errorMsg());
        }
        po.setPrepayId(prepay.prepayId());
        paymentMapper.updateById(po);

        log.info("[支付] 下单成功 paymentNo={}, bizNo={}, 金额={}, 渠道={}",
                po.getPaymentNo(), bizNo, po.getAmount(), po.getChannel());
        return buildPrepayResponse(po, payChannel.channelCode(), member, prepay.payParams());
    }

    private Map<String, Object> buildPrepayResponse(PaymentOrder po, String channel, Member member) {
        PayChannel payChannel = channelRegistry.get(channel == null ? po.getChannel() : channel);
        PayChannel.PrepayResult prepay = payChannel.prepay(new PayChannel.PayRequest(
                po.getPaymentNo(), po.getBizNo(), po.getBizType(), po.getMemberId(), member.getOpenid(),
                po.getAmount(), po.getSubject(), po.getDescription(),
                configService.payNotifyUrl(), null));
        return buildPrepayResponse(po, payChannel.channelCode(), member, prepay.payParams());
    }

    private Map<String, Object> buildPrepayResponse(PaymentOrder po, String channel, Member member,
                                                    Map<String, String> payParams) {
        Map<String, Object> m = new HashMap<>();
        m.put("paymentNo", po.getPaymentNo());
        m.put("bizNo", po.getBizNo());
        m.put("channel", channel);
        m.put("amount", po.getAmount());
        m.put("status", po.getStatus());
        m.put("statusText", PaymentOrder.statusText(po.getStatus()));
        m.put("expireTime", po.getExpireTime());
        m.put("payParams", payParams);
        return m;
    }

    /* ==================================================================
     *  统一入账入口（回调 / 查单补偿 共用）
     * ================================================================== */

    /**
     * 处理支付成功 —— <b>唯一入账入口</b>
     *
     * <p>幂等保证：
     * <ol>
     *   <li>支付单状态判断（仅待支付/支付中可流转）</li>
     *   <li>{@code account_credited} 标记（入账前检查）</li>
     *   <li>资产入账使用 {@code paymentNo} 作为幂等键</li>
     * </ol>
     *
     * @return 是否发生了状态变化（true 表示本次真正入账）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean handlePaid(PaymentOrder payment, String channelOrderNo, BigDecimal notifyAmount) {
        // 1. 金额校验（防篡改）
        if (notifyAmount != null && notifyAmount.signum() > 0
                && !MoneyUtil.equalsAmount(notifyAmount, payment.getAmount())) {
            log.error("[支付] 回调金额不一致 paymentNo={}, 通知={}, 支付单={}",
                    payment.getPaymentNo(), notifyAmount, payment.getAmount());
            throw new BizException(ErrorCode.PAY_AMOUNT_MISMATCH,
                    "回调金额 " + notifyAmount + " 与支付单金额 " + payment.getAmount() + " 不一致");
        }
        // 2. 已入账则直接返回（幂等）
        if (payment.getAccountCredited() != null && payment.getAccountCredited() == 1) {
            log.info("[支付] 已入账，忽略重复通知 paymentNo={}", payment.getPaymentNo());
            return false;
        }
        if (payment.getStatus() != null && payment.getStatus() == PaymentOrder.STATUS_PAID) {
            // 状态已支付但未入账：继续走入账（补偿场景）
            log.warn("[支付] 支付单已支付但未入账，执行补入账 paymentNo={}", payment.getPaymentNo());
        } else {
            int rows = paymentMapper.markPaid(payment.getId(), channelOrderNo);
            if (rows == 0) {
                log.info("[支付] 状态已流转，跳过 paymentNo={}", payment.getPaymentNo());
                return false;
            }
        }

        // 3. 业务订单置为已支付
        Order order = orderMapper.selectById(payment.getOrderId());
        if (order != null && !order.isPaid()) {
            orderMapper.markPaid(order.getId(), Order.STATUS_PAID, order.getPayableAmount());
        }

        // 4. 资产入账（复用充值服务，与收银员手工充值同一逻辑）
        if (payment.getBizType() != null && payment.getBizType() == PaymentOrder.BIZ_RECHARGE) {
            RechargeCmd cmd = new RechargeCmd();
            cmd.setMemberId(payment.getMemberId());
            cmd.setRechargeAmount(payment.getAmount());
            cmd.setGiftAmount(BigDecimal.ZERO);
            cmd.setGiftPoints(0L);
            cmd.setPayMethod(com.company.member.modules.cashier.entity.OrderPayment.METHOD_WECHAT_ONLINE);
            cmd.setRemark("在线充值");
            cmd.setIdempotencyKey(payment.getPaymentNo());
            // 注意：充值单已存在（prepay 前创建），这里直接对已有订单入账
            RechargeResult result = rechargeService.rechargeExistingOrder(order, cmd,
                    payment.getPaymentNo(), com.company.member.modules.cashier.entity.OrderPayment.METHOD_WECHAT_ONLINE);
            log.info("[支付] 充值入账完成 paymentNo={}, 订单={}, 金额={}",
                    payment.getPaymentNo(), payment.getBizNo(), payment.getAmount());
        } else {
            log.info("[支付] 非充值类支付成功，待业务模块处理 paymentNo={}, bizType={}",
                    payment.getPaymentNo(), payment.getBizType());
        }

        // 5. 标记入账完成
        paymentMapper.markCredited(payment.getId());
        return true;
    }

    /* ==================================================================
     *  回调处理
     * ================================================================== */

    /**
     * 处理渠道回调
     *
     * @return 处理结果描述（用于写日志与应答）
     */
    @Transactional(rollbackFor = Exception.class)
    public String handleNotify(String channelCode, String rawBody, Map<String, String> headers, String ip) {
        long start = System.currentTimeMillis();
        PayChannel channel = channelRegistry.get(channelCode);
        PayChannel.PayNotifyEvent event = channel.parseNotify(rawBody, headers);

        // 1. 落回调日志（唯一索引防重放）
        boolean duplicated = notifyLogMapper.countByNotifyId(event.notifyId()) > 0;
        PaymentNotifyLog logRow = new PaymentNotifyLog();
        logRow.setPaymentNo(event.paymentNo());
        logRow.setChannel(channelCode);
        logRow.setNotifyType(PaymentNotifyLog.TYPE_PAY);
        logRow.setNotifyId(event.notifyId());
        logRow.setOutTradeNo(event.paymentNo());
        logRow.setChannelOrderNo(event.channelOrderNo());
        logRow.setTradeState(event.tradeState());
        logRow.setAmountFen(event.amount() == null ? null : MoneyUtil.toFen(event.amount()));
        logRow.setHeaders(truncate(headers == null ? "" : headers.toString(), 2000));
        logRow.setRawBody(rawBody);
        logRow.setIp(ip);
        if (duplicated) {
            logRow.setSignVerified(1);
            logRow.setHandleStatus(PaymentNotifyLog.HANDLE_DUPLICATED);
            logRow.setHandleResult("重复通知，已忽略");
            logRow.setHandleMs((int) (System.currentTimeMillis() - start));
            notifyLogMapper.insert(logRow);
            return "SUCCESS(duplicated)";
        }
        notifyLogMapper.insert(logRow);

        // 2. 验签（验签失败绝不入账）
        boolean verified = channel.verifyNotify(event, headers);
        logRow.setSignVerified(verified ? 1 : 0);
        logRow.setVerifyMode(channelCode + "_AUTO");
        if (!verified) {
            notifyLogMapper.markHandled(logRow.getId(), PaymentNotifyLog.HANDLE_FAILED, "验签失败");
            log.error("[支付] 回调验签失败 paymentNo={}, notifyId={}", event.paymentNo(), event.notifyId());
            throw new BizException(ErrorCode.PAY_NOTIFY_VERIFY_FAIL);
        }

        // 3. 定位支付单
        PaymentOrder payment = paymentMapper.selectForUpdate(event.paymentNo());
        if (payment == null) {
            notifyLogMapper.markHandled(logRow.getId(), PaymentNotifyLog.HANDLE_FAILED, "支付单不存在");
            throw new BizException(ErrorCode.PAYMENT_NOT_FOUND);
        }
        paymentMapper.increaseNotifyCount(payment.getId());

        // 4. 统一入账
        boolean changed;
        try {
            changed = handlePaid(payment, event.channelOrderNo(), event.amount());
            String result = changed ? "入账成功" : "已处理过，忽略";
            notifyLogMapper.markHandled(logRow.getId(), PaymentNotifyLog.HANDLE_SUCCESS, result);
            log.info("[支付] 回调处理完成 paymentNo={}, 结果={}, 耗时={}ms",
                    event.paymentNo(), result, System.currentTimeMillis() - start);
            return "SUCCESS";
        } catch (Exception e) {
            notifyLogMapper.markHandled(logRow.getId(), PaymentNotifyLog.HANDLE_FAILED, e.getMessage());
            log.error("[支付] 回调处理失败 paymentNo={}", event.paymentNo(), e);
            throw e;
        }
    }

    /* ==================================================================
     *  查询 / 关单 / 退款
     * ================================================================== */

    public PaymentOrder getByPaymentNo(String paymentNo) {
        PaymentOrder po = paymentMapper.selectByPaymentNo(paymentNo);
        if (po == null) {
            throw new BizException(ErrorCode.PAYMENT_NOT_FOUND);
        }
        return po;
    }

    /** 查询支付结果（前端轮询用，含资产到账明细） */
    public Map<String, Object> queryResult(String paymentNo) {
        PaymentOrder po = getByPaymentNo(paymentNo);
        Map<String, Object> m = new HashMap<>();
        m.put("paymentNo", po.getPaymentNo());
        m.put("bizNo", po.getBizNo());
        m.put("channel", po.getChannel());
        m.put("amount", po.getAmount());
        m.put("status", po.getStatus());
        m.put("statusText", PaymentOrder.statusText(po.getStatus()));
        m.put("paidAt", po.getPaidAt());
        m.put("channelOrderNo", po.getChannelOrderNo());
        m.put("accountCredited", po.getAccountCredited() != null && po.getAccountCredited() == 1);
        m.put("expireTime", po.getExpireTime());
        return m;
    }

    /** 主动查单补偿：渠道返回已支付则补入账 */
    @Transactional(rollbackFor = Exception.class)
    public boolean sync(String paymentNo) {
        PaymentOrder po = getByPaymentNo(paymentNo);
        if (po.isPaid()) {
            return false;
        }
        PayChannel channel = channelRegistry.get(po.getChannel());
        PayChannel.PayQueryResult qr = channel.query(po.getPaymentNo(), po.getChannelOrderNo());
        if (qr.success() && qr.paid()) {
            log.warn("[支付] 主动查单发现已支付，补入账 paymentNo={}", paymentNo);
            return handlePaid(po, qr.channelOrderNo(), qr.amount());
        }
        return false;
    }

    /** 关单 */
    @Transactional(rollbackFor = Exception.class)
    public void close(String paymentNo) {
        PaymentOrder po = getByPaymentNo(paymentNo);
        PayChannel channel = channelRegistry.get(po.getChannel());
        if (po.getStatus() != null && (po.getStatus() == PaymentOrder.STATUS_UNPAID
                || po.getStatus() == PaymentOrder.STATUS_PAYING)) {
            channel.close(po.getPaymentNo(), po.getChannelOrderNo());
            paymentMapper.markClosed(po.getId());
            Order order = orderMapper.selectById(po.getOrderId());
            if (order != null) {
                orderMapper.updateStatusIfPending(order.getId(), Order.STATUS_CANCELLED);
            }
            log.info("[支付] 关单成功 paymentNo={}", paymentNo);
        }
    }

    /** 退款（原路退回）：渠道退款成功后才执行资产冲正 */
    @Transactional(rollbackFor = Exception.class)
    public String refund(String paymentNo, String refundNo, BigDecimal refundAmount, String reason) {
        PaymentOrder po = getByPaymentNo(paymentNo);
        if (!po.isPaid()) {
            throw new BizException(ErrorCode.PAYMENT_STATUS_INVALID, "仅已支付的支付单可退款");
        }
        BigDecimal refunded = MoneyUtil.nullToZero(po.getRefundAmount());
        BigDecimal refundable = MoneyUtil.sub(po.getAmount(), refunded);
        if (MoneyUtil.gt(refundAmount, refundable)) {
            throw new BizException(ErrorCode.PAY_REFUND_EXCEED,
                    "退款金额超过可退金额 " + refundable + " 元");
        }
        PayChannel channel = channelRegistry.get(po.getChannel());
        PayChannel.RefundResult rr = channel.refund(new PayChannel.RefundRequest(
                po.getPaymentNo(), refundNo, po.getChannelOrderNo(), refundAmount, po.getAmount(), reason));
        if (!rr.success()) {
            log.error("[支付] 渠道退款失败 paymentNo={}, 原因={}", paymentNo, rr.errorMsg());
            throw new BizException(ErrorCode.PAY_REFUND_ERROR, rr.errorMsg());
        }
        // 渠道退款成功 → 更新支付单退款状态
        BigDecimal totalRefunded = MoneyUtil.add(refunded, refundAmount);
        int status = MoneyUtil.gte(totalRefunded, po.getAmount())
                ? PaymentOrder.STATUS_REFUNDED : PaymentOrder.STATUS_PARTIAL_REFUND;
        paymentMapper.markRefunded(po.getId(), status, refundAmount, refundNo);
        log.info("[支付] 退款成功 paymentNo={}, refundNo={}, 金额={}, 渠道退款单号={}",
                paymentNo, refundNo, refundAmount, rr.channelRefundNo());
        return rr.channelRefundNo();
    }

    /* ==================================================================
     *  定时任务支撑
     * ================================================================== */

    public java.util.List<PaymentOrder> pendingForSync(int limit) {
        return paymentMapper.selectPendingForSync(LocalDateTime.now().minusHours(2), 0L, limit);
    }

    public java.util.List<PaymentOrder> timeoutOrders(int limit) {
        return paymentMapper.selectTimeout(0L, limit);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
