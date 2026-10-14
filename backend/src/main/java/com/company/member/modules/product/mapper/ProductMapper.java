package com.company.member.modules.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.product.entity.Product;
import com.company.member.modules.product.entity.ProductCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /** 扣减库存（防超卖，仅启用库存管理的商品） */
    @Update("""
            UPDATE m_product SET stock_qty = stock_qty - #{qty}, updated_at = NOW()
             WHERE id = #{id} AND stock_enabled = 1 AND stock_qty >= #{qty}
            """)
    int deductStock(@Param("id") Long id, @Param("qty") int qty);

    /** 回补库存（退单） */
    @Update("UPDATE m_product SET stock_qty = stock_qty + #{qty}, updated_at = NOW() WHERE id = #{id} AND stock_enabled = 1")
    int restoreStock(@Param("id") Long id, @Param("qty") int qty);
}
