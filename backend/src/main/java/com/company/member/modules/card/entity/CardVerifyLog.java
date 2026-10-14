package com.company.member.modules.card.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 次卡核销记录（表 m_card_verify_log）
 */
@Data
@TableName("m_card_verify_log")
public class CardVerifyLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String verifyNo;
    private Long memberCardId;
    private String cardNo;
    private Long memberCardItemId;
    private Long memberId;
    private String itemName;
    private Integer verifyTimes;
    private Integer beforeTimes;
    private Integer afterTimes;
    private Long storeId;
    private Long operatorId;
    private Long orderId;
    /** 1扫码 2输码 3收银台选择 */
    private Integer verifyType;
    private String remark;
    private String idempotencyKey;
    private LocalDateTime verifiedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
