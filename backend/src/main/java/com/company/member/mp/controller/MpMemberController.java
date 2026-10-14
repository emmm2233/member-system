package com.company.member.mp.controller;

import com.company.member.common.annotation.NoAudit;
import com.company.member.common.result.PageResult;
import com.company.member.common.result.R;
import com.company.member.mp.service.MpMemberService;
import com.company.member.security.SecurityService;
import com.company.member.common.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 小程序端 · 会员自助（首页/资产/账单/卡券/核销码/等级/门店）
 */
@Tag(name = "小程序-会员自助")
@RestController
@RequestMapping("/api/mp")
@RequiredArgsConstructor
@NoAudit("会员端自助查询与核销码：非管理操作，资产变动另有流水可查")
@RateLimit
public class MpMemberController {

    private final MpMemberService mpMemberService;
    private final SecurityService securityService;

    /* ---------- 首页 ---------- */

    @Operation(summary = "首页汇总（资产+等级进度+券+最近消费+待支付）")
    @GetMapping("/home/summary")
    public R<Map<String, Object>> homeSummary() {
        return R.ok(mpMemberService.homeSummary(securityService.requireMemberId()));
    }

    /* ---------- 资产 ---------- */

    @Operation(summary = "我的资产（余额/赠送/积分/成长值/等级进度）")
    @GetMapping("/account")
    public R<Map<String, Object>> account() {
        return R.ok(mpMemberService.account(securityService.requireMemberId()));
    }

    @Operation(summary = "余额账单（type: recharge/consume/refund/adjust）")
    @GetMapping("/account/balance-flows")
    public R<PageResult<Map<String, Object>>> balanceFlows(@RequestParam(required = false) String type,
                                                           @RequestParam(defaultValue = "1") int pageNum,
                                                           @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(mpMemberService.balanceFlows(securityService.requireMemberId(), type, pageNum, pageSize));
    }

    @Operation(summary = "积分明细（type: gain/exchange/expire/adjust）")
    @GetMapping("/account/point-flows")
    public R<PageResult<Map<String, Object>>> pointFlows(@RequestParam(required = false) String type,
                                                         @RequestParam(defaultValue = "1") int pageNum,
                                                         @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(mpMemberService.pointFlows(securityService.requireMemberId(), type, pageNum, pageSize));
    }

    @Operation(summary = "积分到期日历（近 12 个月）")
    @GetMapping("/account/point-expire-calendar")
    public R<List<Map<String, Object>>> pointExpireCalendar() {
        return R.ok(mpMemberService.pointExpireCalendar(securityService.requireMemberId()));
    }

    /* ---------- 账单 ---------- */

    @Operation(summary = "我的账单（orderType: 1消费 2充值 3售卡 4兑换）")
    @GetMapping("/orders")
    public R<PageResult<Map<String, Object>>> orders(@RequestParam(required = false) Integer orderType,
                                                     @RequestParam(defaultValue = "1") int pageNum,
                                                     @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(mpMemberService.orders(securityService.requireMemberId(), orderType, pageNum, pageSize));
    }

    @Operation(summary = "订单详情")
    @GetMapping("/orders/{orderNo}")
    public R<Map<String, Object>> orderDetail(@PathVariable String orderNo) {
        return R.ok(mpMemberService.orderDetail(securityService.requireMemberId(), orderNo));
    }

    /* ---------- 卡券 ---------- */

    @Operation(summary = "我的券包（status: 1未使用 3已使用 4已过期）")
    @GetMapping("/coupons")
    public R<Map<String, Object>> coupons(@RequestParam(required = false) Integer status,
                                          @RequestParam(defaultValue = "1") int pageNum,
                                          @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(mpMemberService.coupons(securityService.requireMemberId(), status, pageNum, pageSize));
    }

    @Operation(summary = "领券中心（可自助领取的券）")
    @GetMapping("/coupons/claimable")
    public R<List<Map<String, Object>>> claimableCoupons() {
        return R.ok(mpMemberService.claimableCoupons(securityService.requireMemberId()));
    }

    /* ---------- 核销码 ---------- */

    @Operation(summary = "我的动态核销码（60 秒有效）")
    @GetMapping("/verify-code")
    public R<Map<String, Object>> verifyCode() {
        return R.ok(mpMemberService.verifyCode(securityService.requireMemberId()));
    }

    @Operation(summary = "静态会员码（仅身份识别）")
    @GetMapping("/verify-code/static")
    public R<Map<String, Object>> staticCode() {
        return R.ok(mpMemberService.staticCode(securityService.requireMemberId()));
    }

    @Operation(summary = "校验动态核销码（收银端扫码后调用）")
    @PostMapping("/verify-code/parse")
    public R<Map<String, Object>> parseCode(@RequestBody Map<String, String> body,
                                            @RequestParam(defaultValue = "120") int toleranceSeconds) {
        return R.ok(mpMemberService.verifyDynamicCode(body.get("qrContent"), toleranceSeconds));
    }

    /* ---------- 等级与门店 ---------- */

    @Operation(summary = "等级权益（含当前等级与已达成标记）")
    @GetMapping("/levels")
    public R<List<Map<String, Object>>> levels() {
        Long memberId = securityService.currentUserId();
        return R.ok(mpMemberService.levels(memberId));
    }

    @Operation(summary = "门店列表")
    @GetMapping("/stores")
    public R<List<Map<String, Object>>> stores() {
        return R.ok(mpMemberService.stores());
    }
}
