package com.company.member.modules.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.payment.entity.PaymentNotifyLog;
import com.company.member.modules.payment.entity.PaymentOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PaymentOrderMapper extends BaseMapper<PaymentOrder> {

    @Select("SELECT * FROM m_payment_order WHERE payment_no = #{paymentNo} LIMIT 1")
    PaymentOrder selectByPaymentNo(@Param("paymentNo") String paymentNo);

    /** 行锁读取（回调处理用） */
    @Select("SELECT * FROM m_payment_order WHERE payment_no = #{paymentNo} FOR UPDATE")
    PaymentOrder selectForUpdate(@Param("paymentNo") String paymentNo);

    @Select("SELECT * FROM m_payment_order WHERE biz_type = #{bizType} AND biz_no = #{bizNo} LIMIT 1")
    PaymentOrder selectByBiz(@Param("bizType") Integer bizType, @Param("bizNo") String bizNo);

    /** 置为已支付（仅待支付/支付中可流转） */
    @Update("""
            UPDATE m_payment_order
               SET status = 3, channel_order_no = #{channelOrderNo}, paid_at = NOW(),
                   notify_count = notify_count + 1, last_notify_at = NOW(), updated_at = NOW()
             WHERE id = #{id} AND status IN (1, 2)
            """)
    int markPaid(@Param("id") Long id, @Param("channelOrderNo") String channelOrderNo);

    /** 入账标记（幂等：仅未入账时可置） */
    @Update("""
            UPDATE m_payment_order
               SET account_credited = 1, credited_at = NOW(), updated_at = NOW()
             WHERE id = #{id} AND account_credited = 0
            """)
    int markCredited(@Param("id") Long id);

    @Update("""
            UPDATE m_payment_order
               SET status = 4, closed_at = NOW(), updated_at = NOW()
             WHERE id = #{id} AND status IN (1, 2)
            """)
    int markClosed(@Param("id") Long id);

    @Update("""
            UPDATE m_payment_order
               SET status = #{status}, refund_amount = refund_amount + #{refundAmount},
                   refund_no = #{refundNo}, refunded_at = NOW(), updated_at = NOW()
             WHERE id = #{id} AND status IN (3, 6)
            """)
    int markRefunded(@Param("id") Long id, @Param("status") Integer status,
                     @Param("refundAmount") BigDecimal refundAmount, @Param("refundNo") String refundNo);

    @Update("""
            UPDATE m_payment_order
               SET status = 7, fail_code = #{failCode}, fail_msg = #{failMsg}, updated_at = NOW()
             WHERE id = #{id} AND status IN (1, 2)
            """)
    int markFailed(@Param("id") Long id, @Param("failCode") String failCode, @Param("failMsg") String failMsg);

    @Update("UPDATE m_payment_order SET notify_count = notify_count + 1, last_notify_at = NOW(), updated_at = NOW() WHERE id = #{id}")
    int increaseNotifyCount(@Param("id") Long id);

    /** 掉单补偿：待支付/支付中且创建不久的支付单 */
    @Select("""
            SELECT * FROM m_payment_order
             WHERE status IN (1, 2) AND created_at >= #{since} AND id > #{lastId}
             ORDER BY id LIMIT #{limit}
            """)
    List<PaymentOrder> selectPendingForSync(@Param("since") LocalDateTime since,
                                            @Param("lastId") Long lastId, @Param("limit") int limit);

    /** 超时关单候选 */
    @Select("""
            SELECT * FROM m_payment_order
             WHERE status IN (1, 2) AND expire_time < NOW() AND id > #{lastId}
             ORDER BY id LIMIT #{limit}
            """)
    List<PaymentOrder> selectTimeout(@Param("lastId") Long lastId, @Param("limit") int limit);

    /** 支付对账：已支付但未入账的支付单（应为空） */
    @Select("SELECT COUNT(1) FROM m_payment_order WHERE status = 3 AND account_credited = 0")
    long countPaidNotCredited();
}
