package com.company.member.modules.card.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.card.entity.MemberCardItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MemberCardItemMapper extends BaseMapper<MemberCardItem> {

    @Select("SELECT * FROM m_member_card_item WHERE member_card_id = #{cardId} ORDER BY sort_no, id")
    List<MemberCardItem> selectByCardId(@Param("cardId") Long cardId);

    /** 行锁读取权益项 */
    @Select("SELECT * FROM m_member_card_item WHERE id = #{id} FOR UPDATE")
    MemberCardItem selectForUpdate(@Param("id") Long id);

    /** 权益项核销扣次（SQL 层防超扣） */
    @Update("""
            UPDATE m_member_card_item
               SET used_times = used_times + #{times},
                   remain_times = remain_times - #{times},
                   status = CASE WHEN remain_times - #{times} <= 0 THEN 2 ELSE status END,
                   updated_at = NOW()
             WHERE id = #{id} AND status = 1 AND remain_times >= #{times}
            """)
    int verifyTimes(@Param("id") Long id, @Param("times") int times);

    @Update("UPDATE m_member_card_item SET status = 3, updated_at = NOW() WHERE member_card_id = #{cardId} AND status = 1")
    int expireByCardId(@Param("cardId") Long cardId);

    @Update("UPDATE m_member_card_item SET status = #{status}, updated_at = NOW() WHERE member_card_id = #{cardId}")
    int changeStatusByCardId(@Param("cardId") Long cardId, @Param("status") Integer status);
}
