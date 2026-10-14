package com.company.member.modules.member.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会员 360° 视图：资产 + 统计 + 卡券概览 + 最近订单
 */
@Data
@Schema(description = "会员 360° 视图")
public class MemberDetailVO implements Serializable {

    @Schema(description = "会员资料")
    private MemberVO member;

    @Schema(description = "资产账户")
    private AccountBrief account;

    @Schema(description = "统计快照")
    private StatBrief stat;

    @Schema(description = "券概览")
    private CouponSummary couponSummary;

    @Schema(description = "次卡概览")
    private CardSummary cardSummary;

    @Schema(description = "最近订单")
    private List<OrderBrief> recentOrders = new ArrayList<>();

    @Data
    @Schema(description = "资产账户简要")
    public static class AccountBrief implements Serializable {
        private BigDecimal balance;
        private BigDecimal giftBalance;
        private BigDecimal frozenAmount;
        private Long points;
        private Long growth;
        private BigDecimal totalRecharge;
        private BigDecimal totalGift;
        private BigDecimal totalConsume;
        private Integer totalConsumeCount;
        private LocalDateTime balanceExpireAt;
        @Schema(description = "可用余额合计（本金+赠送）")
        private BigDecimal available;

        @Schema(description = "距下一等级还需成长值（已是最高等级为 null）")
        private Long nextLevelNeedGrowth;
        private String nextLevelName;
        private Long nextLevelId;
        @Schema(description = "当前等级进度 0~1")
        private BigDecimal levelProgress;
    }

    @Data
    @Schema(description = "统计快照")
    public static class StatBrief implements Serializable {
        private LocalDateTime firstConsumeAt;
        private LocalDateTime lastConsumeAt;
        private BigDecimal payAmount30d;
        private BigDecimal payAmount90d;
        private BigDecimal payAmount12m;
        private Integer orderCount12m;
        private Integer visitDays30d;
        private BigDecimal avgOrderAmount;
    }

    @Data
    @Schema(description = "券概览")
    public static class CouponSummary implements Serializable {
        private Integer unused;
        private Integer expiringIn7d;
        private Integer used;
        private Integer expired;
    }

    @Data
    @Schema(description = "次卡概览")
    public static class CardSummary implements Serializable {
        private Integer normal;
        private Integer totalRemainTimes;
    }

    @Data
    @Schema(description = "订单简要")
    public static class OrderBrief implements Serializable {
        private String orderNo;
        private Integer orderType;
        private String orderTypeText;
        private BigDecimal payableAmount;
        private BigDecimal paidAmount;
        private Integer status;
        private String statusText;
        private Long storeId;
        private String storeName;
        private LocalDateTime createdAt;
    }
}
