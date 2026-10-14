package com.company.member.modules.staff.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 员工新增/编辑入参
 *
 * <p>{@code staffNo} 留空时由服务端按 E001/E002… 规则生成；
 * 手工指定时必须全局唯一（uk_staff_no 兜底）。
 *
 * <p><b>{@code storeId} 是必填的</b>：{@code m_staff.store_id} 在 DDL 里是 NOT NULL，
 * 而且门店是数据权限锚点 —— 一个不属于任何门店的员工，其账号的数据权限会落在空门店上，
 * 后续「本店」范围的查询都会查不到东西。连锁总部的员工请挂在总部门店。
 */
@Data
public class StaffSaveCmd {

    @Size(max = 32, message = "工号过长")
    private String staffNo;

    @NotBlank(message = "员工姓名不能为空")
    @Size(max = 50, message = "姓名过长")
    private String staffName;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotNull(message = "所属门店不能为空")
    private Long storeId;

    @Size(max = 50, message = "岗位过长")
    private String position;

    private LocalDate entryDate;
    private LocalDate leaveDate;

    /** 状态：0 离职 1 在职；留空默认在职 */
    private Integer status;

    /** 同时关联的登录账号（可空；填了就一并绑定） */
    private Long userId;
}
