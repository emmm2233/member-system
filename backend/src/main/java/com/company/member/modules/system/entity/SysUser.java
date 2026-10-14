package com.company.member.modules.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统用户（表 m_sys_user）
 */
@Data
@TableName("m_sys_user")
public class SysUser implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String password;
    private String realName;
    private String phone;
    private Long staffId;
    private Long storeId;
    /** 1全部 2本店 3本人 4自定义 */
    private Integer dataScope;
    private String scopeStoreIds;
    /** 0停用 1启用 */
    private Integer status;
    private Integer tokenVersion;
    private LocalDateTime lastLoginAt;
    private String lastLoginIp;
    private Long tenantId;
    private Integer deleted;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
