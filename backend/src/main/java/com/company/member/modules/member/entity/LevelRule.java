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
 * 等级评定规则（表 m_level_rule）
 *
 * <p>rule_type：1 升级规则 / 2 保级规则
 * <p>rule_json 示例：{@code {"items":[{"field":"growth","op":">=","value":3000}]}}
 */
@Data
@TableName("m_level_rule")
public class LevelRule implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long levelId;
    /** 1升级 2保级 */
    private Integer ruleType;
    /** AND / OR */
    private String logicType;
    private String ruleJson;
    private Integer priority;
    private Integer status;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
