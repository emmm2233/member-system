package com.company.member.modules.payment.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 支付回调日志（表 m_payment_notify_log）
 *
 * <p>排障口诀：钱到了账没到 → 先看 raw_body 与 sign_verified，
 * 再看 m_payment_order.status 与 account_credited，最后看 m_balance_flow。
 */
@Data
@TableName("m_payment_notify_log")
public class PaymentNotifyLog implements Serializable {

    public static final int TYPE_PAY = 1;
    public static final int TYPE_REFUND = 2;

    public static final int HANDLE_PENDING = 1;
    public static final int HANDLE_SUCCESS = 2;
    public static final int HANDLE_FAILED = 3;
    public static final int HANDLE_DUPLICATED = 4;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String paymentNo;
    private String channel;
    private Integer notifyType;
    /** 渠道通知唯一 id（唯一索引，防重放） */
    private String notifyId;
    private String outTradeNo;
    private String channelOrderNo;
    private String tradeState;
    private Long amountFen;
    private String headers;
    private String rawBody;
    private Integer signVerified;
    private String verifyMode;
    private Integer handleStatus;
    private String handleResult;
    private Integer handleMs;
    private String ip;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime handledAt;
}
