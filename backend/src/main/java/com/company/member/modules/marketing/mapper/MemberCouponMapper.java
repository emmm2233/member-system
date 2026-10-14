package com.company.member.modules.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.marketing.entity.MemberCoupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MemberCouponMapper extends BaseMapper<MemberCoupon> {

    @Select("SELECT * FROM m_member_coupon WHERE coupon_no = #{couponNo} LIMIT 1")
    MemberCoupon selectByCouponNo(@Param("couponNo") String couponNo);

    /** 统计某会员某模板已领取数量（限领校验） */
    @Select("""
            SELECT COUNT(1) FROM m_member_coupon
             WHERE member_id = #{memberId} AND tpl_id = #{tplId}
            """)
    int countByMemberAndTpl(@Param("memberId") Long memberId, @Param("tplId") Long tplId);

    /** 会员可用券（未使用 + 有效期覆盖当前时间） */
    @Select("""
            SELECT * FROM m_member_coupon
             WHERE member_id = #{memberId} AND status = 1
               AND valid_start <= #{now} AND valid_end >= #{now}
             ORDER BY valid_end ASC, id ASC
            """)
    List<MemberCoupon> selectUsable(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);

    /** 券概览统计 */
    @Select("""
            SELECT
              IFNULL(SUM(CASE WHEN status = 1 AND valid_end >= NOW() THEN 1 ELSE 0 END), 0) AS unused,
              IFNULL(SUM(CASE WHEN status = 4 THEN 1 ELSE 0 END), 0)                        AS expired,
              IFNULL(SUM(CASE WHEN status = 3 THEN 1 ELSE 0 END), 0)                        AS used
              FROM m_member_coupon WHERE member_id = #{memberId}
            """)
    java.util.Map<String, Object> summary(@Param("memberId") Long memberId);

    /** 锁定券（并发安全：仅未使用可锁） */
    @Update("""
            UPDATE m_member_coupon
               SET status = 2, lock_order_no = #{orderNo}, lock_time = NOW(), updated_at = NOW()
             WHERE id = #{id} AND status = 1
            """)
    int lock(@Param("id") Long id, @Param("orderNo") String orderNo);

    /** 释放锁定 */
    @Update("""
            UPDATE m_member_coupon
               SET status = 1, lock_order_no = NULL, lock_time = NULL, updated_at = NOW()
             WHERE id = #{id} AND status = 2
            """)
    int unlock(@Param("id") Long id);

    /** 置为已使用（仅未使用/已锁定可核销） */
    @Update("""
            UPDATE m_member_coupon
               SET status = 3, order_id = #{orderId}, used_at = NOW(), used_store_id = #{storeId},
                   lock_order_no = NULL, lock_time = NULL, updated_at = NOW()
             WHERE id = #{id} AND status IN (1, 2)
            """)
    int markUsed(@Param("id") Long id, @Param("orderId") Long orderId, @Param("storeId") Long storeId);

    /** 退单恢复券 */
    @Update("""
            UPDATE m_member_coupon
               SET status = #{status}, order_id = NULL, used_at = NULL, used_store_id = NULL, updated_at = NOW()
             WHERE id = #{id} AND status = 3
            """)
    int restore(@Param("id") Long id, @Param("status") Integer status);

    /** 过期扫描 */
    @Select("""
            SELECT * FROM m_member_coupon
             WHERE status = 1 AND valid_end < #{now} AND id > #{lastId}
             ORDER BY id LIMIT #{limit}
            """)
    List<MemberCoupon> selectExpired(@Param("now") LocalDateTime now,
                                     @Param("lastId") Long lastId, @Param("limit") int limit);

    @Update("UPDATE m_member_coupon SET status = 4, updated_at = NOW() WHERE id = #{id} AND status = 1")
    int markExpired(@Param("id") Long id);

    /** 释放超时未支付的锁 */
    @Update("""
            UPDATE m_member_coupon
               SET status = 1, lock_order_no = NULL, lock_time = NULL, updated_at = NOW()
             WHERE status = 2 AND lock_time < #{before}
            """)
    int unlockTimeout(@Param("before") LocalDateTime before);
}
