package com.company.member.modules.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.account.entity.GrowthFlow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 成长值流水 Mapper（只增不改）
 */
@Mapper
public interface GrowthFlowMapper extends BaseMapper<GrowthFlow> {

    @Select("SELECT IFNULL(SUM(change_growth), 0) FROM m_growth_flow WHERE member_id = #{memberId}")
    Long sumByMember(@Param("memberId") Long memberId);

    @Select("SELECT * FROM m_growth_flow WHERE biz_no = #{bizNo} ORDER BY id")
    java.util.List<GrowthFlow> selectByBizNo(@Param("bizNo") String bizNo);

    /**
     * 幂等读侧：同一幂等键 + 同一业务类型的成长值流水是否已存在
     *
     * <p>与 {@code BalanceFlowMapper.countByIdempotency}、{@code PointFlowMapper.countByIdempotency}
     * 保持一致 —— 三张流水表都要能被幂等地「先查后写」，
     * 否则纯成长值调整这类操作只能靠唯一索引抛异常来兜底，用户看到的是天书报错。
     */
    @Select("""
            SELECT COUNT(*) FROM m_growth_flow
             WHERE idempotency_key = #{idempotencyKey} AND biz_type = #{bizType}
            """)
    long countByIdempotency(@Param("idempotencyKey") String idempotencyKey, @Param("bizType") Integer bizType);
}
