package com.company.member.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.system.entity.OpLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OpLogMapper extends BaseMapper<OpLog> {
}
