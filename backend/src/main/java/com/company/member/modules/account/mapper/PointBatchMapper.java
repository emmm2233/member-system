package com.company.member.modules.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.account.entity.PointBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 积分批次 Mapper：FIFO 扣减与过期扫描
 */
@Mapper
public interface PointBatchMapper extends BaseMapper<PointBatch> {

    /**
     * 取可用批次（按到期时间升序，FOR UPDATE 串行化同一会员的并发扣减）
     * <p>expire_time 为 NULL 视为永久有效，排在最后
     */
    @Select("""
            SELECT * FROM m_point_batch
             WHERE member_id = #{memberId}
               AND status = 1
               AND remain_points > 0
             ORDER BY (expire_time IS NULL), expire_time ASC, id ASC
             FOR UPDATE
            """)
    List<PointBatch> selectUsableForUpdate(@Param("memberId") Long memberId);

    /** 扣减批次 */
    @Update("""
            UPDATE m_point_batch
               SET used_points   = used_points + #{usePoints},
                   remain_points = remain_points - #{usePoints},
                   status        = CASE WHEN remain_points - #{usePoints} <= 0 THEN 2 ELSE status END,
                   updated_at    = NOW()
             WHERE id = #{id} AND remain_points >= #{usePoints}
            """)
    int consume(@Param("id") Long id, @Param("usePoints") Long usePoints);

    /** 批次置过期 */
    @Update("""
            UPDATE m_point_batch
               SET status = 3, used_points = used_points + remain_points, remain_points = 0, updated_at = NOW()
             WHERE id = #{id} AND status = 1
            """)
    int expire(@Param("id") Long id);

    /** 待过期批次扫描（分片游标，避免长事务） */
    @Select("""
            SELECT * FROM m_point_batch
             WHERE status = 1 AND remain_points > 0 AND expire_time IS NOT NULL
               AND expire_time <= #{now} AND id > #{lastId}
             ORDER BY id ASC LIMIT #{limit}
            """)
    List<PointBatch> selectExpired(@Param("now") java.time.LocalDateTime now,
                                   @Param("lastId") Long lastId, @Param("limit") int limit);

    /** 即将过期批次（提醒用） */
    @Select("""
            SELECT * FROM m_point_batch
             WHERE member_id = #{memberId} AND status = 1 AND remain_points > 0
               AND expire_time IS NOT NULL
               AND expire_time BETWEEN NOW() AND #{deadline}
             ORDER BY expire_time ASC
            """)
    List<PointBatch> selectExpiringSoon(@Param("memberId") Long memberId,
                                        @Param("deadline") java.time.LocalDateTime deadline);

    /** 批次余量合计（对账用）：统计全部状态，终态批次余量已为 0 */
    @Select("SELECT IFNULL(SUM(remain_points), 0) FROM m_point_batch WHERE member_id = #{memberId}")
    Long sumRemainByMember(@Param("memberId") Long memberId);
}
