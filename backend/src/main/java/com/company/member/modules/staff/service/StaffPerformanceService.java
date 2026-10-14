package com.company.member.modules.staff.service;

import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.util.MoneyUtil;
import com.company.member.modules.staff.entity.Staff;
import com.company.member.modules.staff.mapper.StaffMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 员工业绩
 *
 * <p><b>业绩按 {@code m_order.staff_id} 归属，不按 {@code cashier_id}（登录账号）</b>。
 * 这是「员工与账号分离」最直接的业务价值：
 * <ul>
 *   <li>同一个收银账号换人用（交接班、代班），业绩仍分别归到具体的人</li>
 *   <li>员工离职后，他历史上创造的业绩仍能查到 —— 因为 {@code m_staff} 记录不会被删</li>
 *   <li>账号停用不影响任何历史业绩</li>
 * </ul>
 *
 * <p><b>口径说明（避免和财务报表打架）</b>：
 * <ul>
 *   <li>计入业绩的订单：{@code status IN (3,4,5)} 即「曾经支付成功过」——
 *       部分退款/全额退款的订单仍计入，但退款金额单独列出，不在这里做净额扣减。
 *       这样「卖出多少」和「退了多少」是两个可分别核对的数，
 *       而不是一个混在一起、事后无法解释的净额。</li>
 *   <li>挂单（status=7）与未支付（1/2）不计入。</li>
 *   <li>客单价 = 实付金额 / 订单数。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffPerformanceService {

    private final StaffMapper staffMapper;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 业绩排行
     *
     * @param storeId 门店过滤（null = 全部门店）
     * @param start   起始日期（含）
     * @param end     结束日期（含）
     */
    public List<Map<String, Object>> ranking(Long storeId, LocalDate start, LocalDate end, int limit) {
        validateRange(start, end);
        int size = limit <= 0 ? 20 : Math.min(limit, 200);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT s.id                AS staff_id,
                       s.staff_no          AS staff_no,
                       s.staff_name        AS staff_name,
                       s.position          AS position,
                       s.store_id          AS store_id,
                       s.status            AS staff_status,
                       COUNT(o.id)         AS order_count,
                       IFNULL(SUM(o.paid_amount), 0)  AS amount,
                       IFNULL(SUM(o.points_gained), 0) AS points_gained,
                       IFNULL(SUM(o.refund_amount), 0) AS refund_amount,
                       IFNULL(SUM(o.gift_amount), 0)   AS gift_amount,
                       MAX(o.paid_at)      AS last_order_at
                  FROM m_staff s
                  LEFT JOIN m_order o
                         ON o.staff_id = s.id
                        AND o.status IN (3, 4, 5)
                        AND o.paid_at >= ?
                        AND o.paid_at < ?
                 WHERE s.deleted = 0
                   AND (? IS NULL OR s.store_id = ?)
                 GROUP BY s.id, s.staff_no, s.staff_name, s.position, s.store_id, s.status
                 ORDER BY amount DESC, order_count DESC, s.staff_no
                 LIMIT ?
                """, start.atStartOfDay(), end.plusDays(1).atStartOfDay(), storeId, storeId, size);

        List<Map<String, Object>> out = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", rank++);
            m.put("staffId", r.get("staff_id"));
            m.put("staffNo", r.get("staff_no"));
            m.put("staffName", r.get("staff_name"));
            m.put("position", r.get("position"));
            m.put("storeId", r.get("store_id"));
            m.put("staffStatus", r.get("staff_status"));
            m.put("staffStatusText", num(r.get("staff_status")) == 1 ? "在职" : "离职");
            long orderCount = num(r.get("order_count"));
            BigDecimal amount = dec(r.get("amount"));
            m.put("orderCount", orderCount);
            m.put("amount", amount);
            m.put("refundAmount", dec(r.get("refund_amount")));
            m.put("giftAmount", dec(r.get("gift_amount")));
            m.put("pointsGained", num(r.get("points_gained")));
            m.put("avgOrderAmount", orderCount == 0 ? BigDecimal.ZERO
                    : amount.divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP));
            m.put("lastOrderAt", r.get("last_order_at"));
            out.add(m);
        }
        return out;
    }

    /**
     * 单个员工业绩明细
     *
     * <p>除了汇总，还给出<b>日趋势</b>与<b>支付方式构成</b> ——
     * 门店店长看员工表现时最常问的两个问题就是「这几天怎么样」和「他收的钱是什么形式」。
     */
    public Map<String, Object> detail(Long staffId, LocalDate start, LocalDate end) {
        validateRange(start, end);
        Staff staff = staffMapper.selectAliveById(staffId);
        if (staff == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "员工不存在或已删除");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("staffId", staff.getId());
        out.put("staffNo", staff.getStaffNo());
        out.put("staffName", staff.getStaffName());
        out.put("position", staff.getPosition());
        out.put("storeId", staff.getStoreId());
        out.put("status", staff.getStatus());
        out.put("statusText", Staff.statusText(staff.getStatus()));
        out.put("startDate", start);
        out.put("endDate", end);

        Map<String, Object> sum = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS order_count,
                       IFNULL(SUM(paid_amount), 0)   AS amount,
                       IFNULL(SUM(refund_amount), 0) AS refund_amount,
                       IFNULL(SUM(gift_amount), 0)   AS gift_amount,
                       IFNULL(SUM(total_amount), 0)  AS total_amount,
                       IFNULL(SUM(discount_amount), 0) AS discount_amount,
                       IFNULL(SUM(points_gained), 0) AS points_gained
                  FROM m_order
                 WHERE staff_id = ? AND status IN (3, 4, 5)
                   AND paid_at >= ? AND paid_at < ?
                """, staffId, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        long orderCount = num(sum.get("order_count"));
        BigDecimal amount = dec(sum.get("amount"));
        out.put("orderCount", orderCount);
        out.put("amount", amount);
        out.put("totalAmount", dec(sum.get("total_amount")));
        out.put("discountAmount", dec(sum.get("discount_amount")));
        out.put("refundAmount", dec(sum.get("refund_amount")));
        out.put("giftAmount", dec(sum.get("gift_amount")));
        out.put("pointsGained", num(sum.get("points_gained")));
        out.put("avgOrderAmount", orderCount == 0 ? BigDecimal.ZERO
                : amount.divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP));
        out.put("refundRate", orderCount == 0 || amount.signum() == 0 ? BigDecimal.ZERO
                : dec(sum.get("refund_amount")).divide(amount, 4, RoundingMode.HALF_UP));

        out.put("dailyTrend", jdbcTemplate.queryForList("""
                SELECT DATE(paid_at) AS stat_date,
                       COUNT(*) AS order_count,
                       IFNULL(SUM(paid_amount), 0) AS amount
                  FROM m_order
                 WHERE staff_id = ? AND status IN (3, 4, 5)
                   AND paid_at >= ? AND paid_at < ?
                 GROUP BY DATE(paid_at)
                 ORDER BY stat_date
                """, staffId, start.atStartOfDay(), end.plusDays(1).atStartOfDay()));

        out.put("payMethods", jdbcTemplate.queryForList("""
                SELECT p.pay_method AS pay_method,
                       COUNT(DISTINCT p.order_id) AS order_count,
                       IFNULL(SUM(p.amount), 0)   AS amount
                  FROM m_order_payment p
                  JOIN m_order o ON o.id = p.order_id
                 WHERE o.staff_id = ? AND o.status IN (3, 4, 5)
                   AND o.paid_at >= ? AND o.paid_at < ?
                 GROUP BY p.pay_method
                 ORDER BY amount DESC
                """, staffId, start.atStartOfDay(), end.plusDays(1).atStartOfDay()));

        out.put("recentOrders", jdbcTemplate.queryForList("""
                SELECT o.order_no, o.paid_amount, o.total_amount, o.status,
                       o.paid_at, o.store_id,
                       (SELECT COUNT(*) FROM m_order_item i WHERE i.order_id = o.id) AS item_count
                  FROM m_order o
                 WHERE o.staff_id = ? AND o.status IN (3, 4, 5)
                   AND o.paid_at >= ? AND o.paid_at < ?
                 ORDER BY o.paid_at DESC
                 LIMIT 20
                """, staffId, start.atStartOfDay(), end.plusDays(1).atStartOfDay()));
        return out;
    }

    /** 全员汇总（业绩排行页顶部卡片用） */
    public Map<String, Object> summary(Long storeId, LocalDate start, LocalDate end) {
        validateRange(start, end);
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT COUNT(DISTINCT o.staff_id) AS active_staff,
                       COUNT(o.id) AS order_count,
                       IFNULL(SUM(o.paid_amount), 0) AS amount
                  FROM m_order o
                  JOIN m_staff s ON s.id = o.staff_id AND s.deleted = 0
                 WHERE o.status IN (3, 4, 5)
                   AND o.paid_at >= ? AND o.paid_at < ?
                   AND (? IS NULL OR o.store_id = ?)
                """, start.atStartOfDay(), end.plusDays(1).atStartOfDay(), storeId, storeId);
        long orderCount = num(row.get("order_count"));
        BigDecimal amount = dec(row.get("amount"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeStaff", num(row.get("active_staff")));
        out.put("orderCount", orderCount);
        out.put("amount", amount);
        out.put("avgOrderAmount", orderCount == 0 ? BigDecimal.ZERO
                : amount.divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP));
        out.put("startDate", start);
        out.put("endDate", end);
        // 未被任何订单归属的员工数：提醒运营「有人没开统计口径」或「收银台没选业绩归属」
        Long onDuty = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_staff WHERE deleted = 0 AND status = 1"
                        + " AND (? IS NULL OR store_id = ?)", Long.class, storeId, storeId);
        out.put("onDutyStaff", onDuty == null ? 0 : onDuty);
        out.put("noPerformanceStaff", Math.max(0, (onDuty == null ? 0 : onDuty) - num(row.get("active_staff"))));
        return out;
    }

    private void validateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请指定统计起止日期");
        }
        if (start.isAfter(end)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "起始日期不能晚于结束日期");
        }
        if (start.plusYears(2).isBefore(end)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "统计区间过长（最多 2 年），请缩小范围");
        }
    }

    private static long num(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal b) {
            return MoneyUtil.scale(b);
        }
        if (v instanceof Number n) {
            return MoneyUtil.scale(BigDecimal.valueOf(n.doubleValue()));
        }
        try {
            return MoneyUtil.scale(new BigDecimal(String.valueOf(v)));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
