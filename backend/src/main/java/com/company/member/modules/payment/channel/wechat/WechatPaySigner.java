package com.company.member.modules.payment.channel.wechat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * 微信支付 APIv3 密码学工具
 *
 * <p>封装四件事，全部围绕<b>不自己发明密码学</b>这一原则：
 * <ol>
 *   <li><b>请求签名</b>：构造待签名串 {@code METHOD\nURL\ntimestamp\nnonce\nbody\n}，用商户私钥 SHA256withRSA 签名</li>
 *   <li><b>应答/回调验签</b>：用微信支付平台证书公钥验证 {@code timestamp\nnonce\nbody\n} 的签名</li>
 *   <li><b>回调报文解密</b>：APIv3 密钥做 AES-256-GCM 解密（微信只加密回调里的 resource 字段）</li>
 *   <li><b>PEM 解析</b>：商户私钥（PKCS#8）与平台证书</li>
 * </ol>
 *
 * <p><b>为什么单独一个类</b>：这些逻辑一旦出错就是「签名不通过」或「验签被绕过」，
 * 属于资金安全关键路径，必须能被单元测试直接覆盖，不能埋在 Channel 里。
 */
@Slf4j
@Component
public class WechatPaySigner {

    private static final String SIGN_ALGORITHM = "SHA256withRSA";
    private static final String NONCE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /**
     * 构造 APIv3 请求签名串
     *
     * <p>格式由微信规定，任何一处不一致（少一个 \n、URL 带了 query 之外的东西）
     * 都会导致 401 SIGN_ERROR。注意 URL 必须带 query string。
     */
    public static String buildMessage(String method, String urlWithQuery, long timestamp,
                                      String nonce, String body) {
        return method.toUpperCase() + "\n"
                + urlWithQuery + "\n"
                + timestamp + "\n"
                + nonce + "\n"
                + (body == null ? "" : body) + "\n";
    }

    /** 构造 Authorization 头 */
    public String buildAuthorization(String mchId, String serialNo, PrivateKey privateKey,
                                     String method, String urlWithQuery, String body) {
        long timestamp = System.currentTimeMillis() / 1000;
        String nonce = randomNonce(32);
        String message = buildMessage(method, urlWithQuery, timestamp, nonce, body);
        String signature = sign(message, privateKey);
        return "WECHATPAY2-SHA256-RSA2048 "
                + "mchid=\"" + mchId + "\","
                + "nonce_str=\"" + nonce + "\","
                + "signature=\"" + signature + "\","
                + "timestamp=\"" + timestamp + "\","
                + "serial_no=\"" + serialNo + "\"";
    }

    /** 构造小程序的 {@code paySign}（JSAPI 调起支付的第二段签名） */
    public String buildPaySign(String appId, String timeStamp, String nonceStr,
                               String packageStr, PrivateKey privateKey) {
        String message = appId + "\n" + timeStamp + "\n" + nonceStr + "\n" + packageStr + "\n";
        return sign(message, privateKey);
    }

    /** 构造回调验签串 */
    public static String buildNotifyMessage(String timestamp, String nonce, String body) {
        return timestamp + "\n" + nonce + "\n" + (body == null ? "" : body) + "\n";
    }

    /** SHA256withRSA 签名，返回 Base64 */
    public String sign(String message, PrivateKey privateKey) {
        try {
            Signature signer = Signature.getInstance(SIGN_ALGORITHM);
            signer.initSign(privateKey);
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException("微信支付签名失败：" + e.getMessage(), e);
        }
    }

