package com.company.member.modules.product.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 商品分类（表 m_product_category）
 */
@Data
@TableName("m_product_category")
public class ProductCategory implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId;
    private String cateName;
    /** 1商品 2服务 */
    private Integer cateType;
    private Integer sortNo;
    private Integer status;
    private Integer deleted;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
