package com.company.member.modules.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.store.entity.Store;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface StoreMapper extends BaseMapper<Store> {

    @Select("SELECT store_name FROM m_store WHERE id = #{id}")
    String selectNameById(Long id);

    /* ---------- 删除前的引用盘点 ---------- */

    /** 在职员工数（决定「能不能删」的主判据） */
    @Select("SELECT COUNT(*) FROM m_staff WHERE store_id = #{storeId} AND deleted = 0 AND status = 1")
    int countActiveStaff(@Param("storeId") Long storeId);

    /** 全部员工数（含离职）——离职员工的档案仍引用门店，删门店会让他们变成悬空引用 */
    @Select("SELECT COUNT(*) FROM m_staff WHERE store_id = #{storeId} AND deleted = 0")
    int countAllStaff(@Param("storeId") Long storeId);

    /** 历史订单数（业绩/报表按门店归集，删门店等于毁掉历史口径） */
    @Select("SELECT COUNT(*) FROM m_order WHERE store_id = #{storeId}")
    int countOrders(@Param("storeId") Long storeId);

    /** 归属会员数 */
    @Select("SELECT COUNT(*) FROM m_member WHERE store_id = #{storeId} AND deleted = 0")
    int countMembers(@Param("storeId") Long storeId);

    /** 默认门店为该店的账号数（数据权限锚点） */
    @Select("SELECT COUNT(*) FROM m_sys_user WHERE store_id = #{storeId} AND deleted = 0")
    int countUsers(@Param("storeId") Long storeId);

    /**
     * 门店引用计数批量版
     *
     * <p>替代「每行 5 次 COUNT」的写法：门店列表 20 行原本要发 100 条 COUNT。
     * 一次 GROUP BY 把四类计数（员工/历史员工/订单/会员/账号）全取回来，
     * 结果按 store_id 回填。
     *
     * <p>用 UNION ALL 而不是多个 LEFT JOIN：JOIN 出来是笛卡尔积，
     * 再去重统计会既慢又容易写错；每条子查询各自聚合再 UNION 更直观。
     */
    @Select("""
            <script>
            SELECT store_id, SUM(active_staff) AS active_staff, SUM(all_staff) AS all_staff,
                   SUM(orders) AS orders, SUM(members) AS members, SUM(users) AS users
              FROM (
                SELECT store_id, COUNT(*) AS active_staff, 0 AS all_staff, 0 AS orders, 0 AS members, 0 AS users
                  FROM m_staff WHERE deleted = 0 AND status = 1 AND store_id IN
                  <foreach collection="storeIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                 GROUP BY store_id
                UNION ALL
                SELECT store_id, 0, COUNT(*), 0, 0, 0
                  FROM m_staff WHERE deleted = 0 AND store_id IN
                  <foreach collection="storeIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                 GROUP BY store_id
                UNION ALL
                SELECT store_id, 0, 0, COUNT(*), 0, 0
                  FROM m_order WHERE store_id IN
                  <foreach collection="storeIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                 GROUP BY store_id
                UNION ALL
                SELECT store_id, 0, 0, 0, COUNT(*), 0
                  FROM m_member WHERE deleted = 0 AND store_id IN
                  <foreach collection="storeIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                 GROUP BY store_id
                UNION ALL
                SELECT store_id, 0, 0, 0, 0, COUNT(*)
                  FROM m_sys_user WHERE deleted = 0 AND store_id IN
                  <foreach collection="storeIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                 GROUP BY store_id
              ) t
             GROUP BY store_id
            </script>
            """)
    List<java.util.Map<String, Object>> countReferences(@Param("storeIds") List<Long> storeIds);
}
