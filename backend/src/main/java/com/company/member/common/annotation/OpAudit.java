package com.company.member.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作留痕注解
 *
 * <p>标在控制器方法上，由 {@code OpAuditAspect} 在执行后写入 {@code m_op_log}。
 *
 * <p><b>命名说明</b>：注解叫 {@code OpAudit}、实体叫 {@code OpLog}（对应表 {@code m_op_log}）。
 * 刻意不重名 —— 同名会让「注解」和「落库实体」在代码里互相遮蔽，读起来要反复确认是哪一个。
 *
 * <p><b>覆盖策略是「兜底 + 精修」</b>：
 * <ul>
 *   <li><b>兜底</b>：切面同时匹配 {@link Idempotent}。凡带 {@code @Idempotent} 的写接口
 *       （所有资金相关操作都在其中）一定会留痕，即使开发者忘了加本注解。
 *       这样「新增一个资金接口却忘了加审计」在结构上就不可能发生。</li>
 *   <li><b>精修</b>：在关键端点上显式标注，给出人话的模块名与操作描述，
 *       让运营在「系统管理 → 操作日志」里看得懂，而不是看到一串方法名。</li>
 * </ul>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OpAudit {

    /** 模块名（中文，展示用），如「资产账务」「收银结算」 */
    String module() default "";

    /** 操作类型：CREATE / UPDATE / DELETE / RECHARGE / CONSUME / REFUND / ADJUST / AUDIT … */
    String opType() default "";

    /** 操作描述（中文），如「资产调整」 */
    String desc() default "";

    /**
     * 是否记录请求参数
     *
     * <p>登录、改密、密钥配置这类端点的入参即使脱敏也不该留档，置 false 直接不记。
     */
    boolean recordParam() default true;
}
