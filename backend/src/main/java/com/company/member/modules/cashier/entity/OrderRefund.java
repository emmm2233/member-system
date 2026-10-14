package com.company.member.modules.cashier.entity;

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
 * 退款/退单记录（表 m_order_refund）
 */
@Data
@TableName("m_order_refund")
public class OrderRefund implements Serializable {

    public static final int STATUS_PENDING = 1;
    public static final int STATUS_APPROVED = 2;
    public static final int STATUS_REJECTED = 3;
    public static final int STATUS_CANCELLED = 4;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String refundNo;
    private Long orderId;
    private String orderNo;
    private Long memberId;
    private Long storeId;

    private BigDecimal refundAmount;
    private BigDecimal refundGift;
    private BigDecimal deductGift;
    private Long deductPoints;

    /** 1原路退回余额 2现金 3线下转账 */
    private Integer refundMethod;
    /** 1退回可再用 2作废 3不处理 */
    private Integer couponHandle;

    private String reason;
    private Integer status;
    private Long operatorId;
    private Long approverId;
    private LocalDateTime approvedAt;
    private String approveRemark;
    private String idempotencyKey;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
