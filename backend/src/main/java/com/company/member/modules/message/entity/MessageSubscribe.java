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
 * 订阅消息授权额度（表 m_message_subscribe）
 *
 * <p>微信「一次性订阅消息」的规则：用户每授权一次，服务端只能下发 **一条**。
 * 因此不能只看「是否授权过」，必须按模板维度记账剩余可发送次数：
 * <ul>
 *   <li>小程序端调用 {@code wx.requestSubscribeMessage} 后上报接受的模板 ID → {@code remain_count + 1}</li>
 *   <li>每次成功下发 → {@code remain_count - 1}（用条件 UPDATE 保证不会扣成负数）</li>
 * </ul>
 *
 * <p>额度耗尽时消息降级为站内信，不丢内容，用户仍可在我-消息中心看到。
 */
@Data
@TableName("m_message_subscribe")
public class MessageSubscribe implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;
    private String wxTemplateId;
    /** 关联模板编码（可空，便于运营排查） */
    private String tplCode;
    /** 剩余可发送次数 */
    private Integer remainCount;
    /** 累计授权次数 */
    private Integer totalCount;
    private LocalDateTime lastGrantAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
