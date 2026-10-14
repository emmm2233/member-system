package com.company.member.modules.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.account.entity.PointFlow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 积分流水 Mapper（只增不改）
 */
@Mapper
public interface PointFlowMapper extends BaseMapper<PointFlow> {

    @Select("""
            SELECT COUNT(1) FROM m_point_flow
             WHERE idempotency_key = #{idempotencyKey} AND biz_type = #{bizType}
            """)
    long countByIdempotency(@Param("idempotencyKey") String idempotencyKey, @Param("bizType") Integer bizType);

    @Select("SELECT IFNULL(SUM(change_points), 0) FROM m_point_flow WHERE member_id = #{memberId}")
    Long sumByMember(@Param("memberId") Long memberId);

    @Select("SELECT * FROM m_point_flow WHERE biz_no = #{bizNo} ORDER BY id")
    List<PointFlow> selectByBizNo(@Param("bizNo") String bizNo);
}
