package com.company.member.mp.controller;

import com.company.member.common.annotation.NoAudit;
import com.company.member.common.result.PageResult;
import com.company.member.common.result.R;
import com.company.member.modules.birthday.service.BirthdayGiftService;
import com.company.member.modules.message.service.MessageService;
import com.company.member.modules.referral.service.ReferralService;
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
 * 小程序端 · 推荐有礼 + 消息中心
 */
@Tag(name = "小程序-推荐与消息")
@RestController
@RequestMapping("/api/mp")
@RequiredArgsConstructor
@NoAudit("会员端推荐/生日/消息自助：非管理操作")
@RateLimit
public class MpReferralController {

    private final ReferralService referralService;
    private final BirthdayGiftService birthdayGiftService;
    private final MessageService messageService;
    private final SecurityService securityService;

    /* ---------- 推荐有礼 ---------- */

    @Operation(summary = "推荐有礼主页（邀请码/邀请人数/奖励明细/规则）")
    @GetMapping("/referral")
    public R<Map<String, Object>> center() {
        return R.ok(referralService.myCenter(securityService.requireMemberId()));
    }

    @Operation(summary = "绑定邀请码")
    @PostMapping("/referral/bind")
    public R<Map<String, Object>> bind(@RequestBody Map<String, String> body) {
        Long memberId = securityService.requireMemberId();
        var referral = referralService.bind(memberId, body.get("inviteCode"), body.get("channel"));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("referralId", referral.getId());
        out.put("referrerId", referral.getReferrerId());
        out.put("status", referral.getStatus());
        out.put("bindAt", referral.getBindAt());
        return R.ok(out);
    }

    /* ---------- 生日礼 ---------- */

    @Operation(summary = "我的生日礼（生日/倒计时/本年度是否已领）")
    @GetMapping("/birthday")
    public R<Map<String, Object>> myBirthday() {
        return R.ok(birthdayGiftService.myBirthday(securityService.requireMemberId()));
    }

    /* ---------- 消息中心 ---------- */

    @Operation(summary = "我的消息（status: 1待发送 2已发送 4已读）")
    @GetMapping("/messages")
    public R<PageResult<Map<String, Object>>> messages(@RequestParam(required = false) Integer status,
                                                       @RequestParam(defaultValue = "1") int pageNum,
                                                       @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(messageService.myMessages(securityService.requireMemberId(), status, pageNum, pageSize));
    }

    @Operation(summary = "未读消息数")
    @GetMapping("/messages/unread-count")
    public R<Long> unreadCount() {
        return R.ok(messageService.unreadCount(securityService.requireMemberId()));
    }

    @Operation(summary = "标记单条已读")
    @PostMapping("/messages/{id}/read")
    public R<Void> read(@PathVariable Long id) {
        messageService.markRead(securityService.requireMemberId(), id);
        return R.ok();
    }

    @Operation(summary = "全部标记已读")
    @PostMapping("/messages/read-all")
    public R<Integer> readAll() {
        return R.ok(messageService.markAllRead(securityService.requireMemberId()));
    }

    @Operation(summary = "上报订阅消息授权（wx.requestSubscribeMessage 的结果）")
    @PostMapping("/messages/subscribe/report")
    public R<Integer> reportSubscribe(@RequestBody Map<String, Object> body) {
        Long memberId = securityService.requireMemberId();
        Object raw = body.get("templateIds");
        List<String> ids = raw instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        return R.ok(messageService.reportSubscribe(memberId, ids));
    }

    @Operation(summary = "我的订阅额度（剩余可下发次数）")
    @GetMapping("/messages/subscribe/quota")
    public R<List<Map<String, Object>>> quota() {
        return R.ok(messageService.mySubscribeQuota(securityService.requireMemberId()));
    }
}
