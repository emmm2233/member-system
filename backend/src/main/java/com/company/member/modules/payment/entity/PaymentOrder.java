package com.company.member.modules.payment.entity;

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
 * 支付单（表 m_payment_order，预留：小程序在线支付）
 *
 * <p>状态：1待支付 2支付中 3已支付 4已关闭 5已全额退 6部分退款 7支付失败
 */
@Data
@TableName("m_payment_order")
public class PaymentOrder implements Serializable {

    public static final int BIZ_RECHARGE = 1;
    public static final int BIZ_EXCHANGE = 2;
    public static final int BIZ_CARD = 3;
    public static final int BIZ_MALL = 4;

    public static final int STATUS_UNPAID = 1;
    public static final int STATUS_PAYING = 2;
    public static final int STATUS_PAID = 3;
    public static final int STATUS_CLOSED = 4;
    public static final int STATUS_REFUNDED = 5;
    public static final int STATUS_PARTIAL_REFUND = 6;
    public static final int STATUS_FAILED = 7;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String paymentNo;
    private Integer bizType;
    private String bizNo;
    private Long orderId;
    private Long memberId;
    private String openid;

    /** 1小程序JSAPI 2H5 3APP */
    private Integer payScene;
    private String channel;
    private String channelAppid;
    private String mchId;

    private BigDecimal amount;
    private Long amountFen;
    private String currency;
    private String subject;
    private String description;

    private String channelOrderNo;
    private String prepayId;
    private Integer status;

    private Long storeId;
    private String idempotencyKey;
    private LocalDateTime expireTime;
    private LocalDateTime paidAt;
    private LocalDateTime closedAt;

    private BigDecimal refundAmount;
    private String refundNo;
    private LocalDateTime refundedAt;

    private Integer notifyCount;
    private LocalDateTime lastNotifyAt;
    private Integer accountCredited;
    private LocalDateTime creditedAt;

    private String failCode;
    private String failMsg;
    private String attach;
    private String remark;
    private Long tenantId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case STATUS_UNPAID -> "待支付";
            case STATUS_PAYING -> "支付中";
            case STATUS_PAID -> "已支付";
            case STATUS_CLOSED -> "已关闭";
            case STATUS_REFUNDED -> "已全额退";
            case STATUS_PARTIAL_REFUND -> "部分退款";
            case STATUS_FAILED -> "支付失败";
            default -> "未知";
        };
    }

    public boolean isPaid() {
        return status != null && status == STATUS_PAID;
    }

    public boolean expired() {
        return expireTime != null && LocalDateTime.now().isAfter(expireTime);
    }
}
