package com.company.member.common.config;

import com.company.member.common.cache.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 单号序列初始化器
 *
 * <p>问题背景：本地缓存（Caffeine）实现的序列计数器不跨重启持久化，
 * 若数据库已有当日的会员号/单号，重启后计数器从 1 开始会与既有数据冲突。
 *
 * <p>解决：应用启动时按当日数据恢复各序列水位：
 * <ul>
 *   <li>会员号：M + yyyyMMdd + 6 位序列 → 取当日最大序号</li>
 *   <li>其它单号：前缀 + yyyyMMddHHmmss + 3 位序列（含秒级时间戳，天然不冲突）</li>
 * </ul>
 * 生产环境使用 Redis 实现（{@code member.cache.type=redis}）时计数器天然共享，
 * 本初始化器同样安全（只会把水位向上对齐，不会降低）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SequenceInitializer {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final CacheService cacheService;
    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner initSequences() {
        return args -> {
            String today = LocalDate.now().format(DAY);
            try {
                // 会员号：当日最大序号
                Integer maxMemberSeq = jdbcTemplate.queryForObject("""
                        SELECT IFNULL(MAX(CAST(RIGHT(member_no, 6) AS UNSIGNED)), 0)
                          FROM m_member
                         WHERE member_no LIKE CONCAT('M', ?)
                        """, Integer.class, today);
                if (maxMemberSeq != null && maxMemberSeq > 0) {
                    alignCounter("member", today, maxMemberSeq);
                }
                log.info("[序列] 初始化完成：会员号水位={}（当日 {}）", maxMemberSeq, today);
            } catch (Exception e) {
                log.warn("[序列] 初始化失败（不影响启动，冲突时自动重试）: {}", e.getMessage());
            }
        };
    }

    /** 把缓存计数器水位对齐到指定值（只在低于水位时提升） */
    private void alignCounter(String biz, String day, long watermark) {
        String key = "seq:" + biz + ":" + day;
        String current = cacheService.getString(key);
        long cur = current == null ? 0L : parseLong(current);
        if (cur < watermark) {
            cacheService.set(key, String.valueOf(watermark), 48, java.util.concurrent.TimeUnit.HOURS);
            log.info("[序列] {} 水位从 {} 提升到 {}", key, cur, watermark);
        }
    }

    private long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
