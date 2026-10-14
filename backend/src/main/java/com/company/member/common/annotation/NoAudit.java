package com.company.member.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 显式声明「本接口不需要操作留痕」
 *
 * <p><b>为什么需要这个注解</b>：操作日志的正确默认值是<b>全部记录</b>，
 * 而不是「记得加注解才记录」。后者的问题在实测中已经暴露 ——
 * 全仓 78 个写接口里只有 8 个带 {@code @Idempotent}，
 * 若按「opt-in」思路，剩下 70 个（含退单审批、次卡退卡、会员调级、RBAC 变更）
 * 全都不留痕，而且以后新增的接口会继续默认不留痕。
 *
 * <p>因此切面的覆盖策略已改为 <b>opt-out</b>：
 * 所有控制器上的 {@code @PostMapping / @PutMapping / @DeleteMapping} 默认留痕，
 * 只有显式标注本注解的接口才跳过，并且<b>必须写清理由</b>。
 *
 * <p>理由（{@link #value()}）不是给人看的注释，而是给代码审查看的 ——
 * {@code scripts/tools/audit-coverage.js} 会把所有豁免接口列出来逐一复核，
 * 防止有人为了让日志「干净点」随手加一个 {@code @NoAudit} 把审计绕过去。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoAudit {

    /**
     * 豁免理由（必填）。例如「纯试算无副作用」「回调原文已单独落 notify_log」
     *
     * <p>标在类上表示整个控制器豁免（如小程序端自助接口）。
     */
    String value();
}
