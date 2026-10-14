package com.company.member.modules.account.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 会员资产账户（表 m_member_account，资金核心）
 *
 * <p>不变量：balance == Σ本金流水；gift_balance == Σ赠送流水；
 * points == Σ积分批次余量 == Σ积分流水；growth == Σ成长值流水
 */
@Data
@TableName("m_member_account")
public class MemberAccount implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;

    /** 本金余额（可退） */
    private BigDecimal balance;
    /** 赠送余额（不可退不可提现） */
    private BigDecimal giftBalance;
    /** 冻结金额 */
    private BigDecimal frozenAmount;
    /** 积分余额（可为负：退单扣回） */
    private Long points;
    /** 成长值（只增不减） */
    private Long growth;

    private BigDecimal totalRecharge;
    private BigDecimal totalGift;
    private BigDecimal totalConsume;
    private Integer totalConsumeCount;

    private LocalDateTime balanceExpireAt;

    @Version
    private Integer version;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 可用余额合计（本金 + 赠送） */
    public BigDecimal available() {
        BigDecimal b = balance == null ? BigDecimal.ZERO : balance;
        BigDecimal g = giftBalance == null ? BigDecimal.ZERO : giftBalance;
        return b.add(g);
    }
}
