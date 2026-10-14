package com.company.member.modules.referral.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 推荐关系（表 m_referral）
 *
 * <p>状态流转：1 已绑定 → 2 已结算（触发条件达成后一次性）；异常情况置 3 已失效。
 *
 * <p><b>m_referral.invitee_id 上有唯一索引</b>：一个被推荐人只能被推荐一次，
 * 这是防「一个新人被多个老会员抢绑」的数据库级保证，也是重复收益的最终防线。
 *
 * <p>绑定时间 {@code bindAt} 与结算时间 {@code settleAt} 分开记录，
 * 便于运营分析「绑定→首单」的转化时长。
 */
@Data
@TableName("m_referral")
public class Referral implements Serializable {

    public static final int ST_BOUND = 1;
    public static final int ST_SETTLED = 2;
    public static final int ST_INVALID = 3;

    public static final String CH_POSTER = "POSTER";
    public static final String CH_LINK = "LINK";
    public static final String CH_QRCODE = "QRCODE";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 推荐人会员 id */
    private Long referrerId;
    /** 被推荐人会员 id */
    private Long inviteeId;
    /** 渠道：POSTER 海报 / LINK 链接 / QRCODE 门店码 */
    private String channel;
    private Integer status;
    private LocalDateTime bindAt;
    private LocalDateTime settleAt;
    /** 触发结算的业务单号（首单订单号） */
    private String triggerBizNo;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case ST_BOUND -> "已绑定";
            case ST_SETTLED -> "已结算";
            case ST_INVALID -> "已失效";
            default -> "";
        };
    }
}
