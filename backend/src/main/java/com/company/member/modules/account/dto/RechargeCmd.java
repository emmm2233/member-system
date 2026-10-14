package com.company.member.modules.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 充值命令（收银员手工充值 / 在线支付成功后入账 共用）
 */
@Data
@Schema(description = "充值请求")
public class RechargeCmd {

    @NotNull(message = "会员 id 不能为空")
    private Long memberId;

    @Schema(description = "充值方案 id（传了则本金/赠送/送券按方案执行）")
    private Long planId;

    @NotNull(message = "充值金额不能为空")
    @DecimalMin(value = "0.01", message = "充值金额必须大于 0")
    @Schema(description = "充值本金")
    private BigDecimal rechargeAmount;

    @Schema(description = "赠送金额（无方案时手工填）")
    private BigDecimal giftAmount;

    @Schema(description = "赠送积分")
    private Long giftPoints;

    @Schema(description = "支付方式：2现金 3线下扫码 9其他（在线支付走支付模块，不传）")
    private Integer payMethod = 2;

    @Schema(description = "线下扫码外部流水号")
    private String outTradeNo;

    @Schema(description = "赠送券：[{tplId, qty}]")
    private List<GiftCoupon> giftCoupons;

    @Schema(description = "班次号")
    private String shiftNo;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "幂等键（请求头 X-Idempotency-Key 优先）")
    private String idempotencyKey;

    @Data
    @Schema(description = "赠券项")
    public static class GiftCoupon {
        private Long tplId;
        private Integer qty;
    }
}
