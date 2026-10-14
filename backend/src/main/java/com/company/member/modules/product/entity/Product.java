package com.company.member.modules.product.entity;

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
 * 商品/服务（表 m_product）
 */
@Data
@TableName("m_product")
public class Product implements Serializable {

    public static final int TYPE_GOODS = 1;
    public static final int TYPE_SERVICE = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String productCode;
    private String productName;
    private Long categoryId;
    private Integer productType;

    private BigDecimal price;
    private BigDecimal memberPrice;
    private BigDecimal costPrice;
    private String unit;
    private String image;

    /** 品类积分倍率加成 */
    private BigDecimal pointsRate;
    private Integer joinDiscount;
    private Integer joinPoints;

    private Integer stockEnabled;
    private Integer stockQty;

    private Integer status;
    private Integer sortNo;
    private Long tenantId;
    private Integer deleted;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public boolean onShelf() {
        return status != null && status == 1;
    }

    public boolean discountJoin() {
        return joinDiscount == null || joinDiscount == 1;
    }

    public boolean pointsJoin() {
        return joinPoints == null || joinPoints == 1;
    }

    public BigDecimal pointsRateOrOne() {
        return pointsRate == null ? BigDecimal.ONE : pointsRate;
    }
}
