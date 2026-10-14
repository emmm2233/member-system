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
 * 会员-标签关系（表 m_member_tag_rel）
 */
@Data
@TableName("m_member_tag_rel")
public class MemberTagRel implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;
    private Long tagId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
