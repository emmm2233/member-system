package com.company.member.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录日志（表 m_login_log）
 */
@Data
@TableName("m_login_log")
public class LoginLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 1管理端 2小程序 */
    private Integer loginType;
    private Long userId;
    private Long memberId;
    private String username;
    private String ip;
    private String location;
    private String userAgent;
    /** 0失败 1成功 */
    private Integer status;
    private String msg;

    /**
     * {@code m_login_log.created_at} 是 NOT NULL 且无默认值。
     * 这里显式声明 fill 注解，与其它实体保持一致 ——
     * 否则插入方必须记得 {@code setCreatedAt(now())}，漏一次就是一条
     * "Field 'created_at' doesn't have a default value" 的运行时异常。
     */
    @com.baomidou.mybatisplus.annotation.TableField(value = "created_at",
            fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private LocalDateTime createdAt;
}
