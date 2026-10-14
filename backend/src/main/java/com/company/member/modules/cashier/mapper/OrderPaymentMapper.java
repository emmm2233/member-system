package com.company.member.modules.cashier.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.cashier.entity.OrderPayment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface OrderPaymentMapper extends BaseMapper<OrderPayment> {

    @Select("SELECT * FROM m_order_payment WHERE order_id = #{orderId} ORDER BY id")
    List<OrderPayment> selectByOrderId(@Param("orderId") Long orderId);

    /** 按支付方式汇总（交接班/日结） */
    @Select("""
            SELECT p.pay_method AS payMethod, COUNT(DISTINCT p.order_id) AS orderCount,
                   IFNULL(SUM(p.amount), 0) AS amount
              FROM m_order_payment p
              JOIN m_order o ON o.id = p.order_id AND o.status IN (3, 4)
             WHERE o.store_id = #{storeId}
               AND o.created_at >= #{start} AND o.created_at < #{end}
             GROUP BY p.pay_method
            """)
    List<Map<String, Object>> sumByPayMethod(@Param("storeId") Long storeId,
                                             @Param("start") java.time.LocalDateTime start,
                                             @Param("end") java.time.LocalDateTime end);
}
