package com.company.member.modules.cashier.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 结算结果（试算与正式开单共用）
 */
@Data
@Schema(description = "结算结果")
public class SettleResult implements Serializable {

    @Schema(description = "订单号（试算时为空）")
    private String orderNo;

    @Schema(description = "订单状态（试算时为空）")
    private Integer status;

    @Schema(description = "原价合计")
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Schema(description = "会员折扣优惠额")
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Schema(description = "券抵扣额")
    private BigDecimal couponAmount = BigDecimal.ZERO;

    @Schema(description = "积分抵扣额")
    private BigDecimal pointDeductAmount = BigDecimal.ZERO;

    @Schema(description = "本单消耗积分")
    private Long pointDeductPoints = 0L;

    @Schema(description = "应收金额")
    private BigDecimal payableAmount = BigDecimal.ZERO;

    @Schema(description = "实收金额")
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Schema(description = "找零（仅现金支付时有值）")
    private BigDecimal changeAmount;

    @Schema(description = "本单获得积分（预估/实际）")
    private Long pointsGained = 0L;

    @Schema(description = "本单获得成长值")
    private Long growthGained = 0L;

    @Schema(description = "会员可用积分上限")
    private Long maxUsablePoints = 0L;

    @Schema(description = "积分最多可抵扣金额（按规则）")
    private BigDecimal maxPointDeductAmount = BigDecimal.ZERO;

    @Schema(description = "是否升级")
    private Boolean levelUpgraded = false;

    @Schema(description = "升级后等级名")
    private String newLevelName;

    @Schema(description = "明细")
    private List<ItemResult> items = new ArrayList<>();

    @Schema(description = "券使用结果")
    private List<CouponResult> couponResults = new ArrayList<>();

    @Schema(description = "不可用券及原因（试算提示用）")
    private List<CouponResult> unavailableCoupons = new ArrayList<>();

    @Schema(description = "会员快照")
    private MemberSnapshot memberSnapshot;

    @Schema(description = "变动后资产（正式开单时返回）")
    private com.company.member.modules.account.vo.AccountChangeResult account;

    @Data
    @Schema(description = "明细结果")
    public static class ItemResult implements Serializable {
        private Long productId;
        private Integer itemType;
        private String productName;
        private String productCode;
        private BigDecimal price;
        private BigDecimal qty;
        private BigDecimal totalAmount;
        private BigDecimal discountAmount;
        private BigDecimal actualAmount;
        private Boolean joinDiscount;
        private Boolean joinPoints;
        private BigDecimal pointsRate;
    }

    @Data
    @Schema(description = "券结果")
    public static class CouponResult implements Serializable {
        private String couponNo;
        private Long tplId;
        private String tplName;
        private Integer couponType;
        private BigDecimal discountAmount;
        private Boolean usable;
        private String reason;
        private String validEnd;
    }

    @Data
    @Schema(description = "会员快照")
    public static class MemberSnapshot implements Serializable {
        private Long memberId;
        private String memberNo;
        private String nickname;
        private String levelName;
        private BigDecimal discountRate;
        private BigDecimal pointsRate;
        private BigDecimal balance;
        private BigDecimal giftBalance;
        private Long points;
        private Long growth;
    }
}
