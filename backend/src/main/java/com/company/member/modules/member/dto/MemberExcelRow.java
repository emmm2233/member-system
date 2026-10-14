package com.company.member.modules.member.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

/**
 * 会员导入/导出 Excel 行模型
 *
 * <p>导入时按列名匹配（第 1 行为表头）；导出时按同一模型写出，保证导入导出的模板完全一致。
 */
@Data
public class MemberExcelRow {

    @ColumnWidth(16)
    @ExcelProperty("手机号")
    private String phone;

    @ColumnWidth(12)
    @ExcelProperty("姓名")
    private String realName;

    @ColumnWidth(14)
    @ExcelProperty("昵称")
    private String nickname;

    @ColumnWidth(8)
    @ExcelProperty("性别")
    private String gender;

    @ColumnWidth(14)
    @ExcelProperty("生日")
    private String birthday;

    @ColumnWidth(10)
    @ExcelProperty("来源")
    private String source;

    @ColumnWidth(16)
    @ExcelProperty("推荐人手机号")
    private String referrerPhone;

    @ColumnWidth(12)
    @ExcelProperty("标签")
    private String tags;

    @ColumnWidth(24)
    @ExcelProperty("备注")
    private String remark;

    /* ===== 以下字段仅导出时填充 ===== */

    @ColumnWidth(20)
    @ExcelProperty("会员号")
    private String memberNo;

    @ColumnWidth(12)
    @ExcelProperty("等级")
    private String levelName;

    @ColumnWidth(12)
    @ExcelProperty("本金余额")
    private String balance;

    @ColumnWidth(12)
    @ExcelProperty("赠送余额")
    private String giftBalance;

    @ColumnWidth(10)
    @ExcelProperty("积分")
    private String points;

    @ColumnWidth(10)
    @ExcelProperty("成长值")
    private String growth;

    @ColumnWidth(12)
    @ExcelProperty("累计消费")
    private String totalConsume;

    @ColumnWidth(10)
    @ExcelProperty("消费笔数")
    private String totalConsumeCount;

    @ColumnWidth(20)
    @ExcelProperty("入会时间")
    private String createdAt;

    @ColumnWidth(10)
    @ExcelProperty("状态")
    private String statusText;

    /* ===== 导入时的错误明细（导出失败行用） ===== */

    @ColumnWidth(30)
    @ExcelProperty("失败原因")
    private String errorMsg;

    @ColumnWidth(10)
    @ExcelProperty("原行号")
    private String rowNum;
}
