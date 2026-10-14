package com.company.member.common.aspect;

import com.company.member.common.annotation.Idempotent;
import com.company.member.common.cache.CacheService;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

/**
 * 幂等切面：Redis/Caffeine SETNX 占位，重复请求直接拒绝
 *
 * <p><b>必须比审计切面更靠外</b>（{@code @Order(100)} &lt; 审计的 200，值小者更靠外）：
 * 被幂等拦下的重复请求不应该产生审计记录 —— 否则运营连点几下就把日志表刷满，
 * 真正的操作反而被淹没。
 */
@Slf4j
@Aspect
@Component
@Order(100)
@RequiredArgsConstructor
public class IdempotentAspect {

    public static final String HEADER_IDEMPOTENCY_KEY = "X-Idempotency-Key";

    private final CacheService cacheService;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        String key = resolveKey(pjp);
        if (key == null || key.isBlank()) {
            if (idempotent.allowEmptyKey()) {
                return pjp.proceed();
            }
            // 未传幂等键时生成服务端键：既保留本次请求的快速拒绝能力，
            // 又不阻断合法调用（真正的防重由数据库唯一索引 + 资产入账幂等键兜底）
            key = "auto-" + java.util.UUID.randomUUID().toString().replace("-", "");
            log.debug("[幂等] 未提供幂等键，使用服务端生成键：prefix={}, key={}", idempotent.prefix(), key);
        }

        String cacheKey = "idem:" + idempotent.prefix() + ":" + key;
        boolean first = cacheService.setIfAbsent(cacheKey, "1", idempotent.ttlSeconds(), TimeUnit.SECONDS);
        if (!first) {
            log.warn("[幂等] 重复请求被拦截：key={}, desc={}", cacheKey, idempotent.desc());
            throw new BizException(ErrorCode.REPEAT_SUBMIT,
                    idempotent.desc().isBlank() ? "请求正在处理中，请勿重复提交" : idempotent.desc());
        }

        try {
            return pjp.proceed();
        } catch (Throwable t) {
            // 执行失败时释放幂等键，允许用户修正后重试
            cacheService.delete(cacheKey);
            throw t;
        }
    }

    /** 优先取请求头，其次反射读方法参数里的 idempotencyKey 字段 */
    private String resolveKey(ProceedingJoinPoint pjp) {
        String header = currentKey();
        if (header != null) {
            return header;
        }
        for (Object arg : pjp.getArgs()) {
            String v = readIdempotencyKey(arg);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /**
     * 读取当前请求的 {@code X-Idempotency-Key} 请求头
     *
     * <p><b>为什么把它暴露成公开静态方法</b>：幂等键需要<b>两层</b>防护 ——
     * 切面挡住「同一请求重复提交」，数据库唯一索引挡住「缓存过期后的重放」。
     * 而切面读到的请求头默认只用于第一层：服务层拿不到它，
     * 于是只能自造业务单号，第二层就失效了。
     *
     * <p>控制器在调用服务层时用本方法兜底取值，两层才真正串起来：
     * <pre>
     *   String key = cmd.getIdempotencyKey() != null ? cmd.getIdempotencyKey() : IdempotentAspect.currentKey();
     * </pre>
     *
     * @return 请求头里的幂等键；没有则返回 null
     */
    public static String currentKey() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            String header = sra.getRequest().getHeader(HEADER_IDEMPOTENCY_KEY);
            if (header != null && !header.isBlank()) {
                return header.trim();
            }
        }
        return null;
    }

    private String readIdempotencyKey(Object arg) {
        if (arg == null) {
            return null;
        }
        if (arg instanceof String s) {
            return null;
        }
        try {
            Field f = arg.getClass().getDeclaredField("idempotencyKey");
            f.setAccessible(true);
            Object v = f.get(arg);
            return v == null ? null : String.valueOf(v);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }
}