    /** 用平台证书公钥验签 */
    public boolean verify(String message, String signatureBase64, PublicKey publicKey) {
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance(SIGN_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            log.warn("[微信支付] 验签异常：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 解密回调 resource（AES-256-GCM）
     *
     * @param apiV3Key        APIv3 密钥（32 字节）
     * @param associatedData  微信给的附加数据，可能为空
     * @param nonce           随机串（12 字节）
     * @param ciphertext      Base64 密文（末尾 16 字节是 GCM tag）
     */
    public String decryptResource(String apiV3Key, String associatedData, String nonce, String ciphertext) {
        if (apiV3Key == null || apiV3Key.length() != 32) {
            throw new IllegalStateException("APIv3 密钥必须为 32 位字符串，当前长度="
                    + (apiV3Key == null ? 0 : apiV3Key.length()));
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec key = new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES");
            GCMParameterSpec spec = new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8));
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            if (associatedData != null && !associatedData.isEmpty()) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("微信支付回调解密失败：" + e.getMessage(), e);
        }
    }

    /* ==================== PEM 解析 ==================== */

    /**
     * 解析商户私钥（PKCS#8 PEM）
     *
     * <p>同时兼容 PKCS#1（{@code BEGIN RSA PRIVATE KEY}）：虽然微信要求 PKCS#8，
     * 但运维从旧系统导出的常常是 PKCS#1，直接报「格式错误」会让人排查很久，
     * 这里给出明确提示。
     */
    public PrivateKey parsePrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("商户私钥为空");
        }
        String normalized = pem.replace("\\n", "\n").trim();
        if (normalized.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalStateException(
                    "商户私钥是 PKCS#1 格式，微信支付要求 PKCS#8。请执行 "
                            + "openssl pkcs8 -topk8 -inform PEM -in apiclient_key.pem -outform PEM -nocrypt 转换后再配置");
        }
        String base64 = normalized
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("商户私钥解析失败：" + e.getMessage(), e);
        }
    }

    /** 解析平台证书（PEM），返回证书本身（需要拿公钥与序列号） */
    public X509Certificate parseCertificate(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("平台证书为空");
        }
        String normalized = pem.replace("\\n", "\n").trim();
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(
                    new java.io.ByteArrayInputStream(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("平台证书解析失败：" + e.getMessage(), e);
        }
    }

    /** 证书序列号（大写十六进制，微信回调头里就是这种格式） */
    public static String serialOf(X509Certificate cert) {
        return cert.getSerialNumber().toString(16).toUpperCase();
    }

    /* ==================== 缓存用的编解码 ====================
     *
     * 为什么需要这一组方法：PrivateKey / X509Certificate 是**接口与抽象类**，
     * 没法走 JSON 还原（切到 Redis 后 set 会直接失败，把「配好商户号也开不了支付」
     * 这种最不该出现的故障带进生产 —— 实测见 docs/14 §4.4）。
     * 缓存里只放字符串，读的时候再还原成对象：
     *   PrivateKey  → "算法:Base64(PKCS#8)"
     *   X509Certificate → "Base64(DER)"
     * 好处不只是能存：在 Redis 里直接可读，排查时不用再猜缓存里是什么。
     */

    /** 私钥 → 缓存字符串 */
    public static String encodeKey(PrivateKey key) {
        return key.getAlgorithm() + ":" + Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /** 缓存字符串 → 私钥（失败抛异常，由调用方决定退回重新解析） */
    public static PrivateKey decodeKey(String encoded) {
        int colon = encoded.indexOf(':');
        String algorithm = colon > 0 ? encoded.substring(0, colon) : "RSA";
        try {
            byte[] der = Base64.getDecoder().decode(encoded.substring(colon + 1));
            return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("缓存私钥还原失败：" + e.getMessage(), e);
        }
    }

    /** 证书 → 缓存字符串（DER 的 Base64） */
    public static String encodeCertificate(X509Certificate cert) {
        try {
            return Base64.getEncoder().encodeToString(cert.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("证书编码失败：" + e.getMessage(), e);
        }
    }

    /** 缓存字符串 → 证书 */
    public static X509Certificate decodeCertificate(String encoded) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(
                    new java.io.ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
        } catch (Exception e) {
            throw new IllegalStateException("缓存证书还原失败：" + e.getMessage(), e);
        }
    }

    /** 随机串（微信要求不超过 32 位） */
    public static String randomNonce(int length) {
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(NONCE_CHARS.charAt(random.nextInt(NONCE_CHARS.length())));
        }
        return sb.toString();
    }
}
