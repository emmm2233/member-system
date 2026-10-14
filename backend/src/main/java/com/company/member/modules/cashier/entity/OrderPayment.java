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
 * 订单支付明细（表 m_order_payment）
 */
@Data
@TableName("m_order_payment")
public class OrderPayment implements Serializable {

    public static final int METHOD_BALANCE = 1;
    public static final int METHOD_CASH = 2;
    public static final int METHOD_OFFLINE_CODE = 3;
    public static final int METHOD_POINT = 4;
    public static final int METHOD_WECHAT_ONLINE = 5;
    public static final int METHOD_OTHER = 9;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;
    private String orderNo;
    private Integer payMethod;
    private BigDecimal amount;
    /** 余额支付时：1本金 2赠送；混合扣减时拆两条 */
    private Integer accountType;
    /** 线下扫码外部流水号（人工填） */
    private String outTradeNo;
    /** 在线支付单号（预留） */
    private String paymentNo;
    private String tradeNo;
    private String tradeStatus;
    private LocalDateTime payTime;
    private String accountFlowNo;
    private String idempotencyKey;
    private String remark;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public static String methodText(Integer method) {
        if (method == null) {
            return "";
        }
        return switch (method) {
            case METHOD_BALANCE -> "储值余额";
            case METHOD_CASH -> "现金";
            case METHOD_OFFLINE_CODE -> "线下扫码";
            case METHOD_POINT -> "积分抵扣";
            case METHOD_WECHAT_ONLINE -> "微信在线支付";
            case METHOD_OTHER -> "其他";
            default -> "未知";
        };
    }
}
