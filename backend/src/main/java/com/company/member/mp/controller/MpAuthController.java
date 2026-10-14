package com.company.member.mp.controller;

import com.company.member.common.annotation.NoAudit;
import com.company.member.common.result.R;
import com.company.member.mp.service.MpAuthService;
import com.company.member.security.SecurityService;
import com.company.member.common.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 小程序端 · 认证与资料
 */
@Tag(name = "小程序-认证")
@RestController
@RequestMapping("/api/mp/auth")
@RequiredArgsConstructor
@NoAudit("会员端认证：登录/绑手机属于会员自助行为，已由 m_login_log 记录")
@RateLimit
public class MpAuthController {

    private final MpAuthService authService;
    private final SecurityService securityService;

    @Operation(summary = "微信登录（wx.login 的 code 换登录态，自动建档为游客会员）")
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        return R.ok(authService.login(body.get("code"), body.get("inviteCode")));
    }

    @Operation(summary = "绑定手机号（手机号快速验证 code，或联调模式直接传 phone）")
    @PostMapping("/bind-phone")
    public R<Map<String, Object>> bindPhone(@RequestBody Map<String, String> body) {
        Long memberId = securityService.requireMemberId();
        return R.ok(authService.bindPhone(memberId, body.get("code"), body.get("phone")));
    }

    @Operation(summary = "刷新令牌")
    @PostMapping("/refresh")
    public R<Map<String, Object>> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        return R.ok(authService.refresh(refreshToken));
    }

    @Operation(summary = "当前会员资料")
    @GetMapping("/profile")
    public R<Map<String, Object>> profile() {
        return R.ok(authService.profile(securityService.requireMemberId()));
    }

    @Operation(summary = "修改资料（昵称/头像/姓名/性别/生日）")
    @PutMapping("/profile")
    public R<Void> updateProfile(@RequestBody Map<String, Object> body) {
        authService.updateProfile(securityService.requireMemberId(), body);
        return R.ok();
    }

    @Operation(summary = "退出登录（客户端清除 token；服务端无状态）")
    @PostMapping("/logout")
    public R<Void> logout() {
        return R.ok();
    }

    @Operation(summary = "联调模式状态（是否配置真实 AppID）")
    @GetMapping("/config-status")
    public R<Map<String, Object>> configStatus() {
        return R.ok(authService.configStatus());
    }
}
