package com.company.member.modules.payment.channel;

import java.util.Map;

/**
 * 渠道自诊断（可选实现）
 *
 * <p>{@link PayChannel#missingConfigs()} 只能回答「缺什么」，
 * 但运维排查时更需要知道「现在到底读到的是什么值、密钥是不是真的配了」。
 * 实现本接口的渠道会把这类信息一并交给管理端展示。
 *
 * <p>约定：<b>绝不能回显密钥明文</b>，只回显「是否已配置」与不可逆的标识
 * （商户号、序列号、AppID 这类本身就是半公开信息，可以回显）。
 */
public interface DiagnosableChannel {

    /** 渠道诊断信息（可读的配置摘要，不含密钥明文） */
    Map<String, Object> diagnose();
}
