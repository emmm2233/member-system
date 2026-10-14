package com.company.member.modules.referral.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 推荐奖励发放记录（表 m_referral_reward）
 *
 * <p>一笔推荐关系最多产生两条：一条给推荐人（{@code rewardTarget=1}），
 * 一条给被推荐人（{@code rewardTarget=2}）。
 *
 * <p><b>发放与记账分离</b>：先插记录（status=1 待发放）再调资产服务，
 * 成功后置 2 已发放、失败置 3 发放失败并留 fail_reason。
 * 这样「奖励该不该发」与「发成功了没有」是两个可独立排查的事实，
 * 也是奖励补偿任务的数据来源。
 */
@Data
@TableName("m_referral_reward")
public class ReferralReward implements Serializable {

    public static final int TARGET_REFERRER = 1;
    public static final int TARGET_INVITEE = 2;

    public static final int TYPE_POINTS = 1;
    public static final int TYPE_BALANCE = 2;
    public static final int TYPE_COUPON = 3;
    public static final int TYPE_GROWTH = 4;
    public static final int TYPE_GIFT_BALANCE = 5;

    public static final int ST_PENDING = 1;
    public static final int ST_ISSUED = 2;
    public static final int ST_FAILED = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long referralId;
    /** 奖励对象：1 推荐人 2 被推荐人 */
    private Integer rewardTarget;
    private Long memberId;
    /** 奖励类型：1 积分 2 余额 3 券 4 成长值 5 赠送余额 */
    private Integer rewardType;
    private BigDecimal rewardValue;
    private Long couponTplId;
    private Long couponId;
    private Integer status;
    private String failReason;
    private String bizNo;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime issuedAt;

    public static String typeText(Integer type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case TYPE_POINTS -> "积分";
            case TYPE_BALANCE -> "余额";
            case TYPE_COUPON -> "优惠券";
            case TYPE_GROWTH -> "成长值";
            case TYPE_GIFT_BALANCE -> "赠送余额";
            default -> "";
        };
    }

    public static String targetText(Integer target) {
        if (target == null) {
            return "";
        }
        return target == TARGET_REFERRER ? "推荐人" : "被推荐人";
    }

    public static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case ST_PENDING -> "待发放";
            case ST_ISSUED -> "已发放";
            case ST_FAILED -> "发放失败";
            default -> "";
        };
    }
}
