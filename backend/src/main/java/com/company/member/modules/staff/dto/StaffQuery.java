package com.company.member.modules.staff.dto;

import lombok.Data;

/**
 * 员工查询条件
 */
@Data
public class StaffQuery {

    /** 工号 / 姓名 / 手机号 模糊匹配 */
    private String keyword;

    private Long storeId;

    /** 0 离职 1 在职；空 = 全部 */
    private Integer status;

    private String position;

    private Integer pageNum = 1;
    private Integer pageSize = 20;
}
