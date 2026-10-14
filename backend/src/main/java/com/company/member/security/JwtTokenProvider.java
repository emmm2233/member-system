package com.company.member.security;

import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 令牌工具（无状态，不依赖 Redis）
 *
 * <p>accessToken 短效（默认 2h）+ refreshToken 长效（默认 7d）；
 * 登出/改密通过 {@code sys_user.token_version} 递增使旧 token 失效。
 */
@Slf4j
@Component
public class JwtTokenProvider {

    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_USER_TYPE = "typ";
    public static final String CLAIM_STORE_ID = "sid";
    public static final String CLAIM_DATA_SCOPE = "dsc";
    public static final String CLAIM_REAL_NAME = "rnm";
    public static final String CLAIM_TOKEN_TYPE = "tt";
    public static final String CLAIM_TOKEN_VERSION = "ver";

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    @Value("${member.jwt.secret}")
    private String secret;

    @Value("${member.jwt.access-ttl:7200}")
    private long accessTtl;

    @Value("${member.jwt.refresh-ttl:604800}")
    private long refreshTtl;

    private SecretKey key;

    @PostConstruct
    public void init() {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "member.jwt.secret 长度必须不少于 32 字节，请在环境变量 MEMBER_JWT_SECRET 中配置强随机串");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        log.info("[JWT] 初始化完成 accessTtl={}s refreshTtl={}s", accessTtl, refreshTtl);
    }

    public String createAccessToken(LoginUser user) {
        return createToken(user, TYPE_ACCESS, accessTtl);
    }

    public String createRefreshToken(LoginUser user) {
        return createToken(user, TYPE_REFRESH, refreshTtl);
    }

    private String createToken(LoginUser user, String tokenType, long ttlSeconds) {
        Map<String, Object> claims = new HashMap<>(8);
        claims.put(CLAIM_USER_ID, user.getUserId());
        claims.put(CLAIM_USER_TYPE, user.getUserType());
        claims.put(CLAIM_STORE_ID, user.getStoreId());
        claims.put(CLAIM_DATA_SCOPE, user.getDataScope());
        claims.put(CLAIM_REAL_NAME, user.getRealName());
        claims.put(CLAIM_TOKEN_TYPE, tokenType);

        Date now = new Date();
        Date exp = new Date(now.getTime() + ttlSeconds * 1000L);
        return Jwts.builder()
                .claims(claims)
                .subject(user.getUsername() == null ? String.valueOf(user.getUserId()) : user.getUsername())
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录");
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录凭证无效");
        }
    }

    public Long getUserId(Claims claims) {
        return toLong(claims.get(CLAIM_USER_ID));
    }

    public String getUserType(Claims claims) {
        Object v = claims.get(CLAIM_USER_TYPE);
        return v == null ? null : String.valueOf(v);
    }

    public Long getStoreId(Claims claims) {
        return toLong(claims.get(CLAIM_STORE_ID));
    }

    public Integer getDataScope(Claims claims) {
        Object v = claims.get(CLAIM_DATA_SCOPE);
        return v == null ? null : Integer.valueOf(String.valueOf(v));
    }

    public String getRealName(Claims claims) {
        Object v = claims.get(CLAIM_REAL_NAME);
        return v == null ? null : String.valueOf(v);
    }

    public String getTokenType(Claims claims) {
        Object v = claims.get(CLAIM_TOKEN_TYPE);
        return v == null ? null : String.valueOf(v);
    }

    public boolean isAccessToken(Claims claims) {
        return TYPE_ACCESS.equals(getTokenType(claims));
    }

    public boolean isRefreshToken(Claims claims) {
        return TYPE_REFRESH.equals(getTokenType(claims));
    }

    public long getAccessTtl() {
        return accessTtl;
    }

    public long getRefreshTtl() {
        return refreshTtl;
    }

    private Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
