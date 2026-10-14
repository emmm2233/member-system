package com.company.member.modules.payment.channel.wechat;

import com.company.member.modules.payment.channel.DiagnosableChannel;
import com.company.member.modules.payment.channel.PayChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信支付渠道（APIv3 · JSAPI）
 *
 * <p><b>就绪检测</b>：{@link #isReady()} 不只看参数是否填了，还会校验私钥能否解析、
 * APIv3 密钥是否 32 位、回调地址是否 HTTPS——这些「配了但不合规」的情况
 * 必须在管理端暴露，而不是等第一笔真实支付失败。
 *
 * <p><b>未就绪时的行为</b>：{@link PayChannelRegistry} 会把本渠道从可选列表里摘掉，
 * 并在下单时抛 {@code PAY_CHANNEL_NOT_READY} 且带上缺失项，
 * 前端据此提示「请先在系统参数里配置微信支付」。
 *
 * <p><b>openid 来源</b>：JSAPI 下单必须带 payer.openid。会员在小程序登录时
 * 已经把 openid 写入 {@code m_member.openid}，由 {@code PayService} 传入；
 * 为空时直接失败并提示先绑定微信，避免拿一个必然失败的请求去打扰微信接口。
 *
 * <p><b>回调报文</b>：微信的 resource 是 AES-256-GCM 加密的，必须先验签再解密——
 * 顺序不能反，否则等于给了攻击者一个免费的解密 oracle。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatPayChannel implements PayChannel, DiagnosableChannel {

    public static final String CODE = "WECHAT";

    private final WechatPayConfig config;
    private final WechatPayClient client;
    private final WechatPaySigner signer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String channelCode() {
        return CODE;
    }

    @Override
    public boolean isReady() {
        return config.ready();
    }

    @Override
    public List<String> missingConfigs() {
        return config.missingConfigs();
    }

    /** 管理端诊断：把密钥类配置的「是否已配」和缺失项一次说清 */
    @Override
    public Map<String, Object> diagnose() {
        return config.describe();
    }

    /** 本地自检（不联网）：配置齐备性 + 私钥自签自验 + APIv3 密钥长度 */
    public Map<String, Object> selfTest() {
        return config.selfTest();
    }

    /** 改完密钥后刷新派生缓存（私钥解析结果与平台证书都带缓存） */
    public void refreshCache() {
        config.invalidate();
        client.clearCertCache();
        log.info("[支付-微信] 派生缓存已刷新");
    }

    /* ==================== 下单 ==================== */

    @Override
    public PrepayResult prepay(PayRequest request) {
        if (!isReady()) {
            return PrepayResult.fail("CHANNEL_NOT_READY", "微信支付未配置完成：" + String.join("；", missingConfigs()));
        }
        if (request.openid() == null || request.openid().isBlank()) {
            return PrepayResult.fail("OPENID_MISSING", "会员未绑定微信，无法使用微信支付");
        }
        if (request.amount() == null || request.amount().signum() <= 0) {
            return PrepayResult.fail("AMOUNT_INVALID", "支付金额必须大于 0");
        }
        String notifyUrl = request.notifyUrl() == null || request.notifyUrl().isBlank()
                ? config.notifyUrl() : request.notifyUrl();
        String description = request.subject() == null || request.subject().isBlank()
                ? "会员消费" : request.subject();
        try {
            JsonNode resp = client.jsapiPrepay(request.paymentNo(), truncate(description, 127),
                    request.amount(), request.openid(), notifyUrl, request.attach());
            String prepayId = resp.path("prepay_id").asText("");
            if (prepayId.isEmpty()) {
                return PrepayResult.fail("NO_PREPAY_ID", "微信未返回 prepay_id：" + resp);
            }
            Map<String, String> params = buildJsapiParams(prepayId);
            log.info("[支付-微信] 下单成功 paymentNo={}, 金额={}", request.paymentNo(), request.amount());
            return PrepayResult.ok(prepayId, params);
        } catch (WechatPayClient.WechatPayException e) {
            log.warn("[支付-微信] 下单失败 paymentNo={}, {}", request.paymentNo(), e.getMessage());
            return PrepayResult.fail("WXPAY_ERROR", e.getMessage());
        } catch (Exception e) {
            log.warn("[支付-微信] 下单异常 paymentNo={}", request.paymentNo(), e);
            return PrepayResult.fail("WXPAY_EXCEPTION", e.getMessage());
        }
    }

    /** 组装小程序 {@code wx.requestPayment} 所需参数（第二段签名） */
    private Map<String, String> buildJsapiParams(String prepayId) {
        String timeStamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonceStr = WechatPaySigner.randomNonce(32);
        String packageStr = "prepay_id=" + prepayId;
        String paySign = signer.buildPaySign(config.appId(), timeStamp, nonceStr, packageStr,
                config.privateKey());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("appId", config.appId());
        params.put("timeStamp", timeStamp);
        params.put("nonceStr", nonceStr);
        params.put("package", packageStr);
        params.put("signType", "RSA");
        params.put("paySign", paySign);
        // 便于小程序端在开发者工具里识别渠道
        params.put("channel", CODE);
        return params;
    }

    /* ==================== 查单 / 关单 ==================== */

    @Override
    public PayQueryResult query(String paymentNo, String channelOrderNo) {
        if (!isReady()) {
            return PayQueryResult.fail("微信支付未配置完成");
        }
        try {
            JsonNode resp = client.queryByOutTradeNo(paymentNo);
            String tradeState = resp.path("trade_state").asText("");
            if ("SUCCESS".equals(tradeState)) {
                long total = resp.path("amount").path("total").asLong(0);
                return new PayQueryResult(true, true, resp.path("transaction_id").asText(""),
                        tradeState, WechatPayClient.toYuan(total),
                        resp.path("payer").path("openid").asText(null), null);
            }
            return PayQueryResult.unpaid(tradeState.isEmpty() ? "UNKNOWN" : tradeState);
        } catch (WechatPayClient.WechatPayException e) {
            // 订单不存在（ORDER_NOT_EXIST）等属于正常业务态，不当成故障
            if (e.getMessage() != null && e.getMessage().contains("ORDER_NOT_EXIST")) {
                return PayQueryResult.unpaid("NOTPAY");
            }
            return PayQueryResult.fail(e.getMessage());
        } catch (Exception e) {
            return PayQueryResult.fail(e.getMessage());
        }
    }

    @Override
    public void close(String paymentNo, String channelOrderNo) {
        if (!isReady()) {
            return;
        }
        try {
            client.closeByOutTradeNo(paymentNo);
            log.info("[支付-微信] 关单成功 paymentNo={}", paymentNo);
        } catch (Exception e) {
            // 关单失败不影响本地关单：本地状态才是权威，微信侧超时未支付会自动关闭
            log.warn("[支付-微信] 关单失败（本地仍会关闭）paymentNo={}, 原因={}", paymentNo, e.getMessage());
        }
    }

    /* ==================== 退款 ==================== */

    @Override
    public RefundResult refund(RefundRequest request) {
        if (!isReady()) {
            return RefundResult.fail("CHANNEL_NOT_READY", "微信支付未配置完成");
        }
        try {
            JsonNode resp = client.refund(request.paymentNo(), request.refundNo(),
                    request.refundAmount(), request.totalAmount(), request.reason(), null);
            String refundId = resp.path("refund_id").asText("");
            String status = resp.path("status").asText("PROCESSING");
            if (refundId.isEmpty()) {
                return RefundResult.fail("NO_REFUND_ID", "微信未返回 refund_id：" + resp);
            }
            log.info("[支付-微信] 退款受理 paymentNo={}, refundNo={}, 状态={}",
                    request.paymentNo(), request.refundNo(), status);
            // 微信退款是异步的，受理成功不代表到账；状态原样回传，由对账任务兜底确认
            return new RefundResult(true, refundId, status, null, null);
        } catch (Exception e) {
            log.warn("[支付-微信] 退款失败 paymentNo={}, 原因={}", request.paymentNo(), e.getMessage());
            return RefundResult.fail("WXPAY_REFUND_ERROR", e.getMessage());
        }
    }

    /* ==================== 回调 ==================== */

    /**
     * 解析回调报文
     *
     * <p>只做「结构解析 + 解密」，<b>不做验签</b>——验签在 {@link #verifyNotify} 里，
     * 由 PayService 控制调用顺序（先验签，后信任内容）。
     * 但为了不把密文留给上层，这里会解密 resource；若解密失败则标记 {@code decrypted=false}，
     * 上层的验签失败分支会直接拒绝。
     */
    @Override
    public PayNotifyEvent parseNotify(String rawBody, Map<String, String> headers) {
        if (rawBody == null || rawBody.isBlank()) {
            return new PayNotifyEvent(null, null, null, null, null, null, null, rawBody, false);
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String notifyId = root.path("id").asText("");
            String eventType = root.path("event_type").asText("");
            JsonNode encrypt = root.path("resource");
            String ciphertext = encrypt.path("ciphertext").asText("");
            if (ciphertext.isEmpty()) {
                log.warn("[支付-微信] 回调缺少 resource.ciphertext，原始报文={}", rawBody);
                return new PayNotifyEvent(notifyId, eventType, null, null, null, null, null, rawBody, false);
            }
            String plain = signer.decryptResource(config.apiV3Key(),
                    encrypt.path("associated_data").asText(""),
                    encrypt.path("nonce").asText(""),
                    ciphertext);
            JsonNode res = objectMapper.readTree(plain);
            String outTradeNo = res.path("out_trade_no").asText("");
            String transactionId = res.path("transaction_id").asText("");
            String tradeState = res.path("trade_state").asText("");
            long total = res.path("amount").path("total").asLong(0);
            String openid = res.path("payer").path("openid").asText(null);
            return new PayNotifyEvent(notifyId, eventType, outTradeNo, transactionId, tradeState,
                    WechatPayClient.toYuan(total), openid, plain, true);
        } catch (Exception e) {
            log.warn("[支付-微信] 回调解析失败：{}，原始报文={}", e.getMessage(), rawBody);
            return new PayNotifyEvent(null, null, null, null, null, null, null, rawBody, false);
        }
    }

    /**
     * 回调验签
     *
     * <p>微信回调的签名串是 {@code timestamp\nnonce\nbody\n}，
     * 用的是<b>平台证书</b>的私钥签名，因此必须用平台证书公钥验证。
     * 这里刻意重新拼装签名串（而不是复用解析出的字段），保证验的就是原始报文体。
     */
    @Override
    public boolean verifyNotify(PayNotifyEvent event, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            log.warn("[支付-微信] 回调缺少请求头，验签失败");
            return false;
        }
        String timestamp = header(headers, "Wechatpay-Timestamp");
        String nonce = header(headers, "Wechatpay-Nonce");
        String signature = header(headers, "Wechatpay-Signature");
        String serial = header(headers, "Wechatpay-Serial");
        if (timestamp == null || nonce == null || signature == null) {
            log.warn("[支付-微信] 回调缺少 Wechatpay-Timestamp/Nonce/Signature，验签失败");
            return false;
        }
        // 防重放：超过 5 分钟的回调一律拒绝（微信官方建议值）
        try {
            long ts = Long.parseLong(timestamp.trim());
            long skew = Math.abs(System.currentTimeMillis() / 1000 - ts);
            if (skew > 300) {
                log.warn("[支付-微信] 回调时间戳偏差 {} 秒，拒绝（防重放）", skew);
                return false;
            }
        } catch (NumberFormatException e) {
            log.warn("[支付-微信] 回调时间戳非法：{}", timestamp);
            return false;
        }
        String body = event.rawPayload();
        String message = WechatPaySigner.buildNotifyMessage(timestamp, nonce, body);
        boolean ok = client.verify(message, signature, serial);
        if (!ok) {
            log.warn("[支付-微信] 回调验签失败 notifyId={}, serial={}", event.notifyId(), serial);
        }
        return ok;
    }

    /* ==================== 工具 ==================== */

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 便于对账任务回填渠道单号 */
    public Map<String, String> emptyPayParams() {
        return new HashMap<>();
    }

    /** 诊断用：当前生效的配置摘要（不含密钥明文） */
    public Map<String, Object> configSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appId", config.appId());
        m.put("mchId", config.mchId());
        m.put("serialNo", config.mchSerialNo());
        m.put("apiBase", config.apiBase());
        m.put("notifyUrl", config.notifyUrl());
        m.put("platformCert", config.platformCertRaw().isEmpty() ? "自动下载" : "手动配置");
        return m;
    }

    /** 金额一致性校验：微信回调金额必须与本地支付单一致（防止金额被篡改） */
    public boolean amountMatches(BigDecimal local, BigDecimal notified) {
        if (local == null || notified == null) {
            return false;
        }
        return local.setScale(2, java.math.RoundingMode.HALF_UP)
                .compareTo(notified.setScale(2, java.math.RoundingMode.HALF_UP)) == 0;
    }
}
