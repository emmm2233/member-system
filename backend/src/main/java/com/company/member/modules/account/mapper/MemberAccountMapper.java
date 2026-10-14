package com.company.member.modules.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.account.entity.MemberAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 资产账户 Mapper
 *
 * <p>所有变动方法都带 SQL 层条件（余额充足 / 版本一致），作为并发超扣的最终防线。
 */
@Mapper
public interface MemberAccountMapper extends BaseMapper<MemberAccount> {

    /** 行锁读取（事务内使用） */
    @Select("SELECT * FROM m_member_account WHERE member_id = #{memberId} FOR UPDATE")
    MemberAccount selectForUpdate(@Param("memberId") Long memberId);

    @Select("SELECT * FROM m_member_account WHERE member_id = #{memberId}")
    MemberAccount selectByMemberId(@Param("memberId") Long memberId);

    /**
     * 充值：本金 + 赠送 + 累计充值
     *
     * @return 影响行数，0 表示版本冲突或账户不存在
     */
    @Update("""
            UPDATE m_member_account
               SET balance        = balance + #{principal},
                   gift_balance   = gift_balance + #{gift},
                   total_recharge = total_recharge + #{principal},
                   total_gift     = total_gift + #{gift},
                   version        = version + 1,
                   updated_at     = NOW()
             WHERE id = #{id} AND version = #{version}
            """)
    int recharge(@Param("id") Long id, @Param("principal") BigDecimal principal, @Param("gift") BigDecimal gift,
                 @Param("version") Integer version);

    /**
     * 扣减余额（本金/赠送拆分由应用层算好）
     *
     * @return 0 表示余额不足或版本冲突
     */
    @Update("""
            UPDATE m_member_account
               SET balance             = balance - #{principal},
                   gift_balance        = gift_balance - #{gift},
                   total_consume       = total_consume + #{principal} + #{gift},
                   total_consume_count = total_consume_count + 1,
                   version             = version + 1,
                   updated_at          = NOW()
             WHERE id = #{id}
               AND balance >= #{principal}
               AND gift_balance >= #{gift}
               AND version = #{version}
            """)
    int deduct(@Param("id") Long id, @Param("principal") BigDecimal principal, @Param("gift") BigDecimal gift,
               @Param("version") Integer version);

    /** 退款：原路退回指定账户类型 */
    @Update("""
            UPDATE m_member_account
               SET balance      = balance + #{principal},
                   gift_balance = gift_balance + #{gift},
                   total_consume = GREATEST(total_consume - #{principal} - #{gift}, 0),
                   version      = version + 1,
                   updated_at   = NOW()
             WHERE id = #{id} AND version = #{version}
            """)
    int refund(@Param("id") Long id, @Param("principal") BigDecimal principal, @Param("gift") BigDecimal gift,
               @Param("version") Integer version);

    /** 赠送余额扣回（充值撤销/退单），可同时扣减本金，允许扣到 0 且不小于 0 */
    @Update("""
            UPDATE m_member_account
               SET balance        = balance - #{principal},
                   gift_balance   = gift_balance - #{gift},
                   total_recharge = GREATEST(total_recharge - #{principal}, 0),
                   total_gift     = GREATEST(total_gift - #{gift}, 0),
                   version        = version + 1,
                   updated_at     = NOW()
             WHERE id = #{id}
               AND balance >= #{principal}
               AND gift_balance >= #{gift}
               AND version = #{version}
            """)
    int revokeRecharge(@Param("id") Long id, @Param("principal") BigDecimal principal, @Param("gift") BigDecimal gift,
                       @Param("version") Integer version);

    /** 手动调整余额（可正可负），带下限保护 */
    @Update("""
            UPDATE m_member_account
               SET balance      = balance + #{balanceDelta},
                   gift_balance = gift_balance + #{giftDelta},
                   version      = version + 1,
                   updated_at   = NOW()
             WHERE id = #{id}
               AND balance + #{balanceDelta} >= 0
               AND gift_balance + #{giftDelta} >= 0
               AND version = #{version}
            """)
    int adjustBalance(@Param("id") Long id, @Param("balanceDelta") BigDecimal balanceDelta,
                      @Param("giftDelta") BigDecimal giftDelta, @Param("version") Integer version);

    /** 积分变动（可正可负，负向时要求账户积分足够，除非 allowNegative） */
    @Update("""
            UPDATE m_member_account
               SET points     = points + #{pointsDelta},
                   version    = version + 1,
                   updated_at = NOW()
             WHERE id = #{id} AND version = #{version}
            """)
    int changePoints(@Param("id") Long id, @Param("pointsDelta") Long pointsDelta,
                     @Param("version") Integer version);

    /** 成长值变动（不允许为负） */
    @Update("""
            UPDATE m_member_account
               SET growth     = growth + #{growthDelta},
                   version    = version + 1,
                   updated_at = NOW()
             WHERE id = #{id}
               AND growth + #{growthDelta} >= 0
               AND version = #{version}
            """)
    int changeGrowth(@Param("id") Long id, @Param("growthDelta") Long growthDelta,
                     @Param("version") Integer version);

    /** 对账：本金余额与流水合计比对，返回差异条数 */
    @Select("""
            SELECT COUNT(1) FROM (
              SELECT a.id
                FROM m_member_account a
                LEFT JOIN (SELECT member_id, SUM(change_amount) amt FROM m_balance_flow
                            WHERE account_type = 1 GROUP BY member_id) f ON f.member_id = a.member_id
               WHERE a.balance <> IFNULL(f.amt, 0)
            ) t
            """)
    long countBalanceDiff();

    /**
     * 对账：积分余额与批次余量合计比对
     *
     * <p>口径说明：必须统计**全部**批次的 remain_points。
     * 已用完（status=2）与已过期（status=3）的批次 remain_points 已被扣为 0，
     * 若额外用 status=1 过滤，会在"批次已被扣空"时产生假差异。
     */
    @Select("""
            SELECT COUNT(1) FROM (
              SELECT a.id
                FROM m_member_account a
                LEFT JOIN (SELECT member_id, SUM(remain_points) pts FROM m_point_batch
                            GROUP BY member_id) b ON b.member_id = a.member_id
               WHERE a.points <> IFNULL(b.pts, 0)
            ) t
            """)
    long countPointDiff();
}
