package com.company.member.modules.marketing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 发券命令（三种定向模式）
 *
 * <p>targetType：
 * <ul>
 *   <li>{@code MEMBER} 指定会员（memberIds 生效）</li>
 *   <li>{@code CONDITION} 按条件筛选（condition 生效，服务端实时统计人数）</li>
 *   <li>{@code ALL} 全部正常会员（需二次确认，服务端校验 confirmAll=true）</li>
 * </ul>
 */
@Data
@Schema(description = "发券请求")
public class CouponGrantCmd implements Serializable {

    @NotNull(message = "券模板不能为空")
    private Long tplId;

    @Schema(description = "发放模式 MEMBER / CONDITION / ALL")
    private String targetType = "MEMBER";

    @Schema(description = "指定会员 id 列表（targetType=MEMBER）")
    private List<Long> memberIds = new ArrayList<>();

    @Schema(description = "条件筛选（targetType=CONDITION）")
    private Condition condition;

    @Schema(description = "每人发放数量")
    private Integer qtyPerMember = 1;

    @Schema(description = "是否发送订阅消息")
    private Boolean notify = false;

    @Schema(description = "发放原因（写入操作日志）")
    private String reason;

    @Schema(description = "targetType=ALL 时必须为 true")
    private Boolean confirmAll = false;

    @Data
    @Schema(description = "筛选条件")
    public static class Condition implements Serializable {
        @Schema(description = "等级 id 列表")
        private List<Long> levelIds = new ArrayList<>();
        @Schema(description = "标签 id 列表（AND）")
        private List<Long> tagIds = new ArrayList<>();
        @Schema(description = "状态，默认 1 正常")
        private Integer status = 1;
        @Schema(description = "门店 id")
        private Long storeId;
        @Schema(description = "入会时间起")
        private LocalDate createdStart;
        @Schema(description = "入会时间止")
        private LocalDate createdEnd;
        @Schema(description = "最近消费起（沉睡会员筛选）")
        private LocalDate lastConsumeStart;
        @Schema(description = "最近消费止")
        private LocalDate lastConsumeEnd;
        @Schema(description = "累计消费下限")
        private BigDecimal minTotalConsume;
        @Schema(description = "余额下限")
        private BigDecimal minBalance;
        @Schema(description = "仅生日月")
        private Integer birthdayMonth;
        @Schema(description = "结果上限（保护，最多 50000）")
        private Integer limit = 50000;
    }
}
