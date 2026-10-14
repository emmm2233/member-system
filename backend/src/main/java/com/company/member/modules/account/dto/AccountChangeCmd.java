package com.company.member.modules.account.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资产变动命令（AccountService 统一入口入参）
 *
 * <p>一个命令可以同时变动四类资产（余额/赠送/积分/成长值），
 * 保证「流水 + 余额 + 审计」在同一事务内原子完成。
 */
@Data
@Builder
public class AccountChangeCmd {

    /* ===== 必填 ===== */
    private Long memberId;
    /** 业务类型：见 BizTypeEnum */
    private Integer bizType;
    /** 业务单号（订单号/充值单号），用于流水关联与反查 */
    private String bizNo;
    /** 幂等键：同一业务只入账一次（建议直接用 bizNo） */
    private String idempotencyKey;

    /* ===== 变动额（null 视为 0） ===== */
    private BigDecimal balanceDelta;
    private BigDecimal giftDelta;
    private Long pointsDelta;
    private Long growthDelta;

    /* ===== 上下文 ===== */
    private Long bizId;
    private Long storeId;
    private Long operatorId;
    private String shiftNo;
    private String remark;
    private LocalDateTime occurTime;
    /** 积分过期时间（获得类积分时传入；为空则按系统配置计算） */
    private LocalDateTime pointsExpireTime;
    /** 是否校验余额充足（扣减类必须 true） */
    private boolean checkBalance = true;
    /** 是否校验积分充足 */
    private boolean checkPoints = true;
    /** 积分允许为负（退单扣回场景） */
    private boolean allowNegativePoints = false;

    public BigDecimal balanceDeltaOrZero() {
        return balanceDelta == null ? BigDecimal.ZERO : balanceDelta;
    }

    public BigDecimal giftDeltaOrZero() {
        return giftDelta == null ? BigDecimal.ZERO : giftDelta;
    }

    public long pointsDeltaOrZero() {
        return pointsDelta == null ? 0L : pointsDelta;
    }

    public long growthDeltaOrZero() {
        return growthDelta == null ? 0L : growthDelta;
    }

    public LocalDateTime occurTimeOrNow() {
        return occurTime == null ? LocalDateTime.now() : occurTime;
    }

    /** 是否涉及余额变动 */
    public boolean hasBalanceChange() {
        return balanceDeltaOrZero().signum() != 0 || giftDeltaOrZero().signum() != 0;
    }

    public boolean hasPointsChange() {
        return pointsDeltaOrZero() != 0;
    }

    public boolean hasGrowthChange() {
        return growthDeltaOrZero() != 0;
    }
}
