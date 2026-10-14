package com.company.member.modules.payment.channel.wechat;

import com.company.member.common.cache.CacheService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 微信支付 APIv3 HTTP 客户端
 *
 * <p>只封装本系统用得到的 5 个接口（JSAPI 下单/查单/关单/退款/下载平台证书），
 * 不做通用 SDK——接口少、可控性强，出问题能一眼看到请求与应答。
 *
 * <p><b>平台证书缓存策略</b>：微信要求用平台证书验签回调，证书会轮换。
 * 优先使用 {@code pay.wechat.platform-cert} 手工配置的证书；
 * 未配置时自动调 {@code /v3/certificates} 下载并按序列号缓存 6 小时，
 * 回调头里的 {@code Wechatpay-Serial} 对不上时会强制刷新一次
 * （覆盖「证书刚轮换、缓存还是旧的」这一必然发生的场景）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatPayClient {

    private static final String CACHE_PLATFORM_CERTS = "wxpay:platformcerts";

    private final WechatPayConfig config;
    private final WechatPaySigner signer;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = RestClient.builder().build();

    /** 序列号 → 证书（进程内一级缓存，避免每次回调都反序列化） */
    private final Map<String, X509Certificate> certCache = new ConcurrentHashMap<>();

    /* ==================== 业务接口 ==================== */

    /**
     * JSAPI 统一下单
     *
     * @return 微信原始应答 JSON
     */
    public JsonNode jsapiPrepay(String outTradeNo, String description, BigDecimal amountYuan,
                                String openid, String notifyUrl, String attach) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", config.appId());
        body.put("mchid", config.mchId());
        body.put("description", description);
        body.put("out_trade_no", outTradeNo);
        body.put("notify_url", notifyUrl);
        if (attach != null && !attach.isBlank()) {
            body.put("attach", attach);
        }
        body.put("amount", Map.of("total", toFen(amountYuan), "currency", "CNY"));
        body.put("payer", Map.of("openid", openid));
        return post("/v3/pay/transactions/jsapi", body);
    }

    /** 按商户单号查单 */
    public JsonNode queryByOutTradeNo(String outTradeNo) {
        String url = "/v3/pay/transactions/out-trade-no/" + outTradeNo + "?mchid=" + config.mchId();
        return get(url);
    }

    /** 关闭订单 */
    public JsonNode closeByOutTradeNo(String outTradeNo) {
        String url = "/v3/pay/transactions/out-trade-no/" + outTradeNo + "/close";
        return post(url, Map.of("mchid", config.mchId()));
    }

    /** 申请退款 */
    public JsonNode refund(String outTradeNo, String outRefundNo, BigDecimal refundYuan,
                           BigDecimal totalYuan, String reason, String notifyUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("out_trade_no", outTradeNo);
        body.put("out_refund_no", outRefundNo);
        if (reason != null && !reason.isBlank()) {
            body.put("reason", reason);
        }
        if (notifyUrl != null && !notifyUrl.isBlank()) {
            body.put("notify_url", notifyUrl);
        }
        body.put("amount", Map.of(
                "refund", toFen(refundYuan),
                "total", toFen(totalYuan),
                "currency", "CNY"));
        return post("/v3/refund/domestic/refunds", body);
    }

    /**
     * 取用于验签的平台证书
     *
     * @param serial 回调头 {@code Wechatpay-Serial} 指定的序列号；为空则返回任意一张
     */
    public X509Certificate platformCertificate(String serial) {
        String wanted = serial == null ? "" : serial.trim().toUpperCase();
        if (!wanted.isEmpty() && certCache.containsKey(wanted)) {
            return certCache.get(wanted);
        }
        X509Certificate configured = config.configuredPlatformCert();
        if (configured != null) {
            String s = WechatPaySigner.serialOf(configured);
            certCache.put(s, configured);
            if (wanted.isEmpty() || wanted.equals(s)) {
                return configured;
            }
            log.warn("[微信支付] 回调序列号 {} 与手工配置的证书 {} 不一致，尝试下载平台证书", wanted, s);
        }
        Map<String, X509Certificate> certs = downloadPlatformCertificates(false);
        if (certs.isEmpty()) {
            return null;
        }
        if (wanted.isEmpty()) {
            return certs.values().iterator().next();
        }
        X509Certificate cert = certs.get(wanted);
        if (cert == null) {
            // 证书刚轮换：强制刷新一次再找
            log.warn("[微信支付] 平台证书缓存未命中序列号 {}，强制刷新", wanted);
            cert = downloadPlatformCertificates(true).get(wanted);
        }
        return cert;
    }

    /** 验签（回调/应答通用） */
    public boolean verify(String message, String signature, String serial) {
        X509Certificate cert = platformCertificate(serial);
        if (cert == null) {
            log.warn("[微信支付] 无可用平台证书，无法验签");
            return false;
        }
        PublicKey publicKey = cert.getPublicKey();
        return signer.verify(message, signature, publicKey);
    }

    /** 清空证书的一级（进程内）缓存：换了平台证书或排查验签问题时调用 */
    public void clearCertCache() {
        certCache.clear();
    }

    /* ==================== 内部实现 ==================== */

    /** 下载并解密平台证书，序列号 → 证书 */
    public Map<String, X509Certificate> downloadPlatformCertificates(boolean force) {
        if (!force) {
            // 缓存里存的是「序列号 → Base64(DER)」而不是证书对象：
            // X509Certificate 是抽象类，JSON 读回来只会是 LinkedHashMap，
            // 验签时取出来用就是 ClassCastException —— 表现为「回调一直验签失败」。
            Map<String, String> cached = cacheService.get(CACHE_PLATFORM_CERTS, Map.class);
            if (cached != null && !cached.isEmpty()) {
                Map<String, X509Certificate> restored = new LinkedHashMap<>();
                try {
                    cached.forEach((serial, b64) -> restored.put(serial, WechatPaySigner.decodeCertificate(b64)));
                } catch (Exception e) {
                    log.warn("[微信支付] 缓存平台证书还原失败，改为重新下载：{}", e.getMessage());
                    restored.clear();
                }
                if (!restored.isEmpty()) {
                    certCache.putAll(restored);
                    return restored;
                }
            }
        }
        Map<String, X509Certificate> result = new LinkedHashMap<>();
        try {
            JsonNode resp = get("/v3/certificates");
            JsonNode data = resp.path("data");
            if (!data.isArray()) {
                log.warn("[微信支付] /v3/certificates 应答格式异常：{}", resp);
                return result;
            }
            for (JsonNode item : data) {
                try {
                    JsonNode encrypt = item.path("encrypt_certificate");
                    String pem = signer.decryptResource(config.apiV3Key(),
                            encrypt.path("associated_data").asText(""),
                            encrypt.path("nonce").asText(""),
                            encrypt.path("ciphertext").asText(""));
                    X509Certificate cert = signer.parseCertificate(pem);
                    String serial = WechatPaySigner.serialOf(cert);
                    // 序列号以回调头为准，这里用证书里的序列号；也索引一次微信返回的 serial_no
                    result.put(serial, cert);
                    String declared = item.path("serial_no").asText("");
                    if (!declared.isEmpty()) {
                        result.put(declared.toUpperCase(), cert);
                    }
                } catch (Exception e) {
                    log.warn("[微信支付] 单张平台证书解析失败：{}", e.getMessage());
                }
            }
            certCache.putAll(result);
            Map<String, String> encodable = new LinkedHashMap<>();
            result.forEach((serial, cert) -> encodable.put(serial, WechatPaySigner.encodeCertificate(cert)));
            cacheService.set(CACHE_PLATFORM_CERTS, encodable, 6, TimeUnit.HOURS);
            log.info("[微信支付] 平台证书已更新，共 {} 张", result.size());
        } catch (Exception e) {
            log.warn("[微信支付] 下载平台证书失败：{}", e.getMessage());
        }
        return result;
    }

    private JsonNode get(String urlWithQuery) {
        return exchange("GET", urlWithQuery, null);
    }

    private JsonNode post(String urlWithQuery, Object body) {
        String json = toJson(body);
        return exchange("POST", urlWithQuery, json);
    }

    private JsonNode exchange(String method, String urlWithQuery, String body) {
        String authorization = signer.buildAuthorization(config.mchId(), config.mchSerialNo(),
                config.privateKey(), method, urlWithQuery, body);
        String fullUrl = config.apiBase() + urlWithQuery;
        try {
            String resp;
            if ("GET".equals(method)) {
                resp = restClient.get()
                        .uri(fullUrl)
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .header("User-Agent", "member-system/1.0")
                        .retrieve()
                        .body(String.class);
            } else {
                resp = restClient.post()
                        .uri(fullUrl)
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .header("User-Agent", "member-system/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
            }
            return read(resp);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // 微信的错误信息（code/message）在响应体里，必须带出来，否则排查无从下手
            String errBody = e.getResponseBodyAsString();
            log.warn("[微信支付] {} {} 返回 {}：{}", method, urlWithQuery, e.getStatusCode(), errBody);
            throw new WechatPayException(e.getStatusCode().value(), parseError(errBody));
        } catch (WechatPayException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[微信支付] {} {} 请求异常：{}", method, urlWithQuery, e.getMessage());
            throw new WechatPayException(-1, "微信支付接口调用失败：" + e.getMessage());
        }
    }

    /** 金额转「分」：微信只接受整数分，四舍五入避免 0.005 这类边界丢钱 */
    public static long toFen(BigDecimal yuan) {
        if (yuan == null) {
            return 0L;
        }
        return yuan.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 「分」转元 */
    public static BigDecimal toYuan(long fen) {
        return BigDecimal.valueOf(fen).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }

    private String parseError(String body) {
        try {
            JsonNode node = objectMapper.readTree(body == null ? "{}" : body);
            return node.path("code").asText("") + " " + node.path("message").asText("");
        } catch (Exception e) {
            return body == null ? "" : body;
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("序列化请求体失败：" + e.getMessage(), e);
        }
    }

    private JsonNode read(String body) {
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new WechatPayException(-1, "解析微信应答失败：" + body);
        }
    }

    /** 微信支付接口错误 */
    public static class WechatPayException extends RuntimeException {
        private final int httpStatus;

        public WechatPayException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }

        public int getHttpStatus() {
            return httpStatus;
        }
    }
}
