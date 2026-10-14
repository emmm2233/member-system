package com.company.member.mp.controller;

import com.company.member.common.annotation.NoAudit;
import com.company.member.common.annotation.Idempotent;
import com.company.member.common.result.R;
import com.company.member.modules.account.entity.RechargePlan;
import com.company.member.modules.account.mapper.RechargePlanMapper;
import com.company.member.modules.account.service.RechargeService;
import com.company.member.modules.system.service.ConfigService;
import com.company.member.security.SecurityService;
import com.company.member.common.annotation.RateLimit;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 小程序端 · 在线充值
 *
 * <p>流程：{@code GET /recharge/plans} 选方案 → {@code POST /recharge/orders} 创建待支付充值单
 * → {@code POST /api/mp/pay/prepay} 统一下单 → 调起支付 → 回调入账。
 */
@Tag(name = "小程序-在线充值")
@RestController
@RequestMapping("/api/mp/recharge")
@RequiredArgsConstructor
@NoAudit("会员端在线充值下单：资金结果由支付单与资产流水完整记录")
@RateLimit
public class MpRechargeController {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RechargePlanMapper planMapper;
    private final RechargeService rechargeService;
    private final ConfigService configService;
    private final SecurityService securityService;

    @Operation(summary = "充值方案列表（含赠送说明）")
    @GetMapping("/plans")
    public R<List<Map<String, Object>>> plans() {
        List<RechargePlan> plans = planMapper.selectList(new LambdaQueryWrapper<RechargePlan>()
                .eq(RechargePlan::getDeleted, 0)
                .eq(RechargePlan::getStatus, 1)
                .orderByAsc(RechargePlan::getSortNo));
        List<Map<String, Object>> list = new ArrayList<>();
        for (RechargePlan p : plans) {
            if (p.getValidEnd() != null && p.getValidEnd().isBefore(java.time.LocalDateTime.now())) {
                continue;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("planId", p.getId());
            m.put("planName", p.getPlanName());
            m.put("rechargeAmount", p.getRechargeAmount());
            m.put("giftAmount", p.getGiftAmount());
            m.put("giftPoints", p.getGiftPoints());
            m.put("hasCoupon", p.getGiftCouponTplIds() != null && !p.getGiftCouponTplIds().isBlank());
            m.put("remark", p.getRemark());
            m.put("giftText", buildGiftText(p));
            list.add(m);
        }
        return R.ok(list);
    }

    private String buildGiftText(RechargePlan p) {
        List<String> parts = new ArrayList<>();
        if (p.getGiftAmount() != null && p.getGiftAmount().signum() > 0) {
            parts.add("送 " + p.getGiftAmount().stripTrailingZeros().toPlainString() + " 元");
        }
        if (p.getGiftPoints() != null && p.getGiftPoints() > 0) {
            parts.add("送 " + p.getGiftPoints() + " 积分");
        }
        if (p.getGiftCouponTplIds() != null && !p.getGiftCouponTplIds().isBlank()) {
            parts.add("含优惠券");
        }
        return String.join(" + ", parts);
    }

    @Operation(summary = "创建待支付充值单")
    @PostMapping("/orders")
    @Idempotent(prefix = "mp-recharge", desc = "充值单正在创建中，请勿重复提交")
    public R<Map<String, Object>> createOrder(@RequestBody Map<String, Object> body) {
        Long memberId = securityService.requireMemberId();
        Long planId = body.get("planId") == null ? null : Long.valueOf(String.valueOf(body.get("planId")));
        BigDecimal customAmount = body.get("customAmount") == null ? null
                : new BigDecimal(String.valueOf(body.get("customAmount")));
        Long storeId = body.get("storeId") == null ? null : Long.valueOf(String.valueOf(body.get("storeId")));
        return R.ok(rechargeService.createPendingRechargeOrder(memberId, planId, customAmount, storeId, null));
    }

    @Operation(summary = "充值说明（开关状态与支付可用性）")
    @GetMapping("/config")
    public R<Map<String, Object>> config() {
        Map<String, Object> m = new HashMap<>();
        m.put("payEnabled", configService.payEnabled() || configService.payMockEnabled());
        m.put("mockEnabled", configService.payMockEnabled());
        m.put("timeoutMinutes", configService.payTimeoutMinutes());
        m.put("defaultChannel", configService.payDefaultChannel());
        m.put("notifyUrl", configService.payNotifyUrl());
        m.put("tip", configService.payMockEnabled()
                ? "当前为模拟支付环境，可完整体验在线充值流程"
                : "当前已接入真实支付渠道");
        return R.ok(m);
    }

    /** 时间格式化工具（保留给后续扩展） */
    static String fmt(java.time.LocalDateTime dt) {
        return dt == null ? null : dt.format(DT);
    }
}
