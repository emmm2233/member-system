package com.company.member.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 含「逻辑删除 + 租户」的实体基类
 *
 * <p>仅用于同时具备 {@code deleted} 与 {@code tenant_id} 的主数据表
 * （会员、商品、券模板、次卡产品、门店、员工、系统用户等）。
 * 流水与订单表没有 deleted 字段，直接用普通实体，不继承本类。
 */
@Data
public abstract class BaseEntity implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(value = "deleted")
    private Integer deleted = 0;

    /** 租户 id（预留，本期恒为 0） */
    @TableField(value = "tenant_id")
    private Long tenantId = 0L;
}
