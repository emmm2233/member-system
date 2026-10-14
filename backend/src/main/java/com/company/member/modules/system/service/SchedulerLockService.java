package com.company.member.modules.system.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.support.StorageBasedLockProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务分布式锁的运维视图（ShedLock）
 *
 * <p><b>存在的理由是 ShedLock 的一个「静默失效」行为</b>：
 * {@code StorageBasedLockProvider} 内部有一张 {@code LockRecordRegistry} 内存表，
 * 记住「这个名字的锁记录我已经 INSERT 过了」。此后每次加锁它<b>只走条件 UPDATE、不再 INSERT</b>，
 * 而条件 UPDATE 是 {@code WHERE name=? AND lock_until<=now} ——
 * 一旦那条行被外部删掉（运维清表、恢复备份、手工修数据），UPDATE 永远匹配不到行，
 * 于是该任务<b>从此每次都被判定为「已被锁住」而跳过，直到重启应用</b>，且不报任何错。
 *
 * <p>{@link #resetCache()} 调用 {@code StorageBasedLockProvider.clearCache()} 清掉这份内存记忆，
 * 下一次调度就会重新 INSERT 并恢复执行。<b>任何对 {@code shedlock} 表的表外操作之后都应调用它。</b>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerLockService {

    private final LockProvider lockProvider;
    private final DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /** 锁表清单：谁持着锁、持到什么时候、是否已过期（按锁名排序） */
    public List<Map<String, Object>> list() {
        // expired 用数据库时间判定，与 ShedLock 的 usingDbTime() 保持同一时钟口径，
        // 避免「本地时钟说过期了、实际还锁着」这种排查时最误导人的情况
        return jdbc().queryForList("""
                SELECT name,
                       DATE_FORMAT(locked_at, '%Y-%m-%d %H:%i:%s')  AS lockedAt,
                       DATE_FORMAT(lock_until, '%Y-%m-%d %H:%i:%s') AS lockUntil,
                       locked_by                                     AS lockedBy,
                       TIMESTAMPDIFF(SECOND, locked_at, lock_until)  AS heldSeconds,
                       (lock_until <= UTC_TIMESTAMP(3))              AS expired
                FROM shedlock
                ORDER BY name
                """);
    }

    /**
     * 清掉「锁记录已创建」的内存记忆。
     *
     * @return 结果说明（供管理端回显，也让调用者知道到底做没做）
     */
    public Map<String, Object> resetCache() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", lockProvider.getClass().getSimpleName());
        boolean clearable = lockProvider instanceof StorageBasedLockProvider;
        result.put("clearable", clearable);
        if (!clearable) {
            // 换成 Redis/Zookeeper 等其他 provider 时这里不会有内存表，也就没有本问题
            result.put("cleared", false);
            result.put("remark", "当前锁实现没有本地记忆，无需清理");
            return result;
        }
        ((StorageBasedLockProvider) lockProvider).clearCache();
        result.put("cleared", true);
        result.put("remark", "已清空本地锁记录记忆：下一次调度会重新 INSERT 锁行，被表外删除的行可自愈");
        log.warn("[定时任务] 已手动重置 ShedLock 本地锁记录记忆（provider={}）", result.get("provider"));
        return result;
    }
}
