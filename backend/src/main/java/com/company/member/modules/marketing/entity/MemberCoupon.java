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
 * 会员券实例（表 m_member_coupon）
 *
 * <p>状态：1未使用 2已锁定 3已使用 4已过期 5已作废
 */
@Data
@TableName("m_member_coupon")
public class MemberCoupon implements Serializable {

    public static final int STATUS_UNUSED = 1;
    public static final int STATUS_LOCKED = 2;
    public static final int STATUS_USED = 3;
    public static final int STATUS_EXPIRED = 4;
    public static final int STATUS_VOID = 5;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String couponNo;
    private Long tplId;
    private Long memberId;

    /** 冗余快照字段 */
    private Integer couponType;
    private String couponName;
    private BigDecimal faceValue;
    private BigDecimal minAmount;
    private BigDecimal maxDiscount;
    private String scopeStore;
    private String scopeProduct;
    private Integer allowStack;
    private String stackGroup;
    private String tplSnapshot;

    private Integer status;
    private String source;
    private String sourceBizNo;

    private LocalDateTime validStart;
    private LocalDateTime validEnd;

    private String lockOrderNo;
    private LocalDateTime lockTime;
    private Long orderId;
    private LocalDateTime usedAt;
    private Long usedStoreId;
    private Long giftToMemberId;
    private String remark;

    private Long tenantId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public boolean usable() {
        return status != null && status == STATUS_UNUSED;
    }

    public static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case STATUS_UNUSED -> "未使用";
            case STATUS_LOCKED -> "使用中";
            case STATUS_USED -> "已使用";
            case STATUS_EXPIRED -> "已过期";
            case STATUS_VOID -> "已作废";
            default -> "未知";
        };
    }
}
