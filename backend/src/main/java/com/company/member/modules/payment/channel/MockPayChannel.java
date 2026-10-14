package com.company.member.modules.payment.channel;

import com.company.member.common.util.BizNoGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 模拟支付渠道（开发/测试用）
 *
 * <p>无需商户资质即可跑通「下单 → 调起支付 → 回调 → 验签 → 入账 → 通知」全链路。
 * 前端根据 {@code payParams.mock=true} 弹出模拟支付确认框，
 * 用户点确认后调用 {@code /api/mp/pay/orders/{paymentNo}/mock-success}。
 *
 * <p>生产环境（profile=prod）不会注册本渠道。
 */
@Slf4j
@Component
public class MockPayChannel implements PayChannel {

    public static final String CODE = "MOCK";

    /** 模拟验签密钥（仅用于本地联调） */
    private static final String MOCK_SIGN_SECRET = "mock-pay-secret";

    private final BizNoGenerator noGenerator;

    @Value("${member.pay.mock-enabled:false}")
    private boolean mockEnabled;

    @Value("${member.pay.mock-auto-success-seconds:5}")
    private int autoSuccessSeconds;

    public MockPayChannel(BizNoGenerator noGenerator) {
        this.noGenerator = noGenerator;
    }

    @Override
    public String channelCode() {
        return CODE;
    }

    @Override
    public boolean isReady() {
        return mockEnabled;
    }

    @Override
    public List<String> missingConfigs() {
        return mockEnabled ? List.of() : List.of("member.pay.mock-enabled=true");
    }

    @Override
    public PrepayResult prepay(PayRequest request) {
        String prepayId = "mock_prepay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        Map<String, String> params = new HashMap<>();
        params.put("mock", "true");
        params.put("prepayId", prepayId);
        params.put("paymentNo", request.paymentNo());
        params.put("amount", request.amount() == null ? "0.00" : request.amount().toPlainString());
        params.put("autoSuccessSeconds", String.valueOf(autoSuccessSeconds));
        params.put("successApi", "/api/mp/pay/orders/" + request.paymentNo() + "/mock-success");
        params.put("tip", "当前为模拟支付环境，点击确认即视为支付成功");
        log.info("[支付-Mock] 统一下单 paymentNo={}, 金额={}", request.paymentNo(), request.amount());
        return PrepayResult.ok(prepayId, params);
    }

    @Override
    public PayQueryResult query(String paymentNo, String channelOrderNo) {
        // Mock 渠道无外部状态，查单始终返回未支付，由支付单自身状态决定
        return PayQueryResult.unpaid("NOTPAY");
    }

    @Override
    public void close(String paymentNo, String channelOrderNo) {
        log.info("[支付-Mock] 关单 paymentNo={}", paymentNo);
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        String channelRefundNo = "mock_refund_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        log.info("[支付-Mock] 退款 paymentNo={}, refundNo={}, 金额={}",
                request.paymentNo(), request.refundNo(), request.refundAmount());
        return RefundResult.ok(channelRefundNo);
    }

    @Override
    public PayNotifyEvent parseNotify(String rawBody, Map<String, String> headers) {
        // Mock 回调体格式：paymentNo=xxx&notifyId=xxx&tradeState=SUCCESS&amountFen=100
        String paymentNo = param(rawBody, "paymentNo");
        String notifyId = param(rawBody, "notifyId");
        if (notifyId == null || notifyId.isBlank()) {
            notifyId = "mock_" + paymentNo + "_" + System.currentTimeMillis();
        }
        String tradeState = param(rawBody, "tradeState");
        String amountFen = param(rawBody, "amountFen");
        String channelOrderNo = param(rawBody, "channelOrderNo");
        if (channelOrderNo == null || channelOrderNo.isBlank()) {
            channelOrderNo = "mock_trade_" + System.currentTimeMillis();
        }
        BigDecimal amount = amountFen == null ? BigDecimal.ZERO
                : new BigDecimal(amountFen).movePointLeft(2);
        return new PayNotifyEvent(notifyId, "TRANSACTION.SUCCESS", paymentNo, channelOrderNo,
                tradeState == null ? "SUCCESS" : tradeState, amount, null, rawBody, true);
    }

    @Override
    public boolean verifyNotify(PayNotifyEvent event, Map<String, String> headers) {
        // Mock 用请求头 X-Mock-Sign 校验，模拟真实渠道的签名校验
        if (headers == null || headers.isEmpty()) {
            log.warn("[支付-Mock] 回调缺少签名头，验签失败");
            return false;
        }
        String sign = null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if ("x-mock-sign".equalsIgnoreCase(e.getKey())) {
                sign = e.getValue();
                break;
            }
        }
        if (sign == null) {
            log.warn("[支付-Mock] 回调缺少 X-Mock-Sign，验签失败");
            return false;
        }
        boolean ok = MOCK_SIGN_SECRET.equals(sign);
        if (!ok) {
            log.warn("[支付-Mock] 验签失败，收到的签名={}", sign);
        }
        return ok;
    }

    /** 生成 Mock 回调请求头（供调试接口与测试使用） */
    public static Map<String, String> mockSignHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("X-Mock-Sign", MOCK_SIGN_SECRET);
        return h;
    }

    private String param(String body, String key) {
        if (body == null || body.isBlank()) {
            return null;
        }
        for (String pair : body.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && pair.substring(0, idx).trim().equals(key)) {
                return pair.substring(idx + 1).trim();
            }
        }
        return null;
    }
}
