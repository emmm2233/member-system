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
 * 消息模板（表 m_message_tpl）
 *
 * <p>渠道：WX_SUBSCRIBE 微信订阅消息 / INNER 站内信 / SMS 短信（预留）
 *
 * <p>{@code contentTpl} 支持 <code>{placeholder}</code> 占位符，由
 * {@code MessageService} 在发送时渲染。{@code wxDataMap} 是把占位符映射到
 * 微信订阅消息模板字段（如 thing1/amount2）的 JSON，仅在 WX_SUBSCRIBE 渠道使用。
 */
@Data
@TableName("m_message_tpl")
public class MessageTpl implements Serializable {

    public static final String CH_WX_SUBSCRIBE = "WX_SUBSCRIBE";
    public static final String CH_INNER = "INNER";
    public static final String CH_SMS = "SMS";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tplCode;
    private String tplName;
    private String channel;
    /** 微信订阅消息模板 ID（公众平台申请后填入） */
    private String wxTemplateId;
    /** 微信模板字段映射 JSON：{"thing1":"{memberName}","amount2":"{amount}"} */
    private String wxDataMap;
    private String title;
    private String contentTpl;
    /** 小程序跳转页面 */
    private String pagePath;
    private Integer status;
    private String remark;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static String channelText(String channel) {
        if (channel == null) {
            return "";
        }
        return switch (channel) {
            case CH_WX_SUBSCRIBE -> "微信订阅消息";
            case CH_INNER -> "站内信";
            case CH_SMS -> "短信";
            default -> channel;
        };
    }
}
