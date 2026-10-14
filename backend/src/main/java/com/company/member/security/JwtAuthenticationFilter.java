package com.company.member.security;

import com.company.member.common.constant.OperatorContext;
import com.company.member.common.interceptor.TraceIdFilter;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.result.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器：解析 accessToken → 装载用户与权限 → 写入 SecurityContext 与操作人上下文
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final SecurityService securityService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }
        Claims claims;
        try {
            claims = tokenProvider.parse(token);
        } catch (Exception e) {
            // token 无效不在此处拒绝，交由授权规则统一返回 401（保证公开接口仍可用）
            chain.doFilter(request, response);
            return;
        }
        if (!tokenProvider.isAccessToken(claims)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            LoginUser loginUser = securityService.loadLoginUser(claims);
            if (loginUser != null && loginUser.isAccountNonLocked()) {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                OperatorContext.set(loginUser.toOperator());
            }
        } catch (Exception e) {
            log.warn("[认证] 装载登录用户失败: {}", e.getMessage());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            OperatorContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER_AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String t = header.substring(BEARER_PREFIX.length()).trim();
            return t.isEmpty() ? null : t;
        }
        String queryToken = request.getParameter("access_token");
        if (queryToken != null && !queryToken.isBlank()) {
            return queryToken.trim();
        }
        return null;
    }

    /** 统一写出未登录响应（HTTP 200 + 业务码，前端好处理） */
    public static void writeJson(HttpServletResponse response, ObjectMapper mapper, ErrorCode code) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        R<Void> body = R.fail(code);
        body.setTraceId(MDC.get(TraceIdFilter.TRACE_ID));
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
