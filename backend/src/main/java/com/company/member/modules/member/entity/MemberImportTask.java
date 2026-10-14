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
 * 会员导入/导出任务（表 m_member_import_task）
 */
@Data
@TableName("m_member_import_task")
public class MemberImportTask implements Serializable {

    public static final int STATUS_PENDING = 1;
    public static final int STATUS_RUNNING = 2;
    public static final int STATUS_SUCCESS = 3;
    public static final int STATUS_PARTIAL = 4;
    public static final int STATUS_FAILED = 5;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskNo;
    private String fileName;
    private String fileUrl;
    private Integer totalRows;
    private Integer successRows;
    private Integer failRows;
    private Integer status;
    private String errorFileUrl;
    private String errorMsg;
    private Long operatorId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;

    public static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case STATUS_PENDING -> "待处理";
            case STATUS_RUNNING -> "处理中";
            case STATUS_SUCCESS -> "全部成功";
            case STATUS_PARTIAL -> "部分失败";
            case STATUS_FAILED -> "失败";
            default -> "未知";
        };
    }
}
