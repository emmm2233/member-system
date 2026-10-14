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
 * 积分批次（表 m_point_batch）：FIFO 过期依据
 */
@Data
@TableName("m_point_batch")
public class PointBatch implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;
    private String sourceFlowNo;
    private Integer bizType;
    private Long totalPoints;
    private Long usedPoints;
    private Long remainPoints;
    /** null 表示永久有效 */
    private LocalDateTime expireTime;
    /** 1有效 2已用完 3已过期 */
    private Integer status;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
