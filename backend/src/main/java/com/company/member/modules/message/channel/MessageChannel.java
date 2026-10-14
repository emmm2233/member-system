package com.company.member.modules.message.channel;

import com.company.member.modules.message.entity.MessageRecord;
import com.company.member.modules.message.entity.MessageTpl;

/**
 * 消息渠道抽象
 *
 * <p>新增渠道（短信、公众号模板消息、企微…）只需实现本接口并注册为 Spring Bean，
 * {@code MessageChannelRegistry} 会自动收录，业务侧无需改动。
 *
 * <p><b>契约</b>：
 * <ul>
 *   <li>{@link #available()} 必须是无副作用的快速判断（只读配置），用于决定是否降级</li>
 *   <li>{@link #send} 返回失败时 <b>不抛异常</b>，由 MessageService 统一落库与重试</li>
 *   <li>实现类必须自行保证「同一条 record 重复投递」不会造成用户侧重复触达</li>
 * </ul>
 */
public interface MessageChannel {

    /** 渠道编码，与 m_message_tpl.channel 一致 */
    String code();

    /** 渠道名称（展示用） */
    String name();

    /** 渠道当前是否可用（配置齐全）。false 时 MessageService 会降级到站内信 */
    boolean available();

    /** 不可用原因（用于诊断展示） */
    String unavailableReason();

    /**
     * 投递一条消息
     *
     * @param tpl    模板（含微信模板 ID / 字段映射）
     * @param record 已渲染好标题与正文的记录（含 memberId / openid）
     * @return 投递结果
     */
    SendResult send(MessageTpl tpl, MessageRecord record);

    /**
     * 投递结果
     *
     * @param success 是否成功
     * @param error   失败原因（成功时为 null）
     * @param detail  渠道返回的原始摘要（便于排查，截断到 500 字符内）
     */
    record SendResult(boolean success, String error, String detail) {

        public static SendResult ok(String detail) {
            return new SendResult(true, null, detail);
        }

        public static SendResult fail(String error) {
            return new SendResult(false, error, null);
        }
    }
}
