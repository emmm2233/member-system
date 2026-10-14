package com.company.member.modules.system.controller;

import com.company.member.common.annotation.NoAudit;
import com.company.member.common.result.R;
import com.company.member.modules.system.dto.LoginCmd;
import com.company.member.modules.system.service.AuthService;
import com.company.member.modules.system.vo.LoginVO;
import com.company.member.modules.system.vo.MenuVO;
import com.company.member.security.SecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端 · 认证与会话
 */
@Tag(name = "管理端-认证")
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
@NoAudit("认证类接口：登录/登出/刷新/改密/清计数，已由 m_login_log 覆盖，且参数含口令不宜留档")
public class AuthController {

    private final AuthService authService;
    private final SecurityService securityService;

    @Operation(summary = "账号密码登录")
    @PostMapping("/login")
    public R<LoginVO> login(@Valid @RequestBody LoginCmd cmd, HttpServletRequest request) {
        return R.ok(authService.login(cmd, clientIp(request)));
    }

    @Operation(summary = "刷新 accessToken")
    @PostMapping("/refresh")
    public R<LoginVO> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            refreshToken = body.get("refresh_token");
        }
        return R.ok(authService.refresh(refreshToken));
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout(securityService.currentUserId());
        return R.ok();
    }

    @Operation(summary = "当前用户信息（角色/权限/数据范围）")
    @GetMapping("/me")
    public R<LoginVO.UserInfoVO> me() {
        return R.ok(authService.currentUserInfo());
    }

    @Operation(summary = "当前用户菜单树（前端动态路由）")
    @GetMapping("/menus")
    public R<List<MenuVO>> menus() {
        return R.ok(authService.menus());
    }

    @Operation(summary = "修改密码")
    @PostMapping("/change-password")
    public R<Void> changePassword(@RequestBody Map<String, String> body) {
        authService.changePassword(securityService.currentUserId(),
                body.get("oldPassword"), body.get("newPassword"));
        return R.ok();
    }

    @Operation(summary = "重置登录失败计数（运维/测试用）")
    @PostMapping("/reset-fail")
    public R<Void> resetFail(@RequestParam String username) {
        authService.resetLoginFail(username);
        return R.ok();
    }

    private String clientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String h : headers) {
            String ip = request.getHeader(h);
            if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
                return ip.contains(",") ? ip.split(",")[0].trim() : ip.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
