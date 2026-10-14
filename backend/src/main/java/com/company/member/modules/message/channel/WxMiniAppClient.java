package com.company.member.modules.message.channel;

import com.company.member.common.cache.CacheService;
import com.company.member.modules.system.service.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 微信小程序服务端 API 客户端（当前仅封装订阅消息所需能力）
 *
 * <p>只在 {@code wx.miniapp.appid} 与 {@code wx.miniapp.secret} 都配置后才可用；
 * 未配置时 {@link #available()} 返回 false，消息自动降级为站内信，不会影响主链路。
 *
 * <p>access_token 由微信侧限制为 2 小时有效且<b>全局唯一</b>（新 token 会让旧 token 失效），
 * 因此用 CacheService 缓存 100 分钟并在过期前复用。多实例部署时应换成 Redis 实现
 * （CacheService 已抽象，切换 profile 即可）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxMiniAppClient {

    private static final String TOKEN_URL =
            "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid={appid}&secret={secret}";
    private static final String SUBSCRIBE_SEND_URL =
            "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token={token}";
    private static final String TOKEN_CACHE_KEY = "wx:miniapp:access_token";

    private final ConfigService configService;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestClient restClient = RestClient.builder().build();

    public String appId() {
        return configService.getString("wx.miniapp.appid", "");
    }

    public String appSecret() {
        return configService.getString("wx.miniapp.secret", "");
    }

    /** 小程序「正式版/体验版/开发版」，订阅消息下发时必填 */
    public String miniprogramState() {
        return configService.getString("wx.miniapp.state", "formal");
    }

    public boolean configured() {
        return !appId().isBlank() && !appSecret().isBlank();
    }

    public boolean available() {
        return configured() && configService.wxSubscribeEnabled();
    }

    public String unavailableReason() {
        if (!configured()) {
            return "未配置 wx.miniapp.appid / wx.miniapp.secret";
        }
        if (!configService.wxSubscribeEnabled()) {
            return "系统参数 wx.subscribe.enabled 为 false";
        }
        return null;
    }

    /** 获取 access_token（带 100 分钟缓存） */
    public String accessToken() {
        String cached = cacheService.getString(TOKEN_CACHE_KEY);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        String body;
        try {
            body = restClient.get()
                    .uri(TOKEN_URL, appId(), appSecret())
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new IllegalStateException("请求微信 access_token 失败: " + e.getMessage(), e);
        }
        JsonNode node = read(body);
        String token = node.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("微信未返回 access_token: " + body);
        }
        cacheService.set(TOKEN_CACHE_KEY, token, 100, TimeUnit.MINUTES);
        return token;
    }

    /**
     * 下发订阅消息
     *
     * @param openid        接收者 openid
     * @param wxTemplateId  订阅消息模板 ID
     * @param page          点击跳转页面（可空）
     * @param data          模板数据，形如 {@code {"thing1":{"value":"张三"}}}
     * @return 微信原始响应
     */
    public JsonNode sendSubscribe(String openid, String wxTemplateId, String page, Map<String, Object> data) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("touser", openid);
        payload.put("template_id", wxTemplateId);
        payload.put("miniprogram_state", miniprogramState());
        payload.put("lang", "zh_CN");
        if (page != null && !page.isBlank()) {
            payload.put("page", page);
        }
        payload.put("data", data);

        String token = accessToken();
        String body;
        try {
            body = restClient.post()
                    .uri(SUBSCRIBE_SEND_URL, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new IllegalStateException("请求微信订阅消息接口失败: " + e.getMessage(), e);
        }
        return read(body);
    }

    /** 主动让本地缓存的 access_token 失效（微信报 40001/42001 时调用） */
    public void evictToken() {
        cacheService.delete(TOKEN_CACHE_KEY);
    }

    private JsonNode read(String body) {
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new IllegalStateException("解析微信响应失败: " + body, e);
        }
    }
}
