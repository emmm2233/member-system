package com.company.member.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等保护（第一道防线）
 *
 * <p>第三道防线是数据库唯一索引，两道叠加保证资金类接口绝不重复执行。
 *
 * <p>幂等键取值优先级：
 * <ol>
 *   <li>请求头 {@code X-Idempotency-Key}</li>
 *   <li>方法参数中名为 {@code idempotencyKey} 的字段（反射读取，不依赖具体类型）</li>
 *   <li>两者都为空则拒绝执行（资金类接口必须带幂等键）</li>
 * </ol>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** 业务前缀，用于隔离不同接口的键空间 */
    String prefix();

    /** 锁定时长（秒）。超过该时长视为可重试 */
    long ttlSeconds() default 300;

    /** 没有幂等键时是否放行（默认 false：资金类接口必须带键） */
    boolean allowEmptyKey() default false;

    /** 描述，用于异常提示 */
    String desc() default "";
}
