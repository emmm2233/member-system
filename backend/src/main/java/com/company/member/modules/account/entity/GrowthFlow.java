package com.company.member.modules.account.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 成长值流水（表 m_growth_flow）
 */
@Data
@TableName("m_growth_flow")
public class GrowthFlow implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String flowNo;
    private Long memberId;
    /** 1消费获得 2调整 3年度清零 4退单扣回 5活动 */
    private Integer bizType;

    private Long changeGrowth;
    private Long beforeGrowth;
    private Long afterGrowth;

    private String bizNo;
    /**
     * 幂等键
     *
     * <p>与 {@code m_balance_flow} / {@code m_point_flow} 同构，配
     * {@code uk_idem(idempotency_key, biz_type)} 唯一索引。
     *
     * <p>成长值同样是资产（决定等级与权益），而这张表此前既没有这一列、
     * 也没有唯一索引 —— 意味着纯成长值调整重复提交会真的加两次。
     * 第 1 批把「三张流水表统一做幂等读侧检查」时，缺列直接导致结算回滚，
     * 才把这个结构性不一致暴露出来。
     */
    private String idempotencyKey;
    private Long operatorId;
    private String remark;

    private LocalDateTime occurTime;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
