package com.company.member.modules.member.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会员标签（表 m_member_tag）
 */
@Data
@TableName("m_member_tag")
public class MemberTag implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tagName;
    private String tagColor;
    private String tagGroup;
    private String autoRule;
    private Integer status;
    private Integer sortNo;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
