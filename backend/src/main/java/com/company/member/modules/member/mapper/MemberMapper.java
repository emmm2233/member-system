package com.company.member.modules.member.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.member.entity.Member;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MemberMapper extends BaseMapper<Member> {

    /** 释放被合并/注销会员占用的手机号（保证唯一索引可用） */
    @Update("UPDATE m_member SET phone = NULL, updated_at = NOW() WHERE id = #{id}")
    int releasePhone(@Param("id") Long id);

    /** 注销会员 */
    @Update("""
            UPDATE m_member
               SET status = 3, deleted = 1, remark = CONCAT(IFNULL(remark,''), ' | 注销于 ', NOW()),
                   updated_at = NOW()
             WHERE id = #{id}
            """)
    int cancel(@Param("id") Long id);

    /** 更新等级 */
    @Update("""
            UPDATE m_member
               SET level_id = #{levelId}, level_expire_at = #{levelExpireAt}, updated_at = NOW()
             WHERE id = #{id}
            """)
    int updateLevel(@Param("id") Long id, @Param("levelId") Long levelId,
                    @Param("levelExpireAt") java.time.LocalDateTime levelExpireAt);

    /** 绑定手机号 */
    @Update("UPDATE m_member SET phone = #{phone}, updated_at = NOW() WHERE id = #{id}")
    int bindPhone(@Param("id") Long id, @Param("phone") String phone);

    /** 绑定 openid */
    @Update("UPDATE m_member SET openid = #{openid}, unionid = #{unionid}, updated_at = NOW() WHERE id = #{id}")
    int bindOpenid(@Param("id") Long id, @Param("openid") String openid, @Param("unionid") String unionid);

    /**
     * 按「月-日」查生日会员（游标分批）
     *
     * <p>匹配的是<b>生成列</b> {@code birthday_md}（= {@code DATE_FORMAT(birthday,'%m-%d')}，STORED）
     * 而不是直接在 {@code birthday} 上套函数：
     * 函数作用在列上索引会失效，生日扫描每天全表扫一次 m_member。
     * 生成列 + {@code idx_birthday_md(status, deleted, birthday_md)} 让等值匹配走索引，
     * 语义完全不变（同样只取月日，兼容「年份填 1900」的建档习惯）。
     *
     * <p>游标 {@code id > cursor} 而非 OFFSET：后者在批量发放（行会被后续步骤改写）时
     * 可能漏行或重复行。
     */
    @Select("""
            SELECT * FROM m_member
             WHERE deleted = 0 AND status = 1 AND birthday_md = #{monthDay}
               AND id > #{cursor}
             ORDER BY id LIMIT #{limit}
            """)
    List<Member> selectByBirthdayMonthDay(@Param("monthDay") String monthDay,
                                          @Param("cursor") long cursor,
                                          @Param("limit") int limit);

    /** 统计某月-日的生日会员数（管理端预览用） */
    @Select("""
            SELECT COUNT(*) FROM m_member
             WHERE deleted = 0 AND status = 1 AND birthday_md = #{monthDay}
            """)
    int countByBirthdayMonthDay(@Param("monthDay") String monthDay);

    /**
     * 各等级的会员数（一次查回，替代逐等级 COUNT）
     *
     * <p>返回 {@code [{level_id, cnt}]}，由调用方回填。
     */
    @Select("""
            SELECT level_id, COUNT(*) AS cnt
              FROM m_member
             WHERE deleted = 0 AND level_id IS NOT NULL
             GROUP BY level_id
            """)
    List<java.util.Map<String, Object>> countGroupByLevel();

    /** 按 id 批量取会员（回填用，避免逐行 selectById） */
    @Select("""
            <script>
            SELECT * FROM m_member WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Member> selectByIds(@Param("ids") List<Long> ids);
}
