package com.company.member.modules.marketing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 券模板保存命令
 */
@Data
@Schema(description = "券模板保存")
public class CouponTplSaveCmd implements Serializable {

    @Schema(description = "模板 id（编辑时必填）")
    private Long id;

    @NotBlank(message = "券名称不能为空")
    private String tplName;

    @Schema(description = "模板编码（留空自动生成）")
    private String tplCode;

    @NotNull(message = "券类型不能为空")
    @Schema(description = "1满减 2折扣 3代金 4兑换 5次卡")
    private Integer couponType;

    @Schema(description = "面额（满减/代金）或折扣率（折扣券，如 0.88）")
    private BigDecimal faceValue;

    @Schema(description = "最低消费金额")
    private BigDecimal minAmount;

    @Schema(description = "折扣券最高优惠额")
    private BigDecimal maxDiscount;

    @Schema(description = "有效期类型 1绝对区间 2领取后N天")
    private Integer validType = 2;

    private LocalDateTime validStart;
    private LocalDateTime validEnd;
    private Integer validDays;

    @Schema(description = "发行总量 -1 不限")
    private Integer totalQty = -1;

    @Schema(description = "每人限领数量")
    private Integer limitPerMember = 1;

    @Schema(description = "适用门店 id 列表，空或含 -1 表示全部")
    private List<Long> scopeStoreIds = new ArrayList<>();

    @Schema(description = "适用商品 id 列表，空或含 -1 表示全部")
    private List<Long> scopeProductIds = new ArrayList<>();

    @Schema(description = "排除商品 id 列表")
    private List<Long> excludeProductIds = new ArrayList<>();

    @Schema(description = "是否可叠加")
    private Boolean allowStack = false;

    @Schema(description = "互斥组")
    private String stackGroup;

    @Schema(description = "是否可在小程序自助领取")
    private Boolean claimable = false;

    @Schema(description = "使用规则说明")
    private String useRuleDesc;

    @Schema(description = "券图")
    private String image;

    @Schema(description = "状态 0停用 1启用")
    private Integer status = 1;

    private Integer sortNo = 0;
}
