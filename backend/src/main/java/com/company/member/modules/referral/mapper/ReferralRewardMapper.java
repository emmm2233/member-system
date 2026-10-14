package com.company.member.modules.referral.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.referral.entity.ReferralReward;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReferralRewardMapper extends BaseMapper<ReferralReward> {

    /** 同一推荐关系 + 同一奖励对象是否已建档（奖励发放的幂等前置检查） */
    @Select("SELECT COUNT(*) FROM m_referral_reward WHERE referral_id = #{referralId} AND reward_target = #{target}")
    int countByReferralAndTarget(@Param("referralId") Long referralId, @Param("target") Integer target);

    @Update("""
            UPDATE m_referral_reward
               SET status = 2, issued_at = #{issuedAt}, coupon_id = #{couponId}, fail_reason = NULL
             WHERE id = #{id} AND status <> 2
            """)
    int markIssued(@Param("id") Long id, @Param("issuedAt") LocalDateTime issuedAt,
                   @Param("couponId") Long couponId);

    @Update("UPDATE m_referral_reward SET status = 3, fail_reason = #{reason} WHERE id = #{id} AND status <> 2")
    int markFailed(@Param("id") Long id, @Param("reason") String reason);

    /** 待发放 / 发放失败的记录（补偿任务用） */
    @Select("""
            SELECT * FROM m_referral_reward
             WHERE status IN (1, 3) AND created_at >= #{since}
             ORDER BY id LIMIT #{limit}
            """)
    List<ReferralReward> selectIssuable(@Param("since") LocalDateTime since, @Param("limit") int limit);

    /** 某会员累计获得的推荐奖励值（按类型汇总，用于小程序展示） */
    @Select("""
            SELECT COALESCE(SUM(reward_value), 0) FROM m_referral_reward
             WHERE member_id = #{memberId} AND status = 2
               AND reward_type = #{rewardType} AND reward_target = #{target}
            """)
    BigDecimal sumReward(@Param("memberId") Long memberId, @Param("rewardType") Integer rewardType,
                         @Param("target") Integer target);
}
