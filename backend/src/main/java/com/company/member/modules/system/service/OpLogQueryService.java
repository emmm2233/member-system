package com.company.member.modules.system.service;

import com.company.member.common.result.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作日志 / 登录日志查询服务
 *
 * <p>日志表数据量大，采用 JdbcTemplate 直查并强制分页（单页上限 200），
 * 只暴露必要字段，避免把 JSON 大字段整表拉出。
 */
@Service
@RequiredArgsConstructor
public class OpLogQueryService {

    private final JdbcTemplate jdbcTemplate;

    public PageResult<Map<String, Object>> list(String module, String opType, Long userId,
                                                LocalDateTime startTime, LocalDateTime endTime,
                                                int pageNum, int pageSize) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (module != null && !module.isBlank()) {
            where.append(" AND module = ? ");
            args.add(module);
        }
        if (opType != null && !opType.isBlank()) {
            where.append(" AND op_type = ? ");
            args.add(opType);
        }
        if (userId != null) {
            where.append(" AND user_id = ? ");
            args.add(userId);
        }
        if (startTime != null) {
            where.append(" AND created_at >= ? ");
            args.add(startTime);
        }
        if (endTime != null) {
            where.append(" AND created_at < ? ");
            args.add(endTime);
        }

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM m_op_log" + where,
                Long.class, args.toArray());
        int size = pageSize < 1 ? 20 : Math.min(pageSize, 200);
        int offset = Math.max((pageNum - 1) * size, 0);

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, trace_id, user_id, username, store_id, module, op_type, op_desc,
                       biz_type, biz_id, biz_no, before_value, after_value, amount, reason,
                       request_uri, request_method, request_param, ip, user_agent,
                       status, error_msg, cost_ms, created_at
                  FROM m_op_log
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?", pageArgs.toArray());

        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new HashMap<>(r);
            m.put("statusText", num(r.get("status")) == 1 ? "成功" : "失败");
            list.add(m);
        }
        return PageResult.of(list, total == null ? 0 : total, pageNum, size);
    }

    public PageResult<Map<String, Object>> loginLogs(String username, Integer status,
                                                     int pageNum, int pageSize) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (username != null && !username.isBlank()) {
            where.append(" AND username LIKE ? ");
            args.add("%" + username + "%");
        }
        if (status != null) {
            where.append(" AND status = ? ");
            args.add(status);
        }

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM m_login_log" + where,
                Long.class, args.toArray());
        int size = pageSize < 1 ? 20 : Math.min(pageSize, 200);
        int offset = Math.max((pageNum - 1) * size, 0);

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, login_type, user_id, member_id, username, ip, location, user_agent, status, msg, created_at
                  FROM m_login_log
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?", pageArgs.toArray());

        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new HashMap<>(r);
            m.put("loginTypeText", num(r.get("login_type")) == 1 ? "管理端" : "小程序");
            m.put("statusText", num(r.get("status")) == 1 ? "成功" : "失败");
            list.add(m);
        }
        return PageResult.of(list, total == null ? 0 : total, pageNum, size);
    }

    private static long num(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }
}
