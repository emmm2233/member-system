package com.company.member.modules.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.payment.entity.PaymentNotifyLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PaymentNotifyLogMapper extends BaseMapper<PaymentNotifyLog> {

    /** 是否已收到过同一通知（防重放第一道） */
    @Select("SELECT COUNT(1) FROM m_payment_notify_log WHERE notify_id = #{notifyId}")
    long countByNotifyId(@Param("notifyId") String notifyId);

    @Select("SELECT * FROM m_payment_notify_log WHERE payment_no = #{paymentNo} ORDER BY id DESC")
    List<PaymentNotifyLog> selectByPaymentNo(@Param("paymentNo") String paymentNo);

    @Update("""
            UPDATE m_payment_notify_log
               SET handle_status = #{status}, handle_result = #{result}, handled_at = NOW()
             WHERE id = #{id}
            """)
    int markHandled(@Param("id") Long id, @Param("status") Integer status, @Param("result") String result);
}
