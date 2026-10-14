package com.company.member.modules.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 充值结果
 */
@Data
@Schema(description = "充值结果")
public class RechargeResult implements Serializable {

    @Schema(description = "充值单号")
    private String orderNo;

    @Schema(description = "充值本金")
    private BigDecimal rechargeAmount;

    @Schema(description = "赠送金额")
    private BigDecimal giftAmount;

    @Schema(description = "赠送积分")
    private Long giftPoints;

    @Schema(description = "是否命中幂等（重复请求）")
    private boolean idempotentHit;

    @Schema(description = "变动后资产")
    private com.company.member.modules.account.vo.AccountChangeResult account;

    @Schema(description = "本次发放的券")
    private List<GrantedCoupon> grantedCoupons = new ArrayList<>();

    @Schema(description = "充值后的积分余额（含方案赠送积分）")
    private Long pointsAfter;

    @Schema(description = "充值后的本金余额")
    private BigDecimal balanceAfter;

    @Schema(description = "充值后的赠送余额")
    private BigDecimal giftBalanceAfter;

    @Data
    @Schema(description = "已发放的券")
    public static class GrantedCoupon implements Serializable {
        private String couponNo;
        private Long tplId;
        private String tplName;
        private String validEnd;
    }
}
