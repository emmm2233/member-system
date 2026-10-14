package com.company.member.common.aspect;

import com.company.member.common.annotation.RateLimit;
import com.company.member.common.cache.CacheService;
import com.company.member.common.constant.OperatorContext;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.modules.system.service.ConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.TimeUnit;

/**
 * 限流切面：固定窗口计数
 *
 * <p>计数键 = {@code rl:{接口}:{维度值}:{当前秒}}，用 {@link CacheService#increment}
 * 在缓存里累加，超过额度即抛 {@code 10005 请求过于频繁}。
 * 键自带秒级时间戳，所以窗口滚动不需要清理动作 —— 旧键到期自然消失。
 *
 * <p><b>优先级必须在幂等切面之外</b>（{@code @Order(50)} &lt; 幂等的 100）：
 * 限流要最先执行。否则被刷的请求会先占用幂等键，用户稍后正常提交会拿到
 * 「请勿重复提交」—— 把「被刷」错误地表现为「你的操作有问题」。
 *
 * <p><b>限流不可用时不拦业务</b>：缓存异常（Redis 抖动）时放行并记 WARN。
 * 这是刻意的取舍 —— 限流是保护措施，不能因为保护措施故障让整个系统不可用
 * （与「审计失败不能导致收银失败」同一原则）。
 */
@Slf4j
@Aspect
@Component
@Order(50)
@RequiredArgsConstructor
public class RateLimitAspect {

    /** 会员端默认额度（次/秒/主体） */
    private static final String CFG_MP_QPS = "ratelimit.mp.qps";
    /** 开放接口默认额度（次/秒/主体） */
    private static final String CFG_OPEN_QPS = "ratelimit.open.qps";

    private final CacheService cacheService;
    private final ConfigService configService;

    /**
     * 切点只按「注解类型」匹配，**不绑定注解实例**。
     *
     * <p><b>为什么不用 {@code @annotation(rateLimit)} 直接绑定</b>：第一版写成
     * {@code @annotation(rateLimit) || @within(rateLimit)}，小程序端（类级注解）限流正常，
     * 但方法级注解的 {@code /api/open/pay/notify/{channel}} 直接抛：
     * <pre>
     *   NullPointerException: Cannot invoke "RateLimit.key()" because "rateLimit" is null
     * </pre>
     * 即切点匹配上了、形参却是 null（两个 {@code ||} 分支的绑定在 Spring AOP 里不可靠），
     * 结果是「支付回调全部返回 90001」——限流没生效，回调还坏了。
     * 同时支持类级与方法级，就必须在通知内部自己取值：
     * 先看方法，再看声明类（与 {@code OpAuditAspect} 同一套写法）。
     */
    @Around("@within(com.company.member.common.annotation.RateLimit) || "
            + "@annotation(com.company.member.common.annotation.RateLimit)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        RateLimit rateLimit = sig.getMethod().getAnnotation(RateLimit.class);
        if (rateLimit == null) {
            // 类级注解：用 getMethod().getDeclaringClass() 而不是 getDeclaringType()，
            // 后者返回 AspectJ 的裸 Class，拿不到注解（OpAuditAspect 踩过同一个坑）
            rateLimit = sig.getMethod().getDeclaringClass().getAnnotation(RateLimit.class);
        }
        if (rateLimit == null) {
            // 理论上不可达（切点已保证有注解），防御性放行，绝不因为限流组件让业务不可用
            return pjp.proceed();
        }
        if (!configService.getBoolean("ratelimit.enabled", true)) {
            return pjp.proceed();
        }
        HttpServletRequest request = currentRequest();
        String uri = request == null ? "" : request.getRequestURI();

        String scope = rateLimit.key().isBlank()
                ? sig.getDeclaringType().getSimpleName() + "." + sig.getMethod().getName()
                : rateLimit.key();
        String subject = resolveSubject(rateLimit.dimension(), request);
        int qps = rateLimit.qps() > 0 ? rateLimit.qps() : defaultQps(uri);

        String key = "rl:" + scope + ":" + subject + ":" + (System.currentTimeMillis() / 1000);
        long count;
        try {
            // TTL 给 5 秒：窗口键 1 秒后就没人再读，留几秒余量只为「能自然回收」——
            // 限流键是「每秒一个」，用小时级 TTL 会让键空间随秒数膨胀
            count = cacheService.increment(key, 1, 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[限流] 计数失败，放行本次请求 key={}, 原因={}", key, e.getMessage());
            return pjp.proceed();
        }
        if (count > qps) {
            log.warn("[限流] 触发限制 scope={}, subject={}, count={}, qps={}, uri={}",
                    scope, subject, count, qps, uri);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, rateLimit.message());
        }
        return pjp.proceed();
    }

    /**
     * 计数维度取值：按端区分「会员 id」与「后台用户 id」，未登录一律退化到 IP。
     * 两者 id 空间不重叠（m_member 与 sys_user），但仍显式加前缀，
     * 避免日志里出现两个不同的 1 号主体时无法判断是哪种身份。
     */
    private String resolveSubject(RateLimit.Dimension dimension, HttpServletRequest request) {
        Long userId = OperatorContext.userId();
        boolean memberSide = OperatorContext.isMemberSide();
        if (dimension == RateLimit.Dimension.IP) {
            return "ip:" + clientIp(request);
        }
        if (dimension == RateLimit.Dimension.USER) {
            return (!memberSide && userId != null) ? "u:" + userId : "ip:" + clientIp(request);
        }
        return (memberSide && userId != null) ? "m:" + userId : "ip:" + clientIp(request);
    }

    private int defaultQps(String uri) {
        if (uri.startsWith("/api/open/")) {
            return configService.getInt(CFG_OPEN_QPS, 50);
        }
        return configService.getInt(CFG_MP_QPS, 20);
    }

    /** 取真实来源 IP：反向代理后 RemoteAddr 是网关地址，需优先读 X-Forwarded-For 首段 */
    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String v = request.getHeader("X-Forwarded-For");
        if (v != null && !v.isBlank()) {
            int comma = v.indexOf(',');
            return (comma > 0 ? v.substring(0, comma) : v).trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }
}
