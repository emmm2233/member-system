package com.company.member.modules.birthday.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 生日礼发放记录（表 m_birthday_gift_log）
 *
 * <p><b>(member_id, gift_year) 唯一索引是「一年只送一次」的最终保证</b>：
 * 定时任务重跑、多实例并发、管理员手工补发都不会让同一个会员在同一年拿到两份生日礼。
 *
 * <p>{@code gift_date} 记录的是「这次生日礼对应的那个生日」（可能因为提前 N 天发放
 * 而落在明年 1 月），{@code gift_year} 取该生日的年份——按生日年归集比按自然年归集
 * 更符合会员与运营的直觉。
 */
@Data
@TableName("m_birthday_gift_log")
public class BirthdayGiftLog implements Serializable {

    public static final int ST_ISSUED = 1;
    /** 部分失败：例如积分发了但券模板下架，仍需留痕以便人工补 */
    public static final int ST_PARTIAL = 2;
    public static final int ST_FAILED = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;
    /** 生日礼归属年份 */
    private Integer giftYear;
    /** 对应生日日期 */
    private LocalDate giftDate;
    /** 发放时会员等级（用于分析不同等级生日礼的核销差异） */
    private Long levelId;

    private Long points;
    private BigDecimal balance;
    private Long growth;
    private Long couponTplId;
    private Long couponId;

    private Integer status;
    private String failReason;
    private Integer messageSent;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case ST_ISSUED -> "已发放";
            case ST_PARTIAL -> "部分发放";
            case ST_FAILED -> "发放失败";
            default -> "";
        };
    }
}
