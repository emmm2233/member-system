package com.company.member.modules.staff.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.staff.entity.Staff;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface StaffMapper extends BaseMapper<Staff> {

    /**
     * 取当前最大工号序号（用于生成下一个工号）
     *
     * <p>用 {@code CAST(SUBSTRING(staff_no,2) AS UNSIGNED)} 而不是字符串排序：
     * 字符串排序下 {@code E10 < E9}，工号到两位数就会错乱。
     * 非数字后缀（人工录入的工号）CAST 后为 0，不影响取最大值。
     */
    @Select("""
            SELECT IFNULL(MAX(CAST(SUBSTRING(staff_no, 2) AS UNSIGNED)), 0)
              FROM m_staff
             WHERE staff_no REGEXP '^E[0-9]+$'
            """)
    long maxStaffNoSeq();

    /** 员工是否还有业绩（决定能不能删除） */
    @Select("""
            SELECT COUNT(*) FROM m_order
             WHERE staff_id = #{staffId} AND status IN (3, 4, 5)
            """)
    int countOrders(@Param("staffId") Long staffId);

    /** 按门店取在职员工（收银台选人、门店店长选择用） */
    @Select("""
            SELECT * FROM m_staff
             WHERE deleted = 0 AND status = 1
               AND (#{storeId} IS NULL OR store_id = #{storeId})
             ORDER BY store_id, staff_no
            """)
    List<Staff> selectActiveByStore(@Param("storeId") Long storeId);

    @Select("SELECT * FROM m_staff WHERE staff_no = #{staffNo} AND deleted = 0 LIMIT 1")
    Staff selectByStaffNo(@Param("staffNo") String staffNo);

    @Select("SELECT * FROM m_staff WHERE id = #{id} AND deleted = 0")
    Staff selectAliveById(@Param("id") Long id);

    /** 离职：只改状态与离职日期，绝不删记录（历史订单的业绩归属靠它） */
    @Update("""
            UPDATE m_staff
               SET status = 0, leave_date = #{leaveDate}, updated_at = NOW()
             WHERE id = #{id} AND deleted = 0 AND status = 1
            """)
    int markLeft(@Param("id") Long id, @Param("leaveDate") LocalDate leaveDate);

    @Update("""
            UPDATE m_staff
               SET status = 1, leave_date = NULL, entry_date = IFNULL(entry_date, #{entryDate}), updated_at = NOW()
             WHERE id = #{id} AND deleted = 0 AND status = 0
            """)
    int markReinstated(@Param("id") Long id, @Param("entryDate") LocalDate entryDate);

    @Update("UPDATE m_staff SET deleted = 1, updated_at = NOW() WHERE id = #{id}")
    int softDelete(@Param("id") Long id);

    /** 门店下的在职员工数（门店列表展示用） */
    @Select("SELECT COUNT(*) FROM m_staff WHERE store_id = #{storeId} AND deleted = 0 AND status = 1")
    int countActiveByStoreId(@Param("storeId") Long storeId);
}
