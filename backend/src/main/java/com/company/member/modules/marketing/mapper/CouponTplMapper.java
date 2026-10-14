package com.company.member.modules.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.marketing.entity.CouponTpl;
import com.company.member.modules.marketing.entity.MemberCoupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CouponTplMapper extends BaseMapper<CouponTpl> {

    /** 原子递增发行量（防并发超发） */
    @Update("""
            UPDATE m_coupon_tpl
               SET issued_qty = issued_qty + #{qty}, updated_at = NOW()
             WHERE id = #{id} AND (total_qty < 0 OR issued_qty + #{qty} <= total_qty)
            """)
    int increaseIssued(@Param("id") Long id, @Param("qty") int qty);

    @Update("UPDATE m_coupon_tpl SET used_qty = used_qty + #{qty}, updated_at = NOW() WHERE id = #{id}")
    int increaseUsed(@Param("id") Long id, @Param("qty") int qty);
}
