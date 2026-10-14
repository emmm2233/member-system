package com.company.member.modules.card.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.card.entity.CardProduct;
import com.company.member.modules.card.entity.MemberCard;
import com.company.member.modules.card.entity.MemberCardItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CardProductMapper extends BaseMapper<CardProduct> {
}
