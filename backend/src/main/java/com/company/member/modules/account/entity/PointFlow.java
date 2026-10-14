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
 * 积分流水（表 m_point_flow）
 */
@Data
@TableName("m_point_flow")
public class PointFlow implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String flowNo;
    private Long memberId;
    /** 1消费获得 2充值赠送 3兑换扣减 4过期 5调整 6退单扣回 7升级赠送 */
    private Integer bizType;

    private Long changePoints;
    private Long beforePoints;
    private Long afterPoints;

    private String bizNo;
    private Long bizId;
    private Long storeId;
    private Long operatorId;
    private LocalDateTime expireTime;
    private Long batchId;
    private String idempotencyKey;
    private String remark;

    private LocalDateTime occurTime;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
