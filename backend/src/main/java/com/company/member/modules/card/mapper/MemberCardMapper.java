package com.company.member.modules.card.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.card.entity.MemberCard;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface MemberCardMapper extends BaseMapper<MemberCard> {

    @Select("SELECT * FROM m_member_card WHERE card_no = #{cardNo} LIMIT 1")
    MemberCard selectByCardNo(@Param("cardNo") String cardNo);

    /** 行锁读取（核销/退卡时使用） */
    @Select("SELECT * FROM m_member_card WHERE card_no = #{cardNo} FOR UPDATE")
    MemberCard selectForUpdateByCardNo(@Param("cardNo") String cardNo);

    /**
     * 核销扣次：卡与权益项同时扣减
     *
     * <p>SQL 层校验剩余次数与状态，作为并发超扣的最终防线。
     */
    @Update("""
            UPDATE m_member_card
               SET used_times = used_times + #{times},
                   remain_times = remain_times - #{times},
                   status = CASE WHEN remain_times - #{times} <= 0 THEN 2 ELSE status END,
                   updated_at = NOW()
             WHERE id = #{id} AND status = 1 AND remain_times >= #{times}
            """)
    int verifyTimes(@Param("id") Long id, @Param("times") int times);

    /** 过期扫描 */
    @Select("""
            SELECT * FROM m_member_card
             WHERE status = 1 AND valid_end IS NOT NULL AND valid_end < #{now} AND id > #{lastId}
             ORDER BY id LIMIT #{limit}
            """)
    java.util.List<MemberCard> selectExpired(@Param("now") LocalDateTime now,
                                             @Param("lastId") Long lastId,
                                             @Param("limit") int limit);

    @Update("UPDATE m_member_card SET status = 3, updated_at = NOW() WHERE id = #{id} AND status = 1")
    int markExpired(@Param("id") Long id);

    @Update("UPDATE m_member_card SET status = #{status}, remark = #{remark}, updated_at = NOW() WHERE id = #{id}")
    int changeStatus(@Param("id") Long id, @Param("status") Integer status, @Param("remark") String remark);

    @Update("UPDATE m_member_card SET valid_end = DATE_ADD(valid_end, INTERVAL #{days} DAY), updated_at = NOW() WHERE id = #{id}")
    int extendValidEnd(@Param("id") Long id, @Param("days") int days);
}
