package com.company.member.modules.cashier.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 收银开单 / 试算 请求
 */
@Data
@Schema(description = "收银开单请求")
public class CashierOrderCmd {

    @Schema(description = "会员 id（散客模式为空）")
    private Long memberId;

    @Schema(description = "订单类型：1消费 2充值 3售卡 4积分兑换")
    private Integer orderType = 1;

    @NotNull(message = "商品明细不能为空")
    @Schema(description = "商品明细")
    private List<Item> items = new ArrayList<>();

    @Schema(description = "使用的券码列表")
    private List<String> couponNos;

    @Schema(description = "使用的积分")
    private Long usePoints;

    @Schema(description = "支付明细（试算时可为空）")
    private List<Payment> payments;

    @Schema(description = "服务员工 id（业绩归属）")
    private Long staffId;

    @Schema(description = "门店 id")
    private Long storeId;

    @Schema(description = "班次号")
    private String shiftNo;

    @Schema(description = "现金实收（用于计算找零，不落库）")
    private BigDecimal receivedAmount;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "幂等键（请求头 X-Idempotency-Key 优先）")
    private String idempotencyKey;

    @Data
    @Schema(description = "商品明细项")
    public static class Item {
        @Schema(description = "明细类型：1商品 2服务 4自由金额")
        private Integer itemType = 1;
        private Long productId;
        @Schema(description = "自由金额时的名称")
        private String productName;
        @Schema(description = "自由金额时的单价")
        private BigDecimal price;
        @NotNull(message = "数量不能为空")
        private BigDecimal qty;
    }

    @Data
    @Schema(description = "支付明细项")
    public static class Payment {
        @Schema(description = "1储值余额 2现金 3线下扫码 4积分抵扣 9其他")
        private Integer payMethod;
        private BigDecimal amount;
        @Schema(description = "余额支付时的账户类型：1本金 2赠送（服务端会拆分，前端可不传）")
        private Integer accountType;
        @Schema(description = "线下扫码外部流水号")
        private String outTradeNo;
    }
}
