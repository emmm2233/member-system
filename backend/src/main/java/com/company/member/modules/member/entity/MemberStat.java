package com.company.member.modules.member.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 会员统计快照（表 m_member_stat）：等级评定与留存分析的依据
 *
 * <p>注意：列名含数字（pay_amount_30d），MyBatis-Plus 默认驼峰转换无法覆盖，
 * 必须显式声明 {@code @TableField}。
 */
@Data
@TableName("m_member_stat")
public class MemberStat implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;

    private LocalDateTime firstConsumeAt;
    private LocalDateTime lastConsumeAt;

    @TableField("pay_amount_30d")
    private BigDecimal payAmount30d;

    @TableField("pay_amount_90d")
    private BigDecimal payAmount90d;

    @TableField("pay_amount_12m")
    private BigDecimal payAmount12m;

    @TableField("order_count_12m")
    private Integer orderCount12m;

    @TableField("visit_days_30d")
    private Integer visitDays30d;

    private BigDecimal avgOrderAmount;
    private Long consumeStoreId;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
