package com.company.member.modules.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.account.entity.BalanceFlow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * 余额流水 Mapper（只增不改）
 */
@Mapper
public interface BalanceFlowMapper extends BaseMapper<BalanceFlow> {

    /** 按幂等键判断是否已处理（第三道防线的读侧） */
    @Select("""
            SELECT COUNT(1) FROM m_balance_flow
             WHERE idempotency_key = #{idempotencyKey} AND biz_type = #{bizType}
            """)
    long countByIdempotency(@Param("idempotencyKey") String idempotencyKey, @Param("bizType") Integer bizType);

    /** 流水合计（对账用） */
    @Select("""
            SELECT IFNULL(SUM(change_amount), 0) FROM m_balance_flow
             WHERE member_id = #{memberId} AND account_type = #{accountType}
            """)
    BigDecimal sumByMemberAndType(@Param("memberId") Long memberId, @Param("accountType") Integer accountType);

    /** 按业务单号查流水（退单反查原单、排障用） */
    @Select("SELECT * FROM m_balance_flow WHERE biz_no = #{bizNo} ORDER BY id")
    java.util.List<BalanceFlow> selectByBizNo(@Param("bizNo") String bizNo);
}
