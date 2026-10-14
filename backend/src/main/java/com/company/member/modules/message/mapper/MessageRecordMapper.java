package com.company.member.modules.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.message.entity.MessageRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MessageRecordMapper extends BaseMapper<MessageRecord> {

    @Select("SELECT * FROM m_message_record WHERE idempotency_key = #{key} LIMIT 1")
    MessageRecord selectByIdemKey(@Param("key") String key);

    /** 取出到期待重试记录（游标分批，避免一次锁太多行） */
    @Select("""
            SELECT * FROM m_message_record
             WHERE status = 3 AND retry_count < #{maxRetry}
               AND (next_retry_at IS NULL OR next_retry_at <= NOW())
             ORDER BY id LIMIT #{limit}
            """)
    List<MessageRecord> selectRetryable(@Param("maxRetry") int maxRetry, @Param("limit") int limit);
}
