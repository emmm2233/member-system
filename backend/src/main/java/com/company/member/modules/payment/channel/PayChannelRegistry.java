package com.company.member.modules.payment.channel;

import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.modules.system.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付渠道注册表
 *
 * <p>Spring 自动注入所有 {@link PayChannel} 实现；按系统参数 {@code pay.channels}
 * 过滤可用渠道。新增渠道实现类无需修改本类。
 */
@Slf4j
@Component
public class PayChannelRegistry {

    private final Map<String, PayChannel> channels = new LinkedHashMap<>();
    private final ConfigService configService;

    public PayChannelRegistry(List<PayChannel> channelList, ConfigService configService) {
        this.configService = configService;
        for (PayChannel c : channelList) {
            channels.put(c.channelCode().toUpperCase(), c);
            log.info("[支付] 注册渠道 {} (就绪={})", c.channelCode(), c.isReady());
        }
    }

    /** 取渠道（未注册或未启用则抛业务异常） */
    public PayChannel get(String channelCode) {
        String code = channelCode == null ? configService.payDefaultChannel() : channelCode.toUpperCase();
        PayChannel channel = channels.get(code);
        if (channel == null) {
            throw new BizException(ErrorCode.PAY_CHANNEL_NOT_READY, "支付渠道未实现：" + code);
        }
        if (!channel.isReady()) {
            throw new BizException(ErrorCode.PAY_CHANNEL_NOT_READY,
                    "支付渠道未配置完成：" + code + "，缺失 " + channel.missingConfigs());
        }
        return channel;
    }

    public PayChannel getOrDefault() {
        return get(configService.payDefaultChannel());
    }

    /** 可用渠道列表（前端渲染支付方式用） */
    public List<Map<String, Object>> availableChannels() {
        List<Map<String, Object>> list = new ArrayList<>();
        List<String> enabled = configService.payChannels();
        for (Map.Entry<String, PayChannel> e : channels.entrySet()) {
            PayChannel c = e.getValue();
            Map<String, Object> m = new HashMap<>();
            m.put("channel", c.channelCode());
            m.put("name", displayName(c.channelCode()));
            m.put("ready", c.isReady());
            m.put("enabled", enabled.isEmpty() || enabled.contains(e.getKey()));
            m.put("missing", c.missingConfigs());
            if (c instanceof DiagnosableChannel d) {
                m.put("diagnose", d.diagnose());
            }
            list.add(m);
        }
        return list;
    }

    /** 渠道配置与就绪状态（管理端展示） */
    public Map<String, Object> channelStatus() {
        Map<String, Object> m = new HashMap<>();
        m.put("payEnabled", configService.payEnabled());
        m.put("mockEnabled", configService.payMockEnabled());
        m.put("defaultChannel", configService.payDefaultChannel());
        m.put("notifyUrl", configService.payNotifyUrl());
        m.put("timeoutMinutes", configService.payTimeoutMinutes());
        m.put("channels", availableChannels());
        return m;
    }

    private String displayName(String code) {
        return switch (code) {
            case "MOCK" -> "模拟支付（开发调试）";
            case "WECHAT" -> "微信支付";
            case "ALIPAY" -> "支付宝";
            default -> code;
        };
    }
}
