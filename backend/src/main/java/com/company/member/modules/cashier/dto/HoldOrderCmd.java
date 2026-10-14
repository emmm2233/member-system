package com.company.member.modules.cashier.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 挂单请求
 *
 * <p>挂单只保存"待结算的购物车快照"，不产生任何资产变动；
 * 取单时按快照重建购物车，弃单则标记取消。
 */
@Data
@Schema(description = "挂单请求")
public class HoldOrderCmd {

    @Schema(description = "会员 id（散客挂单可为空）")
    private Long memberId;

    @Schema(description = "会员名快照（散客留空，用于挂单列表展示）")
    private String memberName;

    @NotEmpty(message = "挂单明细不能为空")
    @Schema(description = "明细")
    private List<Item> items = new ArrayList<>();

    @Schema(description = "门店 id")
    private Long storeId;

    @Schema(description = "班次号")
    private String shiftNo;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "幂等键（请求头优先）")
    private String idempotencyKey;

    @Data
    @Schema(description = "挂单明细项")
    public static class Item {
        private Integer itemType;
        private Long productId;
        private String productName;
        private BigDecimal price;
        private BigDecimal qty;
    }
}
