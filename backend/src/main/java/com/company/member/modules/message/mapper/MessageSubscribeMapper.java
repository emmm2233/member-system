package com.company.member.modules.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.message.entity.MessageSubscribe;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 订阅消息授权额度
 *
 * <p><b>为什么不用 {@code INSERT ... ON DUPLICATE KEY UPDATE} 单语句</b>：
 * 项目启用了 MyBatis-Plus 的 {@code BlockAttackInnerInterceptor}（防全表更新/删除），
 * 它内置的 JSqlParser 在解析带 ON DUPLICATE KEY 的 INSERT 时会抛
 * {@code UnsupportedOperationException}（且异常信息为空，排查成本很高）。
 * 因此改为「先 UPDATE，影响 0 行再 INSERT，撞唯一键则回头 UPDATE」三步，
 * 语义等价、可移植，也与项目的 SQL 治理规则一致。
 */
@Mapper
public interface MessageSubscribeMapper extends BaseMapper<MessageSubscribe> {

    /**
     * 累加授权额度
     *
     * @return 影响行数；0 表示该会员还没有这个模板的授权记录，调用方应改为插入
     */
    @Update("""
            UPDATE m_message_subscribe
               SET remain_count = remain_count + #{qty},
                   total_count  = total_count + #{qty},
                   tpl_code     = COALESCE(#{tplCode}, tpl_code),
                   last_grant_at = NOW(),
                   updated_at   = NOW()
             WHERE member_id = #{memberId} AND wx_template_id = #{wxTemplateId}
            """)
    int increaseQuota(@Param("memberId") Long memberId, @Param("wxTemplateId") String wxTemplateId,
                      @Param("tplCode") String tplCode, @Param("qty") int qty);

    /**
     * 占额度：仅当剩余次数 &gt; 0 时扣减。
     *
     * <p>条件 UPDATE 是并发安全的：多个线程同时下发只会有一个扣成功，
     * 不会出现「授权 1 次却发出去 3 条」的越权触达。
     *
     * @return 影响行数，0 表示额度不足（调用方应降级渠道）
     */
    @Update("""
            UPDATE m_message_subscribe
               SET remain_count = remain_count - 1, updated_at = NOW()
             WHERE member_id = #{memberId} AND wx_template_id = #{wxTemplateId} AND remain_count > 0
            """)
    int consumeQuota(@Param("memberId") Long memberId, @Param("wxTemplateId") String wxTemplateId);

    /** 归还额度（下发因非用户原因失败时把刚扣的次数还回去，避免额度被白白烧掉） */
    @Update("""
            UPDATE m_message_subscribe
               SET remain_count = remain_count + 1, updated_at = NOW()
             WHERE member_id = #{memberId} AND wx_template_id = #{wxTemplateId}
            """)
    int refundQuota(@Param("memberId") Long memberId, @Param("wxTemplateId") String wxTemplateId);
}
