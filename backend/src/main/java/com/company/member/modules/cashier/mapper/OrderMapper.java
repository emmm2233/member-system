package com.company.member.modules.cashier.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.cashier.entity.Order;
import com.company.member.modules.cashier.entity.OrderItem;
import com.company.member.modules.cashier.entity.OrderPayment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    @Select("SELECT * FROM m_order WHERE order_no = #{orderNo} LIMIT 1")
    Order selectByOrderNo(@Param("orderNo") String orderNo);

    /** 支付成功后置状态（幂等：仅当仍为待支付/支付中时生效） */
    @Update("""
            UPDATE m_order
               SET status = #{status}, paid_amount = #{paidAmount}, paid_at = NOW(), updated_at = NOW()
             WHERE id = #{id} AND status IN (1, 2)
            """)
    int markPaid(@Param("id") Long id, @Param("status") Integer status, @Param("paidAmount") BigDecimal paidAmount);

    /** 累计退款金额并更新订单状态 */
    @Update("""
            UPDATE m_order
               SET refund_amount = refund_amount + #{refundAmount},
                   status = CASE WHEN refund_amount + #{refundAmount} >= paid_amount THEN 5 ELSE 4 END,
                   updated_at = NOW()
             WHERE id = #{id} AND status IN (3, 4)
            """)
    int applyRefund(@Param("id") Long id, @Param("refundAmount") BigDecimal refundAmount);

    @Update("""
            UPDATE m_order SET status = #{status}, updated_at = NOW()
             WHERE id = #{id} AND status IN (1, 2, 7)
            """)
    int updateStatusIfPending(@Param("id") Long id, @Param("status") Integer status);

    /** 订单与支付明细金额守恒校验（对账用） */
    @Select("""
            SELECT COUNT(1) FROM m_order o
             LEFT JOIN (SELECT order_id, SUM(amount) amt FROM m_order_payment
                         WHERE pay_method <> 4 GROUP BY order_id) p ON p.order_id = o.id
             WHERE o.status IN (3, 4)
               AND o.paid_amount <> IFNULL(p.amt, 0)
            """)
    long countPaidAmountDiff();

    /** 门店日汇总（交接班/日结） */
    @Select("""
            SELECT
              COUNT(*)                                                            AS orderCount,
              IFNULL(SUM(paid_amount), 0)                                         AS paidAmount,
              IFNULL(SUM(refund_amount), 0)                                       AS refundAmount,
              IFNULL(SUM(CASE WHEN order_type = 2 THEN paid_amount ELSE 0 END), 0) AS rechargeAmount,
              IFNULL(SUM(CASE WHEN order_type = 3 THEN paid_amount ELSE 0 END), 0) AS cardSoldAmount,
              IFNULL(SUM(CASE WHEN order_type = 3 THEN 1 ELSE 0 END), 0)           AS cardSoldCount
              FROM m_order
             WHERE store_id = #{storeId} AND status IN (3, 4)
               AND created_at >= #{start} AND created_at < #{end}
            """)
    Map<String, Object> sumByStoreAndRange(@Param("storeId") Long storeId,
                                           @Param("start") java.time.LocalDateTime start,
                                           @Param("end") java.time.LocalDateTime end);
}
