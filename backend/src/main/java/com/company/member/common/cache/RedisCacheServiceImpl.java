package com.company.member.common.cache;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis 缓存实现（生产多实例）
 *
 * <p>启用方式：{@code member.cache.type=redis}
 *
 * <p><b>为什么值要走 JSON 而不是 String.valueOf</b>：
 * 早期版本用 {@code String.valueOf(value)} 存、{@code get(key, clazz)} 只认
 * String/Long/Integer，其它类型一律返回 null。后果不是「缓存不生效」这么轻：
 * <ul>
 *   <li>登录态（{@code LoginUser}）缓存永不命中 —— 每个请求多查一次库与权限</li>
 *   <li>微信支付平台证书（{@code Map<String, X509Certificate>}）永不命中 ——
 *       <b>每次回调都去微信重新下载证书</b></li>
 *   <li>看板聚合结果（{@code Map/List}）永不命中 —— 缓存白配</li>
 * </ul>
 * 而这些在 Caffeine 实现下是好的（存的是对象引用），所以问题只在切 Redis 后暴露，
 * 属于「上线才炸」的典型。改为 JSON 序列化后两种实现语义一致。
 *
 * <p>注意：JSON 反序列化需要目标类型信息，因此 {@code get} 在 JSON 分支用
 * {@code clazz} 反序列化；调用方传 {@code Object.class} 时会退化为 Map/List，
 * 这对看板缓存（本来就是 Map/List）是正确结果。
 *
 * <p><b>2026-09-15 补：三个必须的 mapper 配置（切到 Redis 实测出来的）</b>
 * <ul>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES=false}：缓存值常常是「另一个实例、另一版代码」
 *       写进去的（滚动发布期间新旧实例共用同一个 Redis）。而且 {@code LoginUser} 这类
 *       对象序列化时会写出 {@code enabled} 这种「只有 getter」的字段，反序列化没有对应
 *       setter —— 严格模式下登录态缓存<b>永不命中</b>，每个请求都多查一次库与权限。</li>
 *   <li>{@code JavaTimeModule}：看板/报表的聚合结果里有 {@code LocalDateTime}，
 *       默认 mapper 直接拒收（{@code Java 8 date/time type not supported}），
 *       于是 {@code set} 抛错、整个看板接口 500。</li>
 *   <li>{@code FAIL_ON_EMPTY_BEANS=false}：没有可读属性的对象不再报错，
 *       让缓存写入本身尽量成功。</li>
 * </ul>
 * 另外 {@code set} 不再向上抛异常（见 {@link #serialize}）。
 */
@Slf4j
@RequiredArgsConstructor
public class RedisCacheServiceImpl implements CacheService {

    private final StringRedisTemplate redis;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            // 滚动发布期间新旧实例共用一份缓存，字段增删不该让缓存整体失效
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .addModule(new JavaTimeModule())
            // 时间写成 ISO 字符串而不是时间戳：Redis 里肉眼可读，且不随默认时区解释漂移
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    /** 是否可作为「裸字符串」直接存取（避免给简单值套一层 JSON 引号） */
    private static boolean isPlainString(Object v) {
        return v instanceof String || v instanceof Long || v instanceof Integer
                || v instanceof Boolean || v instanceof Double;
    }

    @Override
    public void set(String key, Object value) {
        String json = serialize(key, value);
        if (json == null) {
            return;
        }
        redis.opsForValue().set(key, json);
    }

    @Override
    public void set(String key, Object value, long timeout, TimeUnit unit) {
        String json = serialize(key, value);
        if (json == null) {
            return;
        }
        redis.opsForValue().set(key, json, timeout, unit);
    }

    @Override
    public <T> T get(String key, Class<T> clazz) {
        String v = redis.opsForValue().get(key);
        if (v == null) {
            return null;
        }
        return deserialize(v, clazz);
    }

    @Override
    public String getString(String key) {
        return redis.opsForValue().get(key);
    }

    @Override
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redis.delete(key));
    }

    @Override
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    @Override
    public long increment(String key, long delta, long expireHours) {
        return increment(key, delta, expireHours, TimeUnit.HOURS);
    }

    @Override
    public long increment(String key, long delta, long timeout, TimeUnit unit) {
        Long v = redis.opsForValue().increment(key, delta);
        // 只在第一次递增时设置过期：Redis 的 expire 每次调用都会重置 TTL，
        // 若每次 INCR 都 EXPIRE，窗口会被无限延长，限流就永远不滚动
        if (v != null && v == delta) {
            redis.expire(key, timeout, unit);
        }
        return v == null ? 0L : v;
    }

    @Override
    public boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, value, timeout, unit));
    }

    /**
     * 按前缀批量失效 —— 用 SCAN 游标，绝不用 KEYS
     *
     * <p>{@code KEYS pattern} 会遍历整个键空间并阻塞 Redis 单线程，
     * 生产上是一次「批量失效」把整个系统卡住的经典事故。
     * SCAN 分批返回游标、不阻塞，代价是同一批内可能重复返回键（用 Set 去重即可）。
     */
    @Override
    public int deleteByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        ScanOptions options = ScanOptions.scanOptions().match(prefix + "*").count(500).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            List<String> batch = new ArrayList<>(500);
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= 500) {
                    deleted += flush(batch);
                }
            }
            deleted += flush(batch);
        } catch (Exception e) {
            log.warn("[缓存] 按前缀清理失败 prefix={}, 原因={}", prefix, e.getMessage());
            return -1;
        }
        return deleted;
    }

    private int flush(List<String> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        Long n = redis.delete(batch);
        batch.clear();
        return n == null ? 0 : n.intValue();
    }

    /**
     * 值 → 字符串；失败返回 null（不抛异常）
     *
     * <p><b>这里刻意反转过一次设计</b>：早期版本对不可序列化的值直接抛
     * {@code IllegalStateException}，理由是「宁可抛错暴露，也不要存进去读不出来」。
     * 切到 Redis 实测后发现代价太大：微信商户私钥（{@code PrivateKey} 是接口）无法
     * JSON 化 → {@code set} 抛错 → 被上层报成「私钥无法解析」→
     * <b>商户号配好了却开不了在线支付</b>；看板结果里的 {@code LocalDateTime} 同理，
     * 直接把报表接口打成 500。
     *
     * <p>缓存的定位是「可选的加速层」：写不进去最多是慢一点，绝不该让业务失败。
     * 为了不退回「静默失败」，失败一律打 WARN（含键名与类型），并且自检里有一条
     * 「后端日志零缓存告警」的断言盯着它。
     */
    private String serialize(String key, Object value) {
        if (value == null) {
            return "";
        }
        if (isPlainString(value)) {
            return String.valueOf(value);
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[缓存] 值无法序列化为 JSON，跳过写入 key={}, 类型={}, 原因={}",
                    key, value.getClass().getName(), e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T deserialize(String raw, Class<T> clazz) {
        try {
            if (clazz == String.class) {
                return (T) raw;
            }
            if (clazz == Long.class) {
                return (T) Long.valueOf(raw);
            }
            if (clazz == Integer.class) {
                return (T) Integer.valueOf(raw);
            }
            if (clazz == Boolean.class) {
                return (T) Boolean.valueOf(raw);
            }
            return objectMapper.readValue(raw, clazz);
        } catch (Exception e) {
            log.warn("[缓存] 反序列化失败 key 类型={}, 原始值前缀={}, 原因={}",
                    clazz.getSimpleName(), raw.substring(0, Math.min(40, raw.length())), e.getMessage());
            return null;
        }
    }
}
