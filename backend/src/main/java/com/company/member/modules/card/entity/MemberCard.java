package com.company.member.modules.card.entity;

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
 * 会员次卡（表 m_member_card）
 *
 * <p>状态：1正常 2已用完 3已过期 4已冻结 5已退卡
 */
@Data
@TableName("m_member_card")
public class MemberCard implements Serializable {

    public static final int STATUS_NORMAL = 1;
    public static final int STATUS_USED_UP = 2;
    public static final int STATUS_EXPIRED = 3;
    public static final int STATUS_FROZEN = 4;
    public static final int STATUS_REFUNDED = 5;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cardNo;
    private Long memberId;
    private Long cardProductId;
    private String cardName;
    private Integer cardType;
    private Long orderId;
    private BigDecimal paidAmount;
    private Integer totalTimes;
    private Integer usedTimes;
    private Integer remainTimes;
    private LocalDateTime validStart;
    private LocalDateTime validEnd;
    private Integer status;
    private Long buyStoreId;
    private String remark;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case STATUS_NORMAL -> "正常";
            case STATUS_USED_UP -> "已用完";
            case STATUS_EXPIRED -> "已过期";
            case STATUS_FROZEN -> "已冻结";
            case STATUS_REFUNDED -> "已退卡";
            default -> "未知";
        };
    }

    public boolean usable() {
        if (status == null || status != STATUS_NORMAL) {
            return false;
        }
        return validEnd == null || LocalDateTime.now().isBefore(validEnd);
    }
}
