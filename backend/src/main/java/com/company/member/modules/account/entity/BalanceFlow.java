package com.company.member.modules.account.entity;

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
 * 余额流水（表 m_balance_flow，资金核心，只增不改）
 */
@Data
@TableName("m_balance_flow")
public class BalanceFlow implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String flowNo;
    private Long memberId;
    /** 1本金 2赠送 */
    private Integer accountType;
    /** 1充值 2消费 3退款 4调整 5赠送 6过期 7转入 8转出 9合并 */
    private Integer bizType;

    private BigDecimal changeAmount;
    private BigDecimal beforeAmount;
    private BigDecimal afterAmount;

    private String bizNo;
    private Long bizId;
    private Long storeId;
    private Long operatorId;
    private String shiftNo;
    private String idempotencyKey;
    private String remark;

    private LocalDateTime occurTime;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
