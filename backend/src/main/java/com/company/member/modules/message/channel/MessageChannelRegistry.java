package com.company.member.modules.message.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 消息渠道注册表
 *
 * <p>Spring 启动时把所有 {@link MessageChannel} 实现收集进来，业务侧只按 code 取用。
 * 新增渠道无需改动 MessageService（开闭原则）。
 */
@Slf4j
@Component
public class MessageChannelRegistry {

    private final Map<String, MessageChannel> channels = new LinkedHashMap<>();

    public MessageChannelRegistry(List<MessageChannel> list) {
        for (MessageChannel c : list) {
            MessageChannel prev = channels.put(c.code(), c);
            if (prev != null) {
                throw new IllegalStateException("消息渠道编码重复: " + c.code());
            }
        }
        log.info("[消息] 已注册渠道 {} 个: {}", channels.size(), channels.keySet());
    }

    public Optional<MessageChannel> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(channels.get(code));
    }

    /** 兜底渠道（站内信），保证任何消息都有落点 */
    public MessageChannel fallback() {
        MessageChannel inner = channels.get("INNER");
        if (inner == null) {
            throw new IllegalStateException("站内信渠道未注册，消息体系缺少兜底");
        }
        return inner;
    }

    /** 渠道就绪状态（供管理端「渠道诊断」展示） */
    public List<Map<String, Object>> status() {
        return channels.values().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.code());
            m.put("name", c.name());
            m.put("available", c.available());
            m.put("reason", c.unavailableReason());
            return m;
        }).toList();
    }
}
