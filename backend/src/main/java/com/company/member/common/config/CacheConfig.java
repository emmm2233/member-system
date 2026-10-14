package com.company.member.common.config;

import com.company.member.common.cache.CacheService;
import com.company.member.common.cache.CaffeineCacheServiceImpl;
import com.company.member.common.cache.RedisCacheServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 缓存配置：按 {@code member.cache.type} 选择实现
 *
 * <ul>
 *   <li>{@code local}（默认）：Caffeine，单机零依赖</li>
 *   <li>{@code redis}：Redis，多实例部署使用</li>
 * </ul>
 */
@Slf4j
@Configuration
public class CacheConfig {

    @Bean
    public CacheService cacheService(@Value("${member.cache.type:local}") String type,
                                     @Value("${member.cache.local.max-size:100000}") long maxSize,
                                     @Value("${member.cache.local.expire-minutes:30}") long expireMinutes,
                                     ObjectProvider<StringRedisTemplate> redisProvider) {
        if ("redis".equalsIgnoreCase(type)) {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) {
                throw new IllegalStateException("member.cache.type=redis 但未配置 spring.data.redis");
            }
            log.info("[缓存] 使用 Redis 实现");
            return new RedisCacheServiceImpl(redis);
        }
        log.info("[缓存] 使用本地 Caffeine 实现（单机模式，多实例部署请切换 member.cache.type=redis）");
        return new CaffeineCacheServiceImpl(maxSize, expireMinutes);
    }
}
