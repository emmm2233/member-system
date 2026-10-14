package com.company.member.modules.report.service;

import com.company.member.common.cache.CacheService;
import com.company.member.modules.system.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 报表只读缓存
 *
 * <p><b>为什么单独一个组件、而不是塞进 DashboardService 的每个方法里</b>：
 * 看板有 9 类报表，逐个在方法体里包一层缓存意味着 9 处样板代码，
 * 而且每个方法都要把「取参 → 拼 key → 查缓存 → 回填」重复一遍。
 * 把入口收敛成一个组件后：新增报表只要在调用处包一下，key 就在调用处一眼可见
 * （key 必须覆盖全部入参，放在离入参最近的地方最不容易写错）。
 *
 * <p><b>失效策略是「整体失效」</b>：看板的每个指标都由同一批订单/会员数据聚合而来，
 * 一笔消费会影响总览、趋势、RFM、留存、排行……精确维护依赖关系既复杂又容易漏。
 * 看板是只读展示、重建成本可控（秒级），因此结算/退单/充值后直接整体清空。
 *
 * <p>TTL 来自 {@code m_sys_config.report.dashboard_cache_seconds}；配 0 即彻底关闭缓存。
 * <b>这个配置此前只有 getter、没有任何调用方</b>，等于形同虚设 —— 本组件是它的第一个消费者。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardCache {

    /** 缓存键前缀；失效时按前缀清理 */
    public static final String CACHE_PREFIX = "dash:";

    private final CacheService cacheService;
    private final ConfigService configService;

    /** 命中/未命中计数，供自检与运维观察缓存是否真的生效 */
    private final java.util.concurrent.atomic.AtomicLong hits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong misses = new java.util.concurrent.atomic.AtomicLong();

    /**
     * 读缓存，未命中则执行 loader 并回填
     *
     * @param key    缓存键（必须覆盖全部影响结果的入参）
     * @param loader 真正的查询
     */
    public <T> T get(String key, Supplier<T> loader) {
        int ttl = configService.dashboardCacheSeconds();
        if (ttl <= 0) {
            // 配置为 0 = 关闭缓存，便于压测时对比开关前后的差异
            return loader.get();
        }
        String cacheKey = CACHE_PREFIX + key;
        Object hit = cacheService.get(cacheKey, Object.class);
        if (hit != null) {
            hits.incrementAndGet();
            @SuppressWarnings("unchecked")
            T v = (T) hit;
            return v;
        }
        misses.incrementAndGet();
        T value = loader.get();
        if (value != null) {
            cacheService.set(cacheKey, value, ttl, TimeUnit.SECONDS);
        }
        return value;
    }

    /** 整体失效（结算/退单/充值/等级重算后调用） */
    public int evictAll() {
        int n = cacheService.deleteByPrefix(CACHE_PREFIX);
        log.debug("[看板] 缓存已失效，清理 {} 条", n);
        return n;
    }

    /** 运行态统计：TTL、命中/未命中。用于确认「缓存真的在工作」而不是配了没生效 */
    public java.util.Map<String, Object> status() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("ttlSeconds", configService.dashboardCacheSeconds());
        m.put("enabled", configService.dashboardCacheSeconds() > 0);
        m.put("hits", hits.get());
        m.put("misses", misses.get());
        long total = hits.get() + misses.get();
        m.put("hitRate", total == 0 ? 0d : Math.round(hits.get() * 10000.0 / total) / 100.0);
        m.put("cachePrefix", CACHE_PREFIX);
        return m;
    }
}
