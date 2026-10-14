package com.company.member.modules.payment.channel;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 支付渠道统一抽象 —— 业务代码只依赖本接口
 *
 * <p>接入真实渠道只需新增一个实现类（如 {@code WechatPayChannel}），
 * 业务代码、数据库、前端均无需改动。
 *
 * <p>实现类通过 Spring 容器自动注册到 {@link PayChannelRegistry}。
 */
public interface PayChannel {

    /** 渠道标识：MOCK / WECHAT / ALIPAY */
    String channelCode();

    /**
     * 渠道是否就绪（商户参数齐全、证书可加载）
     *
     * <p>未就绪的渠道不会出现在前端可选列表中。
     */
    boolean isReady();

    /** 未就绪时缺失的配置项（用于管理端提示） */
    java.util.List<String> missingConfigs();

    /** 统一下单：返回给前端调起支付所需的参数 */
    PrepayResult prepay(PayRequest request);

    /** 主动查单（掉单补偿 / 前端轮询兜底） */
    PayQueryResult query(String paymentNo, String channelOrderNo);

    /** 关单（超时未支付） */
    void close(String paymentNo, String channelOrderNo);

    /** 退款（原路退回） */
    RefundResult refund(RefundRequest request);

    /** 解析回调报文为统一事件（含解密） */
    PayNotifyEvent parseNotify(String rawBody, Map<String, String> headers);

    /** 回调验签（APIv3 用平台证书验证签名） */
    boolean verifyNotify(PayNotifyEvent event, Map<String, String> headers);

    /* ==================== 统一数据结构 ==================== */

    /** 下单请求 */
    record PayRequest(String paymentNo, String bizNo, Integer bizType, Long memberId,
                      String openid, BigDecimal amount, String subject, String description,
                      String notifyUrl, String attach) implements Serializable {
    }

    /** 下单结果 */
    record PrepayResult(boolean success, String prepayId, String channelOrderNo,
                        Map<String, String> payParams, String errorCode, String errorMsg) implements Serializable {

        public static PrepayResult ok(String prepayId, Map<String, String> payParams) {
            return new PrepayResult(true, prepayId, null, payParams, null, null);
        }

        public static PrepayResult fail(String code, String msg) {
            return new PrepayResult(false, null, null, Map.of(), code, msg);
        }
    }

    /** 查单结果 */
    record PayQueryResult(boolean success, boolean paid, String channelOrderNo, String tradeState,
                          BigDecimal amount, String buyerId, String errorMsg) implements Serializable {

        public static PayQueryResult paid(String channelOrderNo, BigDecimal amount) {
            return new PayQueryResult(true, true, channelOrderNo, "SUCCESS", amount, null, null);
        }

        public static PayQueryResult unpaid(String tradeState) {
            return new PayQueryResult(true, false, null, tradeState, null, null, null);
        }

        public static PayQueryResult fail(String errorMsg) {
            return new PayQueryResult(false, false, null, null, null, null, errorMsg);
        }
    }

    /** 退款请求 */
    record RefundRequest(String paymentNo, String refundNo, String channelOrderNo,
                         BigDecimal refundAmount, BigDecimal totalAmount, String reason) implements Serializable {
    }

    /** 退款结果 */
    record RefundResult(boolean success, String channelRefundNo, String status,
                        String errorCode, String errorMsg) implements Serializable {

        public static RefundResult ok(String channelRefundNo) {
            return new RefundResult(true, channelRefundNo, "SUCCESS", null, null);
        }

        public static RefundResult fail(String code, String msg) {
            return new RefundResult(false, null, "FAIL", code, msg);
        }
    }

    /** 回调事件（统一结构） */
    record PayNotifyEvent(String notifyId, String eventType, String paymentNo, String channelOrderNo,
                          String tradeState, BigDecimal amount, String buyerId, String rawPayload,
                          boolean decrypted) implements Serializable {
    }
}
