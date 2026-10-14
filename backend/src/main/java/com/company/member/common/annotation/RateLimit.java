package com.company.member.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流
 *
 * <p><b>为什么需要</b>：登录已有失败锁定（5 次锁号），但会员端 {@code /api/mp/**}
 * 完全暴露在公网且没有任何频次限制。可被刷的典型接口：
 * 动态核销码校验（{@code /verify-code/parse}）、订阅消息授权上报、
 * 以及支付回调 {@code /api/open/**}（伪造回调会消耗验签性能）。
 *
 * <p>{@code ErrorCode.TOO_MANY_REQUESTS(10005)} 此前定义了却从未被使用，
 * 这个注解是它的第一个消费者。
 *
 * <p><b>计数维度</b>（由 {@code key()} 决定）：
 * <ul>
 *   <li>{@code MEMBER}（默认）—— 按当前登录会员计数；未登录时退化为按 IP。
 *       这是会员端最合适的维度：同一门店 WiFi 下多个会员不该互相挤占额度。</li>
 *   <li>{@code IP} —— 按来源 IP 计数，用于未登录接口（登录、回调）</li>
 *   <li>{@code USER} —— 按当前管理端用户计数</li>
 * </ul>
 *
 * <p><b>为什么用固定窗口而不是令牌桶</b>：本项目的目的是「挡住脚本刷接口」，
 * 不是「精确整形流量」。固定窗口实现简单、无需额外存储结构，且额度设得足够宽
 * （默认 20 次/秒）时，窗口边界的双倍突发对真实用户毫无影响。
 * 引入令牌桶只会增加一个需要维护和解释的组件，收益不成比例。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 计数维度 */
    enum Dimension {
        /** 登录会员优先，未登录退化为 IP */
        MEMBER,
        /** 来源 IP */
        IP,
        /** 管理端登录用户优先，未登录退化为 IP */
        USER
    }

    /** 每秒允许的请求数；&lt;= 0 表示取该前缀的默认值（见 V9 的 ratelimit.* 参数） */
    int qps() default 0;

    /** 限流维度 */
    Dimension dimension() default Dimension.MEMBER;

    /** 命中限流时的提示 */
    String message() default "操作过于频繁，请稍后再试";

    /** 计数键后缀：同类接口需要共享额度时填同一个值；留空则按「控制器.方法」各自计数 */
    String key() default "";
}
