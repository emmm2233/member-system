package com.company.member.modules.member.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 会员主档（表 m_member）
 */
@Data
@TableName("m_member")
public class Member implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会员号 M+yyyyMMdd+6位序列 */
    private String memberNo;
    /** 手机号（会员唯一识别，可空） */
    private String phone;
    /** 微信小程序 openid */
    private String openid;
    /** 微信开放平台 unionid */
    private String unionid;

    private String nickname;
    private String avatar;
    private String realName;
    /** 0未知 1男 2女 */
    private Integer gender;
    private LocalDate birthday;

    private Long levelId;
    private LocalDateTime levelExpireAt;
    /** 1正常 2冻结 3注销 */
    private Integer status;
    /** 1门店建档 2小程序注册 3批量导入 4推荐注册 */
    private Integer source;
    private Long referrerId;
    private Long storeId;
    private Long creatorId;
    private String remark;

    private Long tenantId;
    private Integer deleted;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public boolean isNormal() {
        return status != null && status == 1;
    }

    public boolean isFrozen() {
        return status != null && status == 2;
    }

    public boolean isCancelled() {
        return status != null && status == 3;
    }

    public boolean hasPhone() {
        return phone != null && !phone.isBlank();
    }
}
