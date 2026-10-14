package com.company.member.modules.cashier.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.cashier.entity.OrderRefund;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderRefundMapper extends BaseMapper<OrderRefund> {

    @Select("SELECT * FROM m_order_refund WHERE refund_no = #{refundNo} LIMIT 1")
    OrderRefund selectByRefundNo(@Param("refundNo") String refundNo);

    /** 状态流转（幂等：仅当处于期望状态时可流转） */
    @Update("""
            UPDATE m_order_refund
               SET status = #{toStatus}, approver_id = #{approverId}, approved_at = NOW(),
                   approve_remark = #{remark}, updated_at = NOW()
             WHERE id = #{id} AND status = #{fromStatus}
            """)
    int changeStatus(@Param("id") Long id, @Param("fromStatus") Integer fromStatus,
                     @Param("toStatus") Integer toStatus, @Param("approverId") Long approverId,
                     @Param("remark") String remark);
}
