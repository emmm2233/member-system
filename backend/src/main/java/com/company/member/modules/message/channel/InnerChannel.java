package com.company.member.modules.message.channel;

import com.company.member.modules.message.entity.MessageRecord;
import com.company.member.modules.message.entity.MessageTpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 站内信渠道：写入 m_message_record 即视为投递成功（会员在小程序「消息中心」可见）。
 *
 * <p>它是整个消息体系的<b>兜底渠道</b>：微信订阅消息额度用尽、未配置 AppID/Secret、
 * 或下发失败时都会降级到这里，保证业务通知不丢内容。
 */
@Slf4j
@Component
public class InnerChannel implements MessageChannel {

    @Override
    public String code() {
        return MessageTpl.CH_INNER;
    }

    @Override
    public String name() {
        return "站内信";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String unavailableReason() {
        return null;
    }

    @Override
    public SendResult send(MessageTpl tpl, MessageRecord record) {
        log.debug("[消息] 站内信落库 memberId={}, tplCode={}", record.getMemberId(), record.getTplCode());
        return SendResult.ok("INNER");
    }
}
