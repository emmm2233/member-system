package com.company.member.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地缓存实现（默认）：单实例部署或本地开发使用，无需 Redis
 *
 * <p>注意：计数器为进程内计数，多实例部署时序列可能重复——由数据库唯一索引兜底
 * （重试生成）。生产多实例请使用 {@code member.cache.type=redis}。
 */
@Slf4j
public class CaffeineCacheServiceImpl implements CacheService {

    private final Cache<String, Object> cache;

    /**
     * 计数器（业务序列号 + 限流窗口计数）
     *
     * <p><b>为什么不是裸的 ConcurrentHashMap</b>：原先 {@code increment(key, delta, expireHours)}
     * 的第三个参数被<b>完全忽略</b>，于是注释里写的「日切自动清理」从未发生；
     * 更要命的是限流那种「每秒一个键」的用法会让这张表只增不减 ——
     * 这不是性能问题，是「能不能长期运行」的可用性问题（被刷就是内存泄漏）。
     *
     * <p>现在这张缓存做两件事：
     * <ul>
     *   <li><b>按键过期</b>：过期时刻记在值里（序列号 48 小时、秒级序列 1 小时、限流 5 秒
     *       各不相同），读数前判断，过期即从 0 重新计数；</li>
     *   <li><b>内存上限</b>：{@code maximumSize} 兜底，超过阈值再顺手清掉已过期项。</li>
     * </ul>
     * <b>刻意不设统一的 {@code expireAfterWrite}</b>：序列号键要活 48 小时，
     * 若统一 TTL 短于它，计数器会中途归零 → 单号重复（唯一索引会挡下，但业务要重试）。
     */
    private final Cache<String, Counter> counters;

    /** 计数器条目数超过它时触发一次过期清理（避免全表扫描成为常态开销） */
    private static final long SWEEP_THRESHOLD = 10_000L;

    private final AtomicBoolean sweeping = new AtomicBoolean(false);

    public CaffeineCacheServiceImpl(long maxSize, long expireMinutes) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(expireMinutes, TimeUnit.MINUTES)
                .build();
        this.counters = Caffeine.newBuilder()
                .maximumSize(Math.max(maxSize, SWEEP_THRESHOLD))
                .build();
    }

    @Override
    public void set(String key, Object value) {
        cache.put(key, value);
    }

    @Override
    public void set(String key, Object value, long timeout, TimeUnit unit) {
        // Caffeine 的过期策略在 build 时确定，这里退化为默认 TTL（开发环境够用）
        cache.put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) {
        Object v = cache.getIfPresent(key);
        if (v == null) {
            return null;
        }
        if (clazz.isInstance(v)) {
            return (T) v;
        }
        return null;
    }

    @Override
    public String getString(String key) {
        Object v = cache.getIfPresent(key);
        return v == null ? null : String.valueOf(v);
    }

    @Override
    public boolean delete(String key) {
        cache.invalidate(key);
        return true;
    }

    @Override
    public boolean exists(String key) {
        return cache.getIfPresent(key) != null;
    }

    @Override
    public long increment(String key, long delta, long expireHours) {
        return increment(key, delta, expireHours, TimeUnit.HOURS);
    }

    @Override
    public long increment(String key, long delta, long timeout, TimeUnit unit) {
        long ttlMillis = Math.max(1L, unit.toMillis(timeout));
        long now = System.currentTimeMillis();
        AtomicLong box = new AtomicLong();
        // compute 对同一个键是原子的：并发请求不会丢计数，过期判定也只在键上做一次
        counters.asMap().compute(key, (k, cur) -> {
            Counter c = (cur == null || cur.expireAt <= now) ? new Counter(now + ttlMillis) : cur;
            box.set(c.value.addAndGet(delta));
            return c;
        });
        sweepIfNeeded(now);
        return box.get();
    }

    /** 超过阈值时清理已过期计数器；只允许一个线程扫，且扫失败不影响计数结果 */
    private void sweepIfNeeded(long now) {
        if (counters.estimatedSize() <= SWEEP_THRESHOLD || !sweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            counters.asMap().entrySet().removeIf(e -> e.getValue().expireAt <= now);
        } catch (Exception e) {
            log.debug("[缓存] 计数器清理异常（忽略）: {}", e.getMessage());
        } finally {
            sweeping.set(false);
        }
    }

    /** 带独立过期时刻的计数器（Caffeine 的 TTL 是整张缓存统一的，做不到按键设置） */
    private static final class Counter {
        private final long expireAt;
        private final AtomicLong value = new AtomicLong();

        private Counter(long expireAt) {
            this.expireAt = expireAt;
        }
    }

    @Override
    public boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit) {
        Object existing = cache.asMap().putIfAbsent(key, value);
        return existing == null;
    }

    /**
     * 按前缀批量失效
     *
     * <p>Caffeine 没有原生的前缀清理，用 {@code asMap().keySet().removeIf} 遍历本地键集：
     * 单机缓存规模有限（默认上限几万条），且不涉及网络往返，代价可接受。
     * 计数器（{@code counters}）里的是业务序列号，不在本方法的清理范围内 ——
     * 序列号被清掉会导致单号重复，这是绝对不能碰的。
     */
    @Override
    public int deleteByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return 0;
        }
        java.util.List<String> hits = new java.util.ArrayList<>();
        for (String k : cache.asMap().keySet()) {
            if (k.startsWith(prefix)) {
                hits.add(k);
            }
        }
        hits.forEach(cache::invalidate);
        return hits.size();
    }
}
