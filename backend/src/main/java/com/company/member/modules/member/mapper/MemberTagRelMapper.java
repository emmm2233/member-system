package com.company.member.modules.member.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.member.entity.MemberTagRel;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MemberTagRelMapper extends BaseMapper<MemberTagRel> {

    @Delete("DELETE FROM m_member_tag_rel WHERE member_id = #{memberId}")
    int deleteByMemberId(@Param("memberId") Long memberId);

    @Select("SELECT tag_id FROM m_member_tag_rel WHERE member_id = #{memberId}")
    List<Long> selectTagIds(@Param("memberId") Long memberId);

    @Select("""
            <script>
            SELECT r.member_id, r.tag_id
              FROM m_member_tag_rel r
             WHERE r.member_id IN
             <foreach collection="memberIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<java.util.Map<String, Object>> selectByMemberIds(@Param("memberIds") List<Long> memberIds);
}
