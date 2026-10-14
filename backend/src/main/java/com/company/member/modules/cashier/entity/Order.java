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
 * 订单主表（表 m_order，资金核心）
 *
 * <p>订单号前缀：O 消费、R 充值、C 售卡、X 积分兑换
 */
@Data
@TableName("m_order")
public class Order implements Serializable {

    public static final int TYPE_CONSUME = 1;
    public static final int TYPE_RECHARGE = 2;
    public static final int TYPE_CARD_SALE = 3;
    public static final int TYPE_EXCHANGE = 4;

    public static final int STATUS_UNPAID = 1;
    public static final int STATUS_PAYING = 2;
    public static final int STATUS_PAID = 3;
    public static final int STATUS_PARTIAL_REFUND = 4;
    public static final int STATUS_REFUNDED = 5;
    public static final int STATUS_CANCELLED = 6;
    public static final int STATUS_HOLD = 7;
    public static final int STATUS_PAY_FAILED = 8;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;
    private Integer orderType;
    private Long memberId;
    private Long storeId;
    private Long cashierId;
    private Long staffId;

    private BigDecimal totalAmount;
    private BigDecimal discountAmount;
    private BigDecimal couponAmount;
    private BigDecimal pointDeductAmount;
    private Long pointDeductPoints;
    private BigDecimal payableAmount;
    private BigDecimal paidAmount;
    private BigDecimal giftAmount;
    private Long pointsGained;
    private Long growthGained;

    private Integer status;
    private BigDecimal refundAmount;

    private String paymentNo;
    private String channelOrderNo;
    private LocalDateTime expireTime;

    private String shiftNo;
    private String idempotencyKey;
    private String remark;
    private Long tenantId;
    private LocalDateTime paidAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static String typeText(Integer type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case TYPE_CONSUME -> "消费";
            case TYPE_RECHARGE -> "充值";
            case TYPE_CARD_SALE -> "售卡";
            case TYPE_EXCHANGE -> "积分兑换";
            default -> "其他";
        };
    }

    public static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case STATUS_UNPAID -> "待支付";
            case STATUS_PAYING -> "支付中";
            case STATUS_PAID -> "已支付";
            case STATUS_PARTIAL_REFUND -> "部分退款";
            case STATUS_REFUNDED -> "已全额退";
            case STATUS_CANCELLED -> "已取消";
            case STATUS_HOLD -> "挂单";
            case STATUS_PAY_FAILED -> "支付失败";
            default -> "未知";
        };
    }

    public boolean isPaid() {
        return status != null && (status == STATUS_PAID || status == STATUS_PARTIAL_REFUND);
    }
}
