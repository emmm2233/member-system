package com.company.member.modules.payment.channel.wechat;

import com.company.member.common.cache.CacheService;
import com.company.member.modules.system.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信支付商户参数
 *
 * <p>所有参数来自 {@code m_sys_config}，避免把商户号/密钥写死在代码或配置文件里。
 * 私钥与平台证书既可以<b>直接填 PEM 内容</b>（换行写 {@code \n}），
 * 也可以填<b>文件路径</b>——后者更适合生产（密钥不进数据库、便于权限管控）。
 *
 * <p>私钥解析结果做进程内缓存：RSA 解析不便宜，而每个请求都要用它签名。
 * 配置变更时由 {@link #invalidate()} 清掉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatPayConfig {

    private static final String CACHE_KEY_PRIVATE_KEY = "wxpay:privatekey";
    private static final String CACHE_KEY_PLATFORM_CERT = "wxpay:platformcert";
    private static final String CACHE_KEY_PLATFORM_CERTS = "wxpay:platformcerts";

    private final ConfigService configService;
    private final WechatPaySigner signer;
    private final CacheService cacheService;

    /* ==================== 原始配置 ==================== */

    /** 商户号 */
    public String mchId() {
        return configService.getString("pay.wechat.mch-id", "").trim();
    }

    /** 商户 API 证书序列号 */
    public String mchSerialNo() {
        return configService.getString("pay.wechat.mch-serial-no", "").trim();
    }

    /** 商户私钥：PEM 内容（换行用 \n）或文件路径 */
    public String privateKeyRaw() {
        return configService.getString("pay.wechat.private-key", "").trim();
    }

    /** APIv3 密钥（32 位） */
    public String apiV3Key() {
        return configService.getString("pay.wechat.apiv3-key", "").trim();
    }

    /** 支付用 AppID：留空则复用小程序 AppID */
    public String appId() {
        String v = configService.getString("pay.wechat.appid", "").trim();
        return v.isEmpty() ? configService.getString("wx.miniapp.appid", "").trim() : v;
    }

    /** 支付回调地址：留空则复用全局 pay.notify-url */
    public String notifyUrl() {
        String v = configService.getString("pay.wechat.notify-url", "").trim();
        return v.isEmpty() ? configService.payNotifyUrl() : v;
    }

    /** 接口基址（测试环境可用 https://api.mch.weixin.qq.com 的沙箱域名） */
    public String apiBase() {
        String v = configService.getString("pay.wechat.api-base", "").trim();
        return v.isEmpty() ? "https://api.mch.weixin.qq.com" : v.replaceAll("/+$", "");
    }

    /** 平台证书：PEM 内容或文件路径；留空则运行时通过 /v3/certificates 自动下载 */
    public String platformCertRaw() {
        return configService.getString("pay.wechat.platform-cert", "").trim();
    }

    /* ==================== 派生与校验 ==================== */

    /**
     * 商户私钥对象（带缓存）
     *
     * <p><b>缓存键必须带上配置值的指纹</b>：早期版本用固定键，导致「换了私钥但
     * 解析结果还是旧的」——自检报通过、真实下单却签名失败，是极难排查的坑。
     * 指纹用 hashCode 即可（这里只用于区分缓存，不承担安全职责）。
     *
     * <p><b>缓存里放的是 Base64 字符串而不是 PrivateKey 对象</b>：PrivateKey 是接口，
     * JSON 无法还原类型 —— 用 Redis 时 {@code set} 会直接失败，而调用方
     * （{@code missingConfigs()} 的私钥可解析性校验）会把它报成「私钥无法解析」，
     * 于是「商户号配好了却开不了在线支付」。实测见 docs/14 §4.4。
     */
    public PrivateKey privateKey() {
        String raw = privateKeyRaw();
        String cacheKey = CACHE_KEY_PRIVATE_KEY + ":" + Integer.toHexString(raw.hashCode());
        String cached = cacheService.get(cacheKey, String.class);
        if (cached != null && !cached.isBlank()) {
            try {
                return WechatPaySigner.decodeKey(cached);
            } catch (Exception e) {
                // 缓存内容坏了不该让业务挂掉：丢缓存重新解析即可
                log.warn("[微信支付] 缓存私钥还原失败，改为重新解析：{}", e.getMessage());
            }
        }
        PrivateKey key = signer.parsePrivateKey(resolveSecret(raw));
        cacheService.set(cacheKey, WechatPaySigner.encodeKey(key), 6, java.util.concurrent.TimeUnit.HOURS);
        return key;
    }

    /** 是否所有必填参数齐备且私钥可解析 */
    public boolean ready() {
        return missingConfigs().isEmpty();
    }

    /**
     * 缺失/非法的配置项
     *
     * <p>刻意做「值校验」而不只是「非空校验」：私钥格式错、APIv3 密钥不是 32 位，
     * 都属于「配了但没用」的情况，必须在这里就暴露给管理端，而不是等第一笔支付失败。
     */
    public List<String> missingConfigs() {
        List<String> missing = new ArrayList<>();
        if (mchId().isEmpty()) {
            missing.add("pay.wechat.mch-id（商户号）");
        }
        if (mchSerialNo().isEmpty()) {
            missing.add("pay.wechat.mch-serial-no（商户证书序列号）");
        }
        if (appId().isEmpty()) {
            missing.add("pay.wechat.appid 或 wx.miniapp.appid（AppID）");
        }
        String key = apiV3Key();
        if (key.isEmpty()) {
            missing.add("pay.wechat.apiv3-key（APIv3 密钥）");
        } else if (key.length() != 32) {
            missing.add("pay.wechat.apiv3-key 必须为 32 位（当前 " + key.length() + " 位）");
        }
        if (privateKeyRaw().isEmpty()) {
            missing.add("pay.wechat.private-key（商户私钥）");
        } else {
            try {
                privateKey();
            } catch (Exception e) {
                missing.add("pay.wechat.private-key 无法解析：" + e.getMessage());
            }
        }
        if (notifyUrl().isEmpty()) {
            missing.add("pay.notify-url（回调地址，需公网 HTTPS）");
        } else if (!notifyUrl().startsWith("https://")) {
            missing.add("pay.notify-url 必须是 https 地址（微信支付强制要求）");
        }
        return missing;
    }

    /** 配置诊断（管理端展示，密钥类只回显「是否已配置」） */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channel", "WECHAT");
        m.put("ready", ready());
        m.put("missing", missingConfigs());
        m.put("mchId", mchId());
        m.put("mchSerialNo", mchSerialNo());
        m.put("appId", appId());
        m.put("apiBase", apiBase());
        m.put("notifyUrl", notifyUrl());
        m.put("privateKeyConfigured", !privateKeyRaw().isEmpty());
        m.put("apiV3KeyConfigured", !apiV3Key().isEmpty());
        m.put("platformCertConfigured", !platformCertRaw().isEmpty());
        m.put("platformCertMode", platformCertRaw().isEmpty() ? "自动下载（/v3/certificates）" : "手动配置");
        return m;
    }

    /** 配置变更后清掉派生缓存 */
    public void invalidate() {
        // 私钥缓存键带配置指纹，改值自然失效；这里只需清平台证书下载缓存
        cacheService.delete(CACHE_KEY_PLATFORM_CERTS);
    }

    /**
     * 本地自检（不联网）
     *
     * <p>回答运维最关心的三个问题：<b>参数齐不齐、私钥能不能用、密钥长度对不对</b>。
     *
     * <p>「私钥能不能用」不是简单看解析成功，而是做一次<b>自签自验</b>：
     * 从私钥里取出 modulus/publicExponent 构造对应公钥，签一段固定消息再用公钥验签。
     * 这样能一次性排除「私钥与商户证书不匹配」「私钥被截断」「复制时带上了多余字符」
     * 这些只有到真实下单才会暴露、且错误信息极不友好的问题。
     *
     * <p>刻意不做网络调用：自检要能在没有外网、微信接口抖动时随时跑。
     */
    public Map<String, Object> selfTest() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();

        // 1. 配置齐备性
        List<String> missing = missingConfigs();
        out.put("missingConfigs", missing);
        if (!missing.isEmpty()) {
            problems.addAll(missing);
        }

        // 2. 私钥自签自验
        Map<String, Object> keyCheck = new LinkedHashMap<>();
        try {
            java.security.PrivateKey pk = privateKey();
            keyCheck.put("parsed", true);
            keyCheck.put("algorithm", pk.getAlgorithm());
            String probe = "member-system-wxpay-selftest";
            String sig = signer.sign(probe, pk);
            java.security.PublicKey pub = derivePublicKey(pk);
            if (pub == null) {
                keyCheck.put("selfVerify", "skipped");
                keyCheck.put("note", "私钥非 CRT 格式，无法推导公钥做自验（不影响签名）");
            } else {
                boolean ok = signer.verify(probe, sig, pub);
                keyCheck.put("selfVerify", ok);
                if (!ok) {
                    problems.add("私钥自签自验失败，密钥可能已损坏");
                }
            }
        } catch (Exception e) {
            keyCheck.put("parsed", false);
            keyCheck.put("error", e.getMessage());
            problems.add("私钥不可用：" + e.getMessage());
        }
        out.put("privateKey", keyCheck);

        // 3. APIv3 密钥
        Map<String, Object> keyV3 = new LinkedHashMap<>();
        String v3 = apiV3Key();
        keyV3.put("configured", !v3.isEmpty());
        keyV3.put("length", v3.length());
        keyV3.put("valid", v3.length() == 32);
        out.put("apiV3Key", keyV3);

        // 4. 平台证书（只报形态，不联网下载）
        Map<String, Object> cert = new LinkedHashMap<>();
        String rawCert = platformCertRaw();
        cert.put("mode", rawCert.isEmpty() ? "AUTO" : "MANUAL");
        if (!rawCert.isEmpty()) {
            try {
                java.security.cert.X509Certificate c = signer.parseCertificate(resolveSecret(rawCert));
                cert.put("parsed", true);
                cert.put("serial", WechatPaySigner.serialOf(c));
                cert.put("subject", c.getSubjectX500Principal().getName());
                cert.put("notAfter", String.valueOf(c.getNotAfter()));
                if (c.getNotAfter().before(new java.util.Date())) {
                    problems.add("平台证书已过期：" + c.getNotAfter());
                }
            } catch (Exception e) {
                cert.put("parsed", false);
                cert.put("error", e.getMessage());
                problems.add("平台证书不可用：" + e.getMessage());
            }
        }
        out.put("platformCert", cert);

        // 5. 回调地址
        Map<String, Object> notify = new LinkedHashMap<>();
        String url = notifyUrl();
        notify.put("url", url);
        notify.put("https", url.startsWith("https://"));
        out.put("notify", notify);

        out.put("ready", problems.isEmpty());
        out.put("problems", problems);
        return out;
    }

    /**
     * 从 RSA 私钥推导对应公钥
     *
     * <p>PKCS#8 私钥本身不含公钥部分，但 CRT 形式的 RSA 私钥带有 modulus 与 publicExponent，
     * 足以构造出公钥。这样自检不需要额外的证书文件。
     *
     * @return 公钥；私钥不是 CRT 形式时返回 null
     */
    private java.security.PublicKey derivePublicKey(java.security.PrivateKey privateKey) {
        if (!(privateKey instanceof java.security.interfaces.RSAPrivateCrtKey crt)) {
            return null;
        }
        try {
            return java.security.KeyFactory.getInstance("RSA").generatePublic(
                    new java.security.spec.RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        } catch (Exception e) {
            log.warn("[微信支付] 推导公钥失败：{}", e.getMessage());
            return null;
        }
    }

    /** 已配置的平台证书（可能为 null，表示需要自动下载） */
    public X509Certificate configuredPlatformCert() {
        String raw = platformCertRaw();
        if (raw.isEmpty()) {
            return null;
        }
        // 按配置值指纹缓存：手工换证书后必须立刻生效，否则回调会拿旧证书验签，
        // 表现为「微信侧显示成功、本地一直验签失败」，极难定位
        String cacheKey = CACHE_KEY_PLATFORM_CERT + ":" + Integer.toHexString(raw.hashCode());
        String cached = cacheService.get(cacheKey, String.class);
        if (cached != null && !cached.isBlank()) {
            try {
                return WechatPaySigner.decodeCertificate(cached);
            } catch (Exception e) {
                log.warn("[微信支付] 缓存平台证书还原失败，改为重新解析：{}", e.getMessage());
            }
        }
        try {
            X509Certificate cert = signer.parseCertificate(resolveSecret(raw));
            cacheService.set(cacheKey, WechatPaySigner.encodeCertificate(cert), 6, java.util.concurrent.TimeUnit.HOURS);
            return cert;
        } catch (Exception e) {
            log.warn("[微信支付] 平台证书解析失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 把配置值解析成「内容」
     *
     * <p>支持三种写法，按顺序尝试：
     * <ol>
     *   <li>以 {@code file:} 开头 → 当路径读文件</li>
     *   <li>以 {@code -----BEGIN} 开头（或含 {@code \n} 转义后的内容）→ 直接当 PEM</li>
     *   <li>其它 → 先当路径试，读不到再当 Base64 内容解</li>
     * </ol>
     * 这样运维无论用哪种习惯配置都能工作，减少「明明配了却说不认识」的沟通成本。
     */
    public String resolveSecret(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim();
        if (value.startsWith("file:")) {
            return readFile(value.substring("file:".length()).trim());
        }
        if (value.contains("BEGIN ") || value.contains("\n")) {
            return value;
        }
        // 看起来像路径（含分隔符且不含 Base64 的 = 结尾特征）
        if (value.contains("/") || value.contains("\\")) {
            try {
                return readFile(value);
            } catch (Exception e) {
                log.debug("[微信支付] {} 不是可读路径，按内容处理", value);
            }
        }
        return value;
    }

    private String readFile(String path) {
        try {
            byte[] bytes = Files.readAllBytes(Path.of(path));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取密钥文件失败 " + path + "：" + e.getMessage(), e);
        }
    }
}
