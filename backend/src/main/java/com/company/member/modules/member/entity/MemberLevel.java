package com.company.member.modules.member.entity;

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
 * 会员等级定义（表 m_member_level）
 */
@Data
@TableName("m_member_level")
public class MemberLevel implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String levelCode;
    private String levelName;
    /** 等级序号，越大越高 */
    private Integer levelValue;
    private String icon;
    /** 商品折扣率：0.9 = 九折 */
    private BigDecimal discountRate;
    /** 积分倍率 */
    private BigDecimal pointsRate;
    /** 次卡/服务折扣率 */
    private BigDecimal cardDiscountRate;
    private Long birthdayCouponTplId;
    /** 升级赠送积分 */
    private Long upgradeGiftPoints;
    /** 是否默认等级（新会员） */
    private Integer isDefault;
    /** 等级有效期（月），0=永久 */
    private Integer validMonths;
    private Integer status;
    private Integer sortNo;
    private String remark;
    private Integer deleted;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public BigDecimal discountRateOrOne() {
        return discountRate == null ? BigDecimal.ONE : discountRate;
    }

    public BigDecimal pointsRateOrOne() {
        return pointsRate == null ? BigDecimal.ONE : pointsRate;
    }
}
