package com.company.member.modules.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 会员列表查询条件（全部可选，按需组合）
 */
@Data
@Schema(description = "会员查询条件")
public class MemberQuery {

    @Schema(description = "模糊匹配：会员号/手机号/昵称/姓名")
    private String keyword;

    @Schema(description = "手机号（精确或后 4 位）")
    private String phone;

    private Long levelId;

    @Schema(description = "标签 id，逗号分隔（AND 关系）")
    private String tagIds;

    @Schema(description = "状态 1正常 2冻结 3注销")
    private Integer status;

    @Schema(description = "来源")
    private Integer source;

    private Long storeId;

    @Schema(description = "生日月份 1-12（生日营销筛选）")
    private Integer birthdayMonth;

    private BigDecimal minBalance;
    private BigDecimal maxBalance;
    private Long minPoints;
    private Long maxPoints;

    @Schema(description = "最近消费时间起（沉睡会员筛选）")
    private LocalDate lastConsumeStart;
    private LocalDate lastConsumeEnd;

    @Schema(description = "入会时间起")
    private LocalDate createdStart;
    private LocalDate createdEnd;

    @Schema(description = "是否只看有余额的会员")
    private Boolean hasBalance;

    @Schema(description = "排序字段：created_at/balance/points/total_consume/last_consume_at")
    private String sortField = "id";

    @Schema(description = "排序方向：asc/desc")
    private String sortOrder = "desc";

    private Integer pageNum = 1;
    private Integer pageSize = 20;
}
