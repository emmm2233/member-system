package com.company.member.modules.birthday.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.birthday.entity.BirthdayGiftLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface BirthdayGiftLogMapper extends BaseMapper<BirthdayGiftLog> {

    @Select("SELECT * FROM m_birthday_gift_log WHERE member_id = #{memberId} AND gift_year = #{year} LIMIT 1")
    BirthdayGiftLog selectByMemberYear(@Param("memberId") Long memberId, @Param("year") Integer year);

    /** 最近一次生日礼（小程序展示「您的生日礼」用） */
    @Select("SELECT * FROM m_birthday_gift_log WHERE member_id = #{memberId} ORDER BY id DESC LIMIT 1")
    BirthdayGiftLog selectLatest(@Param("memberId") Long memberId);

    /** 发放失败/部分发放的记录（补偿任务用） */
    @Select("""
            SELECT * FROM m_birthday_gift_log
             WHERE status IN (2,3) AND created_at >= #{since}
             ORDER BY id LIMIT #{limit}
            """)
    java.util.List<BirthdayGiftLog> selectRepairable(@Param("since") LocalDateTime since,
                                                     @Param("limit") int limit);

    @Update("UPDATE m_birthday_gift_log SET message_sent = 1 WHERE id = #{id}")
    int markMessageSent(@Param("id") Long id);
}
