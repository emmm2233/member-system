package com.company.member.modules.message.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 消息发送记录（表 m_message_record）
 *
 * <p>状态：1待发送 2已发送 3发送失败 4已读
 *
 * <p>{@code idempotencyKey} 上有唯一索引，是消息「不重复发送」的最终防线：
 * 同一业务动作（同一笔生日礼、同一笔推荐奖励）只允许落一条记录。
 */
@Data
@TableName("m_message_record")
public class MessageRecord implements Serializable {

    public static final int ST_PENDING = 1;
    public static final int ST_SENT = 2;
    public static final int ST_FAILED = 3;
    public static final int ST_READ = 4;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tplCode;
    /** 实际投递渠道（模板渠道不可用时会降级为 INNER） */
    private String channel;
    private Long memberId;
    private String openid;
    private String title;
    private String content;
    private String bizNo;

    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String errorMsg;
    private LocalDateTime sentAt;
    private LocalDateTime readAt;
    private String idempotencyKey;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case ST_PENDING -> "待发送";
            case ST_SENT -> "已发送";
            case ST_FAILED -> "发送失败";
            case ST_READ -> "已读";
            default -> "";
        };
    }
}
