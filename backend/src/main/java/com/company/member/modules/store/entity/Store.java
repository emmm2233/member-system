package com.company.member.modules.store.entity;

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
 * 门店（表 m_store）
 *
 * <p>注意 {@code created_at} / {@code updated_at} 在 DDL 里是
 * <b>NOT NULL 且无默认值</b>，必须由 {@link MybatisMetaObjectHandler} 自动填充；
 * 漏了 {@code fill} 注解的话 insert 会直接报
 * {@code Field 'created_at' doesn't have a default value} —— 而这条报错
 * 来自数据库驱动，报错信息里不会告诉你「是你实体少了个注解」。
 */
@Data
@TableName("m_store")
public class Store implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String storeCode;
    private String storeName;
    private String province;
    private String city;
    private String district;
    private String address;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String contactName;
    private String contactPhone;
    private String businessHours;
    private Long managerId;
    private Integer status;
    private Integer sortNo;
    private Long tenantId;
    private Integer deleted;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
