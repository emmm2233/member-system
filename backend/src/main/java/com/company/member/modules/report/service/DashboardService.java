package com.company.member.modules.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.member.common.util.MoneyUtil;
import com.company.member.modules.account.entity.MemberAccount;
import com.company.member.modules.account.mapper.MemberAccountMapper;
import com.company.member.modules.cashier.entity.Order;
import com.company.member.modules.cashier.entity.OrderPayment;
import com.company.member.modules.cashier.mapper.OrderMapper;
import com.company.member.modules.cashier.mapper.OrderPaymentMapper;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.entity.MemberLevel;
import com.company.member.modules.member.mapper.MemberLevelMapper;
import com.company.member.modules.member.mapper.MemberMapper;
import com.company.member.modules.store.entity.Store;
import com.company.member.modules.store.mapper.StoreMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 看板与报表服务
 *
 * <p>数据来源：订单表实时聚合 + 账户表实时汇总（数据量大时可切换到 m_stat_daily 预聚合）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final OrderMapper orderMapper;
    private final OrderPaymentMapper orderPaymentMapper;
    private final MemberMapper memberMapper;
    private final MemberAccountMapper accountMapper;
    private final MemberLevelMapper levelMapper;
    private final StoreMapper storeMapper;
    private final JdbcTemplate jdbcTemplate;

    /* ==================================================================
     *  经营总览
     * ================================================================== */

    public Map<String, Object> overview(Long storeId, int days) {
        int span = days <= 0 ? 30 : Math.min(days, 365);
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(span - 1L);

        Map<String, Object> result = new HashMap<>();
        result.put("today", periodStat(today, today, storeId));
        result.put("yesterday", periodStat(today.minusDays(1), today.minusDays(1), storeId));
        result.put("month", periodStat(today.withDayOfMonth(1), today, storeId));
        result.put("range", periodStat(start, today, storeId));
        result.put("total", totalStat(storeId));
        result.put("liability", liability(storeId));
        result.put("memberOverview", memberOverview());
        result.put("levelDistribution", levelDistribution(storeId));
        result.put("payMethodDistribution", payMethodDistribution(start, today, storeId));
        result.put("refreshTime", LocalDateTime.now());
        return result;
    }

    /** 区间经营指标 */
    public Map<String, Object> periodStat(LocalDate start, LocalDate end, Long storeId) {
        Map<String, Object> m = new HashMap<>();
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = end.plusDays(1).atStartOfDay();

        List<Order> orders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(storeId != null, Order::getStoreId, storeId)
                .ge(Order::getCreatedAt, from)
                .lt(Order::getCreatedAt, to));

        BigDecimal consumeAmount = BigDecimal.ZERO;
        BigDecimal rechargeAmount = BigDecimal.ZERO;
        BigDecimal cardAmount = BigDecimal.ZERO;
        BigDecimal refundAmount = BigDecimal.ZERO;
        int consumeCount = 0;
        int rechargeCount = 0;
        int cardCount = 0;
        int refundCount = 0;

        for (Order o : orders) {
            BigDecimal paid = nz(o.getPaidAmount());
            switch (o.getOrderType() == null ? 0 : o.getOrderType()) {
                case Order.TYPE_CONSUME -> {
                    consumeAmount = consumeAmount.add(paid);
                    consumeCount++;
                }
                case Order.TYPE_RECHARGE -> {
                    rechargeAmount = rechargeAmount.add(paid);
                    rechargeCount++;
                }
                case Order.TYPE_CARD_SALE -> {
                    cardAmount = cardAmount.add(paid);
                    cardCount++;
                }
                default -> {
                }
            }
            BigDecimal refunded = nz(o.getRefundAmount());
            if (refunded.signum() > 0) {
                refundAmount = refundAmount.add(refunded);
                refundCount++;
            }
        }

        m.put("startDate", start.format(DAY));
        m.put("endDate", end.format(DAY));
        m.put("orderCount", orders.size());
        m.put("consumeAmount", MoneyUtil.scale(consumeAmount));
        m.put("consumeCount", consumeCount);
        m.put("rechargeAmount", MoneyUtil.scale(rechargeAmount));
        m.put("rechargeCount", rechargeCount);
        m.put("cardSoldAmount", MoneyUtil.scale(cardAmount));
        m.put("cardSoldCount", cardCount);
        m.put("refundAmount", MoneyUtil.scale(refundAmount));
        m.put("refundCount", refundCount);
        m.put("avgOrderAmount", consumeCount == 0 ? BigDecimal.ZERO
                : MoneyUtil.scale(consumeAmount.divide(BigDecimal.valueOf(consumeCount), 2, RoundingMode.HALF_UP)));
        return m;
    }

    /** 累计指标 */
    public Map<String, Object> totalStat(Long storeId) {
        Long totalOrders = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(storeId != null, Order::getStoreId, storeId)
                .in(Order::getStatus, Order.STATUS_PAID, Order.STATUS_PARTIAL_REFUND));
        List<MemberAccount> accounts = accountMapper.selectList(new LambdaQueryWrapper<>());
        BigDecimal consume = accounts.stream().map(a -> nz(a.getTotalConsume()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal recharge = accounts.stream().map(a -> nz(a.getTotalRecharge()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> m = new HashMap<>();
        m.put("orderCount", totalOrders);
        m.put("totalConsumeAmount", MoneyUtil.scale(consume));
        m.put("totalRechargeAmount", MoneyUtil.scale(recharge));
        return m;
    }

    /** 储值与积分负债 */
    public Map<String, Object> liability(Long storeId) {
        List<MemberAccount> accounts = accountMapper.selectList(new LambdaQueryWrapper<>());
        BigDecimal principal = BigDecimal.ZERO;
        BigDecimal gift = BigDecimal.ZERO;
        long points = 0L;
        for (MemberAccount a : accounts) {
            principal = principal.add(nz(a.getBalance()));
            gift = gift.add(nz(a.getGiftBalance()));
            points += a.getPoints() == null ? 0L : a.getPoints();
        }
        Map<String, Object> m = new HashMap<>();
        m.put("accountCount", accounts.size());
        m.put("balanceLiability", MoneyUtil.scale(principal));
        m.put("giftBalanceLiability", MoneyUtil.scale(gift));
        m.put("totalLiability", MoneyUtil.scale(principal.add(gift)));
        m.put("pointsLiability", points);
        return m;
    }

    /* ==================================================================
     *  会员分析
     * ================================================================== */

    public Map<String, Object> memberOverview() {
        long total = nz(memberMapper.selectCount(new LambdaQueryWrapper<Member>().eq(Member::getDeleted, 0)));
        long normal = nz(memberMapper.selectCount(new LambdaQueryWrapper<Member>()
                .eq(Member::getDeleted, 0).eq(Member::getStatus, 1)));
        long frozen = nz(memberMapper.selectCount(new LambdaQueryWrapper<Member>()
                .eq(Member::getDeleted, 0).eq(Member::getStatus, 2)));
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long newToday = nz(memberMapper.selectCount(new LambdaQueryWrapper<Member>()
                .eq(Member::getDeleted, 0).ge(Member::getCreatedAt, todayStart)));
        long newThisMonth = nz(memberMapper.selectCount(new LambdaQueryWrapper<Member>()
                .eq(Member::getDeleted, 0).ge(Member::getCreatedAt, LocalDate.now().withDayOfMonth(1).atStartOfDay())));

        // 活跃会员：近 30 天有消费
        LocalDateTime from = LocalDate.now().minusDays(29).atStartOfDay();
        Long activeCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT member_id) FROM m_order
                 WHERE status IN (3, 4) AND created_at >= ?
                """, Long.class, from);

        Map<String, Object> m = new HashMap<>();
        m.put("memberCount", total);
        m.put("normalCount", normal);
        m.put("frozenCount", frozen);
        m.put("newToday", newToday);
        m.put("newThisMonth", newThisMonth);
        m.put("activeCount30d", activeCount == null ? 0 : activeCount);
        m.put("activeRate", total == 0 ? 0
                : BigDecimal.valueOf((activeCount == null ? 0 : activeCount) * 100.0 / total)
                .setScale(2, RoundingMode.HALF_UP));
        return m;
    }

    /** 等级分布 */
    public List<Map<String, Object>> levelDistribution(Long storeId) {
        List<MemberLevel> levels = levelMapper.selectList(new LambdaQueryWrapper<MemberLevel>()
                .eq(MemberLevel::getDeleted, 0).orderByAsc(MemberLevel::getLevelValue));
        List<Map<String, Object>> list = new ArrayList<>();
        long total = nz(memberMapper.selectCount(new LambdaQueryWrapper<Member>().eq(Member::getDeleted, 0)));
        for (MemberLevel l : levels) {
            long count = nz(memberMapper.selectCount(new LambdaQueryWrapper<Member>()
                    .eq(Member::getDeleted, 0)
                    .eq(Member::getLevelId, l.getId())));
            Map<String, Object> m = new HashMap<>();
            m.put("levelId", l.getId());
            m.put("levelName", l.getLevelName());
            m.put("count", count);
            m.put("ratio", total == 0 ? 0
                    : BigDecimal.valueOf(count * 100.0 / total).setScale(2, RoundingMode.HALF_UP));
            list.add(m);
        }
        return list;
    }

    /** 会员增长趋势（按日） */
    public List<Map<String, Object>> memberTrend(int days) {
        int span = days <= 0 ? 30 : Math.min(days, 180);
        LocalDate start = LocalDate.now().minusDays(span - 1L);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT DATE(created_at) AS d,
                       COUNT(*) AS new_cnt
                  FROM m_member
                 WHERE deleted = 0 AND created_at >= ?
                 GROUP BY DATE(created_at) ORDER BY d
                """, start.atStartOfDay());
        List<Map<String, Object>> activeRows = jdbcTemplate.queryForList("""
                SELECT DATE(created_at) AS d, COUNT(DISTINCT member_id) AS active_cnt,
                       IFNULL(SUM(paid_amount), 0) AS consume_amt
                  FROM m_order
                 WHERE status IN (3, 4) AND created_at >= ?
                 GROUP BY DATE(created_at) ORDER BY d
                """, start.atStartOfDay());

        Map<String, Map<String, Object>> newMap = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            newMap.put(String.valueOf(r.get("d")), new HashMap<>(r));
        }
        Map<String, Map<String, Object>> activeMap = new LinkedHashMap<>();
        for (Map<String, Object> r : activeRows) {
            activeMap.put(String.valueOf(r.get("d")), new HashMap<>(r));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < span; i++) {
            String d = start.plusDays(i).format(DAY);
            Map<String, Object> m = new HashMap<>();
            m.put("date", d);
            m.put("newMemberCount", newMap.containsKey(d) ? newMap.get(d).get("new_cnt") : 0);
            m.put("activeMemberCount", activeMap.containsKey(d) ? activeMap.get(d).get("active_cnt") : 0);
            m.put("consumeAmount", activeMap.containsKey(d) ? activeMap.get(d).get("consume_amt") : 0);
            result.add(m);
        }
        return result;
    }

    /** 消费趋势（按日） */
    public List<Map<String, Object>> consumeTrend(int days) {
        return memberTrend(days);
    }

    /** 支付方式分布 */
    public List<Map<String, Object>> payMethodDistribution(LocalDate start, LocalDate end, Long storeId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT p.pay_method AS pay_method, COUNT(DISTINCT p.order_id) AS order_count,
                       IFNULL(SUM(p.amount), 0) AS amount
                  FROM m_order_payment p
                  JOIN m_order o ON o.id = p.order_id
                 WHERE o.status IN (3, 4)
                   AND o.created_at >= ? AND o.created_at < ?
                   AND (? IS NULL OR o.store_id = ?)
                 GROUP BY p.pay_method
                """, start.atStartOfDay(), end.plusDays(1).atStartOfDay(), storeId, storeId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new HashMap<>(r);
            Object method = r.get("pay_method");
            m.put("payMethodText", OrderPayment.methodText(method == null ? null : ((Number) method).intValue()));
            list.add(m);
        }
        return list;
    }

    /** 门店业绩排行 */
    public List<Map<String, Object>> storeRanking(LocalDate start, LocalDate end, int limit) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT o.store_id, IFNULL(s.store_name, CONCAT('门店', o.store_id)) AS store_name,
                       COUNT(*) AS order_count, IFNULL(SUM(o.paid_amount), 0) AS amount
                  FROM m_order o
                  LEFT JOIN m_store s ON s.id = o.store_id
                 WHERE o.status IN (3, 4) AND o.created_at >= ? AND o.created_at < ?
                 GROUP BY o.store_id, s.store_name
                 ORDER BY amount DESC
                 LIMIT ?
                """, start.atStartOfDay(), end.plusDays(1).atStartOfDay(), limit <= 0 ? 10 : limit);
        return rows;
    }

    /**
     * 员工业绩排行
     *
     * <p><b>按 {@code m_order.staff_id} 归属到员工，而不是 {@code cashier_id}（登录账号）</b>。
     * 早期版本按账号统计，会踩两个坑：
     * <ul>
     *   <li>代班/交接时多人共用一个收银账号 → 业绩全记在账号上，分不清是谁做的；</li>
     *   <li>员工离职停用账号后，自己历史上的业绩就再也查不到了。</li>
     * </ul>
     * 时间口径用 {@code paid_at}（支付时间）而不是 {@code created_at}：
     * 业绩应以「钱什么时候收到」为准；两者在正常结算下只差毫秒。
     */
    public List<Map<String, Object>> staffRanking(LocalDate start, LocalDate end, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT s.id AS staff_id, s.staff_no, s.staff_name, s.position, s.store_id,
                       COUNT(o.id) AS order_count, IFNULL(SUM(o.paid_amount), 0) AS amount
                  FROM m_order o
                  JOIN m_staff s ON s.id = o.staff_id AND s.deleted = 0
                 WHERE o.status IN (3, 4) AND o.staff_id IS NOT NULL
                   AND o.paid_at >= ? AND o.paid_at < ?
                 GROUP BY s.id, s.staff_no, s.staff_name, s.position, s.store_id
                 ORDER BY amount DESC
                 LIMIT ?
                """, start.atStartOfDay(), end.plusDays(1).atStartOfDay(), limit <= 0 ? 10 : limit);
    }

    /** 商品销量排行 */
    public List<Map<String, Object>> productRanking(LocalDate start, LocalDate end, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT i.product_name, SUM(i.qty) AS qty, IFNULL(SUM(i.actual_amount), 0) AS amount
                  FROM m_order_item i
                  JOIN m_order o ON o.id = i.order_id
                 WHERE o.status IN (3, 4) AND o.created_at >= ? AND o.created_at < ?
                 GROUP BY i.product_name
                 ORDER BY amount DESC
                 LIMIT ?
                """, start.atStartOfDay(), end.plusDays(1).atStartOfDay(), limit <= 0 ? 10 : limit);
    }

    /** 留存分析：按入会月份队列，统计 7 日 / 30 日内是否复购 */
    public List<Map<String, Object>> retention(int months) {
        int span = months <= 0 ? 6 : Math.min(months, 24);
        LocalDate startMonth = LocalDate.now().withDayOfMonth(1).minusMonths(span - 1L);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT DATE_FORMAT(m.created_at, '%Y-%m') AS cohort,
                       COUNT(*) AS member_cnt,
                       SUM(CASE WHEN EXISTS (
                             SELECT 1 FROM m_order o
                              WHERE o.member_id = m.id AND o.status IN (3, 4)
                                AND o.created_at >= DATE_ADD(m.created_at, INTERVAL 7 DAY)
                           ) THEN 1 ELSE 0 END) AS retained_7d,
                       SUM(CASE WHEN EXISTS (
                             SELECT 1 FROM m_order o
                              WHERE o.member_id = m.id AND o.status IN (3, 4)
                                AND o.created_at >= DATE_ADD(m.created_at, INTERVAL 30 DAY)
                           ) THEN 1 ELSE 0 END) AS retained_30d
                  FROM m_member m
                 WHERE m.deleted = 0 AND m.created_at >= ?
                 GROUP BY cohort ORDER BY cohort
                """, startMonth.atStartOfDay());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long cnt = num(r.get("member_cnt"));
            long r7 = num(r.get("retained_7d"));
            long r30 = num(r.get("retained_30d"));
            Map<String, Object> m = new HashMap<>();
            m.put("cohort", r.get("cohort"));
            m.put("memberCount", cnt);
            m.put("retained7d", r7);
            m.put("retained30d", r30);
            m.put("retention7d", cnt == 0 ? 0 : BigDecimal.valueOf(r7 * 100.0 / cnt).setScale(2, RoundingMode.HALF_UP));
            m.put("retention30d", cnt == 0 ? 0 : BigDecimal.valueOf(r30 * 100.0 / cnt).setScale(2, RoundingMode.HALF_UP));
            result.add(m);
        }
        return result;
    }

    /**
     * RFM 分层
     *
     * <p>R = 最近消费距今天数、F = 消费频次、M = 累计消费金额。
     * 每维按中位数二分，组合出 8 类，归并为 6 个业务分层。
     */
    public Map<String, Object> rfm() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT m.id AS member_id, m.member_no, m.nickname, m.phone,
                       a.total_consume AS amount, a.total_consume_count AS freq,
                       s.last_consume_at AS last_at
                  FROM m_member m
                  JOIN m_member_account a ON a.member_id = m.id
                  LEFT JOIN m_member_stat s ON s.member_id = m.id
                 WHERE m.deleted = 0 AND m.status = 1 AND a.total_consume_count > 0
                """);
        if (rows.isEmpty()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("total", 0);
            empty.put("segments", List.of());
            empty.put("members", List.of());
            return empty;
        }
        List<Long> freqList = rows.stream().map(r -> num(r.get("freq"))).sorted().toList();
        List<BigDecimal> amountList = rows.stream().map(r -> new BigDecimal(String.valueOf(r.get("amount"))))
                .sorted().toList();
        long freqMedian = freqList.get(freqList.size() / 2);
        BigDecimal amountMedian = amountList.get(amountList.size() / 2);

        Map<String, Integer> segmentCount = new LinkedHashMap<>();
        segmentCount.put("重要价值客户", 0);
        segmentCount.put("重要保持客户", 0);
        segmentCount.put("重要发展客户", 0);
        segmentCount.put("重要挽留客户", 0);
        segmentCount.put("一般客户", 0);
        segmentCount.put("流失客户", 0);

        List<Map<String, Object>> members = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> r : rows) {
            long freq = num(r.get("freq"));
            BigDecimal amount = new BigDecimal(String.valueOf(r.get("amount")));
            long days = 9999;
            Object lastAt = r.get("last_at");
            if (lastAt instanceof java.sql.Timestamp ts) {
                days = java.time.Duration.between(ts.toLocalDateTime(), now).toDays();
            } else if (lastAt instanceof LocalDateTime ldt) {
                days = java.time.Duration.between(ldt, now).toDays();
            }
            boolean recent = days <= 30;
            boolean highFreq = freq >= freqMedian;
            boolean highAmount = amount.compareTo(amountMedian) >= 0;

            String segment;
            if (recent && highFreq && highAmount) {
                segment = "重要价值客户";
            } else if (!recent && highFreq && highAmount) {
                segment = "重要保持客户";
            } else if (recent && !highFreq && highAmount) {
                segment = "重要发展客户";
            } else if (!recent && highFreq && !highAmount) {
                segment = "一般客户";
            } else if (days > 90) {
                segment = "流失客户";
            } else if (!recent && !highFreq && highAmount) {
                segment = "重要挽留客户";
            } else {
                segment = "一般客户";
            }
            segmentCount.merge(segment, 1, Integer::sum);

            Map<String, Object> m = new HashMap<>();
            m.put("memberId", r.get("member_id"));
            m.put("memberNo", r.get("member_no"));
            m.put("nickname", r.get("nickname"));
            m.put("phone", r.get("phone"));
            m.put("recencyDays", days);
            m.put("frequency", freq);
            m.put("monetary", MoneyUtil.scale(amount));
            m.put("segment", segment);
            members.add(m);
        }
        members.sort(Comparator.comparing((Map<String, Object> m) -> (BigDecimal) m.get("monetary")).reversed());

        List<Map<String, Object>> segments = segmentCount.entrySet().stream().map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("segment", e.getKey());
            m.put("count", e.getValue());
            m.put("ratio", BigDecimal.valueOf(e.getValue() * 100.0 / rows.size()).setScale(2, RoundingMode.HALF_UP));
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("total", rows.size());
        result.put("freqMedian", freqMedian);
        result.put("amountMedian", MoneyUtil.scale(amountMedian));
        result.put("segments", segments);
        result.put("topMembers", members.subList(0, Math.min(members.size(), 50)));
        return result;
    }

    /** 沉睡会员清单（用于唤醒营销） */
    public List<Map<String, Object>> sleepingMembers(int days, int limit) {
        int d = days <= 0 ? 60 : days;
        return jdbcTemplate.queryForList("""
                SELECT m.id AS member_id, m.member_no, m.nickname, m.phone,
                       a.balance, a.gift_balance, a.points, a.total_consume,
                       s.last_consume_at
                  FROM m_member m
                  JOIN m_member_account a ON a.member_id = m.id
                  LEFT JOIN m_member_stat s ON s.member_id = m.id
                 WHERE m.deleted = 0 AND m.status = 1
                   AND (s.last_consume_at IS NULL OR s.last_consume_at < DATE_SUB(NOW(), INTERVAL ? DAY))
                   AND (a.balance + a.gift_balance > 0 OR a.points > 0)
                 ORDER BY (a.balance + a.gift_balance) DESC
                 LIMIT ?
                """, d, limit <= 0 ? 200 : limit);
    }

    /** 门店列表（筛选器用） */
    public List<Map<String, Object>> storeOptions() {
        return storeMapper.selectList(new LambdaQueryWrapper<Store>()
                        .eq(Store::getDeleted, 0).orderByAsc(Store::getSortNo)).stream()
                .map(s -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", s.getId());
                    m.put("storeName", s.getStoreName());
                    return m;
                }).toList();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static long num(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }
}
