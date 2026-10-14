package com.company.member.modules.account.entity;

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
 * 充值方案（表 m_recharge_plan）
 */
@Data
@TableName("m_recharge_plan")
public class RechargePlan implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String planName;
    private BigDecimal rechargeAmount;
    private BigDecimal giftAmount;
    private Long giftPoints;
    /** 赠送券模板 id 列表，格式：tplId:qty,tplId:qty */
    private String giftCouponTplIds;
    private Long upgradeLevelId;
    private Long storeId;
    private LocalDateTime validStart;
    private LocalDateTime validEnd;
    private Integer status;
    private Integer sortNo;
    private String remark;
    private Integer deleted;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
