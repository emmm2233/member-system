package com.company.member.modules.system.service;

import com.company.member.modules.system.entity.LoginLog;
import com.company.member.modules.system.mapper.LoginLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 登录日志服务
 *
 * <p>登录成功与失败都要留痕（安全审计要求）。
 * 日志写入失败**不能影响登录本身**，因此全部异常吞掉并降级为 warn。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginLogService {

    public static final int TYPE_ADMIN = 1;
    public static final int TYPE_MP = 2;

    private final LoginLogMapper loginLogMapper;

    public void record(int loginType, Long userId, Long memberId, String username,
                       String ip, String userAgent, boolean success, String msg) {
        try {
            LoginLog logRow = new LoginLog();
            logRow.setLoginType(loginType);
            logRow.setUserId(userId);
            logRow.setMemberId(memberId);
            logRow.setUsername(username == null ? null
                    : (username.length() > 50 ? username.substring(0, 50) : username));
            logRow.setIp(ip);
            logRow.setUserAgent(userAgent == null ? null
                    : (userAgent.length() > 500 ? userAgent.substring(0, 500) : userAgent));
            logRow.setStatus(success ? 1 : 0);
            logRow.setMsg(msg == null ? null : (msg.length() > 255 ? msg.substring(0, 255) : msg));
            logRow.setCreatedAt(LocalDateTime.now());
            loginLogMapper.insert(logRow);
        } catch (Exception e) {
            log.warn("[登录日志] 写入失败（不影响登录）：{}", e.getMessage());
        }
    }

    public void adminSuccess(Long userId, String username, String ip, String ua) {
        record(TYPE_ADMIN, userId, null, username, ip, ua, true, "登录成功");
    }

    public void adminFail(String username, String ip, String ua, String reason) {
        record(TYPE_ADMIN, null, null, username, ip, ua, false, reason);
    }

    public void mpSuccess(Long memberId, String memberNo, String ip, String ua) {
        record(TYPE_MP, null, memberId, memberNo, ip, ua, true, "小程序登录成功");
    }

    public void mpFail(String code, String ip, String ua, String reason) {
        record(TYPE_MP, null, null, "mp:" + (code == null ? "" : code), ip, ua, false, reason);
    }
}
