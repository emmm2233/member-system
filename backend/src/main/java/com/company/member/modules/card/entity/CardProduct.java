package com.company.member.modules.card.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 次卡/服务卡产品（表 m_card_product）
 */
@Data
@TableName("m_card_product")
public class CardProduct implements Serializable {

    public static final int TYPE_TIMES = 1;
    public static final int TYPE_PERIOD = 2;
    public static final int TYPE_VALUE = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cardCode;
    private String cardName;
    private Integer cardType;
    private BigDecimal sellPrice;
    private BigDecimal originalPrice;
    private Integer totalTimes;
    private Integer validDays;
    private String scopeStore;
    private String description;
    private String image;
    private Integer allowRefund;
    private String refundRule;
    private Integer status;
    private Integer sortNo;
    private Integer deleted;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
