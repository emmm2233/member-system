package com.company.member.modules.referral.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.referral.entity.Referral;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReferralMapper extends BaseMapper<Referral> {

    @Select("SELECT * FROM m_referral WHERE invitee_id = #{inviteeId} LIMIT 1")
    Referral selectByInvitee(@Param("inviteeId") Long inviteeId);

    /**
     * 乐观锁定结算权：只有把状态从 1 改成 2 的那个线程才发奖。
     *
     * <p>返回 0 表示「已经有人结算过了」，调用方必须直接返回，不得发奖。
     * 这是推荐奖励「一笔只发一次」的最终防线（比先查后改更可靠）。
     */
    @Update("""
            UPDATE m_referral
               SET status = 2, settle_at = #{settleAt}, trigger_biz_no = #{bizNo}
             WHERE id = #{id} AND status = 1
            """)
    int markSettled(@Param("id") Long id, @Param("settleAt") LocalDateTime settleAt,
                    @Param("bizNo") String bizNo);

    @Update("UPDATE m_referral SET status = 3 WHERE id = #{id} AND status = 1")
    int markInvalid(@Param("id") Long id);

    /** 推荐人本月已结算单数（用于月度上限防刷） */
    @Select("""
            SELECT COUNT(*) FROM m_referral
             WHERE referrer_id = #{referrerId} AND status = 2
               AND settle_at >= #{monthStart}
            """)
    int countSettledThisMonth(@Param("referrerId") Long referrerId,
                              @Param("monthStart") LocalDateTime monthStart);

    /** 推荐人的有效邀请数（已绑定，不含失效） */
    @Select("SELECT COUNT(*) FROM m_referral WHERE referrer_id = #{referrerId} AND status <> 3")
    int countBound(@Param("referrerId") Long referrerId);

    @Select("""
            SELECT COUNT(*) FROM m_referral
             WHERE referrer_id = #{referrerId} AND status = #{status}
            """)
    int countByReferrerAndStatus(@Param("referrerId") Long referrerId, @Param("status") Integer status);

    /** 待结算的推荐关系（补偿任务用：事件丢失时兜底） */
    @Select("SELECT * FROM m_referral WHERE status = 1 ORDER BY id LIMIT #{limit}")
    List<Referral> selectPending(@Param("limit") int limit);

    /** 近期已结算的推荐关系（对账任务用：确保奖励记录不缺失） */
    @Select("""
            SELECT * FROM m_referral
             WHERE status = 2 AND settle_at >= #{since}
             ORDER BY id DESC LIMIT #{limit}
            """)
    List<Referral> selectRecentSettled(@Param("since") LocalDateTime since, @Param("limit") int limit);

    /* ---------- 跨表查询：首单判定 ---------- */

    /**
     * 已支付订单数（用于判定「首单」）
     *
     * <p>状态 3已支付 / 4部分退款 / 5已退款 都算「曾经支付成功」——
     * 若用 status=3 判定，一笔已退款的订单会让「首单」永远成立，造成重复发奖。
     */
    @Select("""
            SELECT COUNT(*) FROM m_order
             WHERE member_id = #{memberId} AND order_type = #{orderType} AND status IN (3,4,5)
            """)
    int countPaidOrders(@Param("memberId") Long memberId, @Param("orderType") Integer orderType);

    /** 首笔已支付订单的实付金额（补偿任务判定门槛用；无订单返回 null） */
    @Select("""
            SELECT paid_amount FROM m_order
             WHERE member_id = #{memberId} AND order_type = #{orderType} AND status IN (3,4,5)
             ORDER BY id ASC LIMIT 1
            """)
    BigDecimal selectFirstPaidAmount(@Param("memberId") Long memberId, @Param("orderType") Integer orderType);
}
