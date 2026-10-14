package com.company.member.modules.message.channel;

import com.company.member.modules.message.entity.MessageRecord;
import com.company.member.modules.message.entity.MessageTpl;
import com.company.member.modules.message.mapper.MessageSubscribeMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信订阅消息渠道
 *
 * <p><b>一次性订阅消息的额度语义</b>：用户每授权一次只能收一条。本渠道在真正调用
 * 微信接口前先用条件 UPDATE 预扣额度（{@code remain_count > 0}），因此：
 * <ul>
 *   <li>并发下发不会超额（数据库层面互斥）</li>
 *   <li>额度为 0 时返回 {@link #ERR_NO_QUOTA}，由 MessageService 降级为站内信</li>
 *   <li>调用失败（非用户拒收）时把预扣的额度还回去，下次重试仍有额度</li>
 * </ul>
 *
 * <p>模板字段映射来自 {@code m_message_tpl.wx_data_map}，形如
 * <code>{"thing1":"{memberName}","amount2":"{amount}"}</code>；未配置时退化为
 * 只发一个 thing1 字段（内容取消息标题）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxSubscribeChannel implements MessageChannel {

    /** 额度耗尽（MessageService 据此降级，不当作失败重试） */
    public static final String ERR_NO_QUOTA = "SUBSCRIBE_QUOTA_EXHAUSTED";

    /** 用户拒收 / 已取消订阅，微信 errcode 43101 */
    private static final int ERR_USER_REFUSED = 43101;
    /** access_token 失效类错误，清缓存后可重试 */
    private static final int ERR_TOKEN_INVALID = 40001;
    private static final int ERR_TOKEN_EXPIRED = 42001;

    private final WxMiniAppClient wxClient;
    private final MessageSubscribeMapper subscribeMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String code() {
        return MessageTpl.CH_WX_SUBSCRIBE;
    }

    @Override
    public String name() {
        return "微信订阅消息";
    }

    @Override
    public boolean available() {
        return wxClient.available();
    }

    @Override
    public String unavailableReason() {
        return wxClient.unavailableReason();
    }

    @Override
    public SendResult send(MessageTpl tpl, MessageRecord record) {
        if (!available()) {
            return SendResult.fail(unavailableReason());
        }
        if (tpl.getWxTemplateId() == null || tpl.getWxTemplateId().isBlank()) {
            return SendResult.fail("模板 " + tpl.getTplCode() + " 未配置 wx_template_id");
        }
        if (record.getOpenid() == null || record.getOpenid().isBlank()) {
            return SendResult.fail("会员未绑定微信 openid，无法下发订阅消息");
        }

        // 1. 先组装模板数据（纯计算，失败不应消耗额度）
        Map<String, Object> data = buildData(tpl, record);
        if (data.isEmpty()) {
            return SendResult.fail("模板 " + tpl.getTplCode() + " 的 wx_data_map 为空且标题为空");
        }

        // 2. 预扣额度（并发安全）
        if (subscribeMapper.consumeQuota(record.getMemberId(), tpl.getWxTemplateId()) == 0) {
            return SendResult.fail(ERR_NO_QUOTA);
        }
        boolean quotaConsumed = true;
        try {
            // 3. 调用微信
            JsonNode resp = wxClient.sendSubscribe(record.getOpenid(), tpl.getWxTemplateId(),
                    tpl.getPagePath(), data);
            int errcode = resp.path("errcode").asInt(0);
            if (errcode == 0) {
                return SendResult.ok(truncate(resp.toString()));
            }
            if (errcode == ERR_TOKEN_INVALID || errcode == ERR_TOKEN_EXPIRED) {
                wxClient.evictToken();
            }
            if (errcode != ERR_USER_REFUSED) {
                // 非用户原因：归还额度，后续重试仍可下发
                subscribeMapper.refundQuota(record.getMemberId(), tpl.getWxTemplateId());
                quotaConsumed = false;
            }
            String reason = "微信错误 " + errcode + ": " + resp.path("errmsg").asText("");
            log.warn("[消息] 订阅消息下发失败 memberId={}, tpl={}, {}",
                    record.getMemberId(), tpl.getTplCode(), reason);
            return SendResult.fail(reason);
        } catch (Exception e) {
            if (quotaConsumed) {
                subscribeMapper.refundQuota(record.getMemberId(), tpl.getWxTemplateId());
            }
            log.warn("[消息] 订阅消息异常 memberId={}, tpl={}, 原因={}",
                    record.getMemberId(), tpl.getTplCode(), e.getMessage());
            return SendResult.fail(e.getMessage());
        }
    }

    /** 依据 wx_data_map 生成微信模板字段；未配置时退化为 thing1=标题 */
    Map<String, Object> buildData(MessageTpl tpl, MessageRecord record) {
        Map<String, Object> data = new LinkedHashMap<>();
        String map = tpl.getWxDataMap();
        if (map == null || map.isBlank()) {
            if (record.getTitle() != null && !record.getTitle().isBlank()) {
                data.put("thing1", Map.of("value", clip(record.getTitle(), 20)));
            }
            return data;
        }
        try {
            JsonNode node = objectMapper.readTree(map);
            node.fields().forEachRemaining(e -> {
                String key = e.getKey();
                String raw = e.getValue().asText("");
                // 正文/标题占位符直接取整段文本，其余保持原样
                String value = raw.replace("{title}", nvl(record.getTitle()))
                        .replace("{content}", nvl(record.getContent()));
                data.put(key, Map.of("value", clip(value, maxLen(key))));
            });
        } catch (Exception e) {
            log.warn("[消息] wx_data_map 解析失败 tpl={}, 原始值={}, 原因={}",
                    tpl.getTplCode(), map, e.getMessage());
        }
        return data;
    }

    /** 微信各字段类型有长度上限，超长会整条被拒，这里按前缀做保守截断 */
    private static int maxLen(String key) {
        if (key.startsWith("thing")) {
            return 20;
        }
        if (key.startsWith("character_string")) {
            return 32;
        }
        if (key.startsWith("phrase")) {
            return 5;
        }
        if (key.startsWith("date")) {
            return 16;
        }
        if (key.startsWith("number") || key.startsWith("amount") || key.startsWith("price")) {
            return 32;
        }
        return 32;
    }

    private static String clip(String v, int max) {
        if (v == null) {
            return "";
        }
        String s = v.trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String nvl(String v) {
        return v == null ? "" : v;
    }

    private static String truncate(String v) {
        if (v == null) {
            return null;
        }
        return v.length() <= 500 ? v : v.substring(0, 500);
    }
}
