package com.company.member.modules.card.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.card.entity.CardVerifyLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CardVerifyLogMapper extends BaseMapper<CardVerifyLog> {

    @Select("SELECT COUNT(1) FROM m_card_verify_log WHERE idempotency_key = #{key}")
    long countByIdempotency(@Param("key") String key);
}
