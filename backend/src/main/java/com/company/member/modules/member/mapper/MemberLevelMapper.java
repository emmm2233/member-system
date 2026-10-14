package com.company.member.modules.member.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.member.entity.MemberLevel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MemberLevelMapper extends BaseMapper<MemberLevel> {

    @Select("SELECT * FROM m_member_level WHERE is_default = 1 AND status = 1 AND deleted = 0 LIMIT 1")
    MemberLevel selectDefaultLevel();
}
