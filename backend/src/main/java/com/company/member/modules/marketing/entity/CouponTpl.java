package com.company.member.modules.marketing.entity;

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
 * 优惠券模板（表 m_coupon_tpl）
 *
 * <p>券类型：1满减 2折扣 3代金 4兑换 5次卡
 */
@Data
@TableName("m_coupon_tpl")
public class CouponTpl implements Serializable {

    public static final int TYPE_FULL_CUT = 1;
    public static final int TYPE_DISCOUNT = 2;
    public static final int TYPE_CASH = 3;
    public static final int TYPE_EXCHANGE = 4;
    public static final int TYPE_CARD = 5;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tplCode;
    private String tplName;
    private Integer couponType;
    /** 面额（满减/代金）或折扣值（如 0.88） */
    private BigDecimal faceValue;
    private BigDecimal minAmount;
    private BigDecimal maxDiscount;

    /** 1绝对区间 2领取后N天 */
    private Integer validType;
    private LocalDateTime validStart;
    private LocalDateTime validEnd;
    private Integer validDays;

    private Integer totalQty;
    private Integer issuedQty;
    private Integer usedQty;
    private Integer limitPerMember;

    /** 适用门店 id，-1 表示全部 */
    private String scopeStore;
    private String scopeProduct;
    private String excludeProduct;

    private Integer allowStack;
    private String stackGroup;
    private Integer claimable;
    private String getWay;
    private String useRuleDesc;
    private String image;

    private Integer status;
    private Integer sortNo;
    private Long tenantId;
    private Integer deleted;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static String typeText(Integer type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case TYPE_FULL_CUT -> "满减券";
            case TYPE_DISCOUNT -> "折扣券";
            case TYPE_CASH -> "代金券";
            case TYPE_EXCHANGE -> "兑换券";
            case TYPE_CARD -> "次卡券";
            default -> "优惠券";
        };
    }
}
