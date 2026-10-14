package com.company.member.common.cache;

import java.util.concurrent.TimeUnit;

/**
 * 缓存抽象
 *
 * <p>两套实现：
 * <ul>
 *   <li>{@code CaffeineCacheServiceImpl}（默认，单机可用，无需 Redis）</li>
 *   <li>{@code RedisCacheServiceImpl}（生产多实例，配置 {@code member.cache.type=redis} 启用）</li>
 * </ul>
 * 业务代码只依赖本接口，因此本地无 Redis 也能完整跑通。
 */
public interface CacheService {

    void set(String key, Object value);

    void set(String key, Object value, long timeout, TimeUnit unit);

    <T> T get(String key, Class<T> clazz);

    String getString(String key);

    boolean delete(String key);

    boolean exists(String key);

    /**
     * 原子递增并返回新值（用于订单号/流水号序列）
     *
     * @param delta       增量
     * @param expireHours 过期小时数（用于日切自动清理）
     */
    long increment(String key, long delta, long expireHours);

    /**
     * 原子递增并返回新值（秒级 TTL 版本）
     *
     * <p>为什么需要重载：{@code expireHours} 的最小粒度是小时，而「固定窗口限流」的窗口
     * 只有 1 秒 —— 若沿用小时粒度，Redis 上每个窗口键要活 1 小时
     * （单主体就是 3600 个键），本地实现里更是连过期都没有。
     *
     * @param timeout 过期时长
     * @param unit    时长单位
     */
    long increment(String key, long delta, long timeout, TimeUnit unit);

    /**
     * SET NX：仅当 key 不存在时写入
     *
     * @return true 表示写入成功（即首次请求）
     */
    boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit);

    /**
     * 按前缀批量失效
     *
     * <p>用于「一组派生数据整体重算」的场景，例如看板缓存（{@code dash:*}）：
     * 一笔消费会影响总览、趋势、RFM、留存、排行等多个指标，
     * 逐个维护依赖关系既复杂又容易漏，整体失效更稳。
     *
     * <p>实现要求：<b>不得使用 Redis KEYS</b>（会阻塞整个实例）——
     * Redis 实现必须走 SCAN 游标；Caffeine 实现遍历本地 map。
     *
     * @return 实际清理的条目数；实现无法精确统计时返回 -1
     */
    int deleteByPrefix(String prefix);
}
