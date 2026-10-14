package com.company.member.modules.payment.controller;

import com.company.member.common.annotation.Idempotent;
import com.company.member.common.annotation.NoAudit;
import com.company.member.common.result.R;
import com.company.member.modules.payment.channel.PayChannelRegistry;
import com.company.member.modules.payment.channel.MockPayChannel;
import com.company.member.modules.payment.channel.wechat.WechatPayChannel;
import com.company.member.modules.payment.entity.PaymentNotifyLog;
import com.company.member.modules.payment.entity.PaymentOrder;
import com.company.member.modules.payment.mapper.PaymentNotifyLogMapper;
import com.company.member.modules.payment.mapper.PaymentOrderMapper;
import com.company.member.modules.payment.service.PayService;
import com.company.member.security.SecurityService;
import com.company.member.common.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付接口（预留：小程序在线支付）
 *
 * <p>三组接口：
 * <ul>
 *   <li>{@code /api/mp/pay/**}：小程序端下单/查单/关单</li>
 *   <li>{@code /api/admin/payments/**}：管理端支付单管理</li>
 *   <li>{@code /api/open/pay/notify/{channel}}：渠道回调（免登录 + 验签）</li>
 *   <li>{@code /api/mock/pay/**}：开发调试（仅 mock 开启且非生产时注册）</li>
 * </ul>
 */
@Slf4j
@Tag(name = "支付（预留）")
@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PayService payService;
    private final PayChannelRegistry channelRegistry;
    private final PaymentOrderMapper paymentMapper;
    private final PaymentNotifyLogMapper notifyLogMapper;
    private final SecurityService securityService;
    private final WechatPayChannel wechatPayChannel;

    @Value("${member.pay.mock-enabled:false}")
    private boolean mockEnabled;

    /* ==================================================================
     *  小程序端
     * ================================================================== */

    @Operation(summary = "可用支付渠道")
    @GetMapping("/api/mp/pay/channels")
    public R<List<Map<String, Object>>> mpChannels() {
        return R.ok(channelRegistry.availableChannels());
    }

    @Operation(summary = "统一下单（返回调起支付所需参数）")
    @PostMapping("/api/mp/pay/prepay")
    public R<Map<String, Object>> prepay(@RequestBody Map<String, Object> body) {
        Integer bizType = body.get("bizType") == null ? PaymentOrder.BIZ_RECHARGE
                : Integer.valueOf(String.valueOf(body.get("bizType")));
        String bizNo = body.get("bizNo") == null ? null : String.valueOf(body.get("bizNo"));
        String channel = body.get("channel") == null ? null : String.valueOf(body.get("channel"));
        Long memberId = securityService.requireMemberId();
        return R.ok(payService.prepay(bizType, bizNo, channel, memberId));
    }

    @Operation(summary = "查询支付结果（前端轮询）")
    @GetMapping("/api/mp/pay/orders/{paymentNo}")
    public R<Map<String, Object>> queryResult(@PathVariable String paymentNo) {
        return R.ok(payService.queryResult(paymentNo));
    }

    @Operation(summary = "关闭支付单（用户取消支付）")
    @PostMapping("/api/mp/pay/orders/{paymentNo}/close")
    public R<Void> close(@PathVariable String paymentNo) {
        payService.close(paymentNo);
        return R.ok();
    }

    @Operation(summary = "模拟支付成功（仅 mock 渠道可用）")
    @PostMapping("/api/mp/pay/orders/{paymentNo}/mock-success")
    public R<String> mockSuccess(@PathVariable String paymentNo) {
        if (!mockEnabled) {
            return R.fail(com.company.member.common.result.ErrorCode.PAY_CHANNEL_NOT_READY, "模拟支付未启用");
        }
        PaymentOrder po = payService.getByPaymentNo(paymentNo);
        if (!MockPayChannel.CODE.equals(po.getChannel())) {
            return R.fail(com.company.member.common.result.ErrorCode.OPERATION_FAILED, "该支付单不是模拟渠道");
        }
        // 构造与真实渠道相同的回调报文与签名头，走完整的验签 + 幂等 + 入账链路
        String notifyBody = "paymentNo=" + paymentNo
                + "&notifyId=mock_" + paymentNo + "_" + System.currentTimeMillis()
                + "&tradeState=SUCCESS"
                + "&amountFen=" + po.getAmountFen()
                + "&channelOrderNo=mock_trade_" + System.currentTimeMillis();
        String result = payService.handleNotify(MockPayChannel.CODE, notifyBody,
                MockPayChannel.mockSignHeaders(), "127.0.0.1");
        log.info("[支付-Mock] 模拟支付成功 paymentNo={}, 结果={}", paymentNo, result);
        return R.ok(result);
    }

    /* ==================================================================
     *  开放回调（免登录，靠渠道验签）
     * ================================================================== */

    @Operation(summary = "支付结果回调（渠道调用，验签后入账）")
@RateLimit(dimension = RateLimit.Dimension.IP, qps = 100, key = "pay-notify",
            message = "回调请求过于频繁")
    @PostMapping("/api/open/pay/notify/{channel}")
    @NoAudit("渠道回调：原文与验签结果已单独落 m_payment_notify_log")
    public Map<String, String> notify(@PathVariable String channel,
                                      @RequestBody(required = false) String rawBody,
                                      HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        var names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        String ip = request.getRemoteAddr();
        Map<String, String> response = new HashMap<>();
        try {
            payService.handleNotify(channel, rawBody == null ? "" : rawBody, headers, ip);
            response.put("code", "SUCCESS");
            response.put("message", "成功");
        } catch (Exception e) {
            log.error("[支付] 回调处理异常 channel={}", channel, e);
            response.put("code", "FAIL");
            response.put("message", e.getMessage() == null ? "处理失败" : e.getMessage());
        }
        return response;
    }

    /* ==================================================================
     *  管理端
     * ================================================================== */

    @Operation(summary = "支付单分页")
    @GetMapping("/api/admin/payments")
    @PreAuthorize("@ps.has('payment:list')")
    public R<Map<String, Object>> page(@RequestParam(required = false) String paymentNo,
                                       @RequestParam(required = false) String bizNo,
                                       @RequestParam(required = false) Long memberId,
                                       @RequestParam(required = false) String channel,
                                       @RequestParam(required = false) Integer status,
                                       @RequestParam(defaultValue = "1") int pageNum,
                                       @RequestParam(defaultValue = "20") int pageSize) {
        var w = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaymentOrder>()
                .eq(paymentNo != null && !paymentNo.isBlank(), PaymentOrder::getPaymentNo, paymentNo)
                .eq(bizNo != null && !bizNo.isBlank(), PaymentOrder::getBizNo, bizNo)
                .eq(memberId != null, PaymentOrder::getMemberId, memberId)
                .eq(channel != null && !channel.isBlank(), PaymentOrder::getChannel, channel)
                .eq(status != null, PaymentOrder::getStatus, status)
                .orderByDesc(PaymentOrder::getId);
        var page = paymentMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                pageNum, Math.min(pageSize, 200)), w);
        List<Map<String, Object>> list = page.getRecords().stream().map(po -> {
            Map<String, Object> m = new HashMap<>();
            m.put("paymentNo", po.getPaymentNo());
            m.put("bizType", po.getBizType());
            m.put("bizNo", po.getBizNo());
            m.put("memberId", po.getMemberId());
            m.put("channel", po.getChannel());
            m.put("amount", po.getAmount());
            m.put("status", po.getStatus());
            m.put("statusText", PaymentOrder.statusText(po.getStatus()));
            m.put("channelOrderNo", po.getChannelOrderNo());
            m.put("accountCredited", po.getAccountCredited());
            m.put("notifyCount", po.getNotifyCount());
            m.put("refundAmount", po.getRefundAmount());
            m.put("createdAt", po.getCreatedAt());
            m.put("paidAt", po.getPaidAt());
            m.put("expireTime", po.getExpireTime());
            return m;
        }).toList();
        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("total", page.getTotal());
        result.put("pageNum", pageNum);
        result.put("pageSize", page.getSize());
        return R.ok(result);
    }

    @Operation(summary = "支付单详情")
    @GetMapping("/api/admin/payments/{paymentNo}")
    @PreAuthorize("@ps.has('payment:query')")
    public R<Map<String, Object>> detail(@PathVariable String paymentNo) {
        PaymentOrder po = payService.getByPaymentNo(paymentNo);
        Map<String, Object> m = new HashMap<>();
        m.put("payment", po);
        m.put("statusText", PaymentOrder.statusText(po.getStatus()));
        m.put("notifyLogs", notifyLogMapper.selectByPaymentNo(paymentNo));
        return R.ok(m);
    }

    @Operation(summary = "支付单回调日志")
    @GetMapping("/api/admin/payments/{paymentNo}/notify-logs")
    @PreAuthorize("@ps.has('payment:query')")
    public R<List<PaymentNotifyLog>> notifyLogs(@PathVariable String paymentNo) {
        return R.ok(notifyLogMapper.selectByPaymentNo(paymentNo));
    }

    @Operation(summary = "手动同步支付结果（掉单补偿）")
    @PostMapping("/api/admin/payments/{paymentNo}/sync")
    @PreAuthorize("@ps.has('payment:sync')")
    public R<Map<String, Object>> sync(@PathVariable String paymentNo) {
        PaymentOrder before = payService.getByPaymentNo(paymentNo);
        Integer beforeStatus = before.getStatus();
        boolean changed = payService.sync(paymentNo);
        PaymentOrder after = payService.getByPaymentNo(paymentNo);
        Map<String, Object> m = new HashMap<>();
        m.put("paymentNo", paymentNo);
        m.put("beforeStatus", beforeStatus);
        m.put("afterStatus", after.getStatus());
        m.put("changed", changed);
        m.put("accountCredited", after.getAccountCredited());
        m.put("tip", changed ? "渠道返回已支付，已补写入账" : "渠道未返回已支付，状态未变化");
        return R.ok(m);
    }

    @Operation(summary = "手动关单")
    @PostMapping("/api/admin/payments/{paymentNo}/close")
    @PreAuthorize("@ps.has('payment:edit')")
    public R<Void> adminClose(@PathVariable String paymentNo) {
        payService.close(paymentNo);
        return R.ok();
    }

    @Operation(summary = "发起原路退款")
    @PostMapping("/api/admin/payments/{paymentNo}/refund")
    @PreAuthorize("@ps.has('payment:refund')")
    @Idempotent(prefix = "pay-refund", desc = "该笔支付退款正在处理中，请勿重复提交")
    public R<String> refund(@PathVariable String paymentNo,
                            @RequestParam BigDecimal amount,
                            @RequestParam(required = false) String reason,
                            @RequestParam(required = false) String refundNo) {
        String rn = refundNo == null || refundNo.isBlank()
                ? "RF" + System.currentTimeMillis() : refundNo;
        String channelRefundNo = payService.refund(paymentNo, rn, amount, reason);
        return R.ok(channelRefundNo);
    }

    @Operation(summary = "支付渠道配置与就绪状态")
    @GetMapping("/api/admin/payments/channels")
    @PreAuthorize("@ps.has('payment:config')")
    public R<Map<String, Object>> channelStatus() {
        return R.ok(channelRegistry.channelStatus());
    }

    @Operation(summary = "微信支付参数本地自检（不联网：校验必填项、私钥自签自验、APIv3密钥长度）")
    @GetMapping("/api/admin/payments/channels/wechat/self-test")
    @PreAuthorize("@ps.has('payment:config')")
    public R<Map<String, Object>> wechatSelfTest() {
        return R.ok(wechatPayChannel.selfTest());
    }

    @Operation(summary = "刷新微信支付派生缓存（改完密钥后调用）")
    @PostMapping("/api/admin/payments/channels/wechat/refresh")
    @PreAuthorize("@ps.has('payment:config')")
    @NoAudit("刷新微信支付派生缓存：无业务数据变更")
    public R<Void> wechatRefresh() {
        wechatPayChannel.refreshCache();
        return R.ok();
    }

    @Operation(summary = "支付对账（渠道 vs 本地差异）")
    @GetMapping("/api/admin/payments/reconcile")
    @PreAuthorize("@ps.has('payment:reconcile')")
    public R<Map<String, Object>> reconcile() {
        long paidNotCredited = paymentMapper.countPaidNotCredited();
        Map<String, Object> m = new HashMap<>();
        m.put("paidNotCreditedCount", paidNotCredited);
        m.put("conclusion", paidNotCredited == 0 ? "支付与入账一致" : "存在已支付未入账的支付单，请检查");
        m.put("checkTime", java.time.LocalDateTime.now());
        return R.ok(m);
    }
}
