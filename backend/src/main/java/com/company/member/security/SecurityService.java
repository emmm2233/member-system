package com.company.member.security;

import com.company.member.common.cache.CacheService;
import com.company.member.common.constant.OperatorContext;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.mapper.MemberMapper;
import com.company.member.modules.system.entity.SysUser;
import com.company.member.modules.system.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 安全服务：登录态装载 + 权限校验
 *
 * <p>既作为 JWT 过滤器装载用户的依赖，也作为 SpEL 权限判断 Bean：
 * <pre>
 *   &#64;PreAuthorize("&#64;ps.has('member:edit')")
 *   &#64;PreAuthorize("&#64;ps.hasAny('order:refund:apply','order:refund:approve')")
 * </pre>
 */
@Slf4j
@Service("ps")
@RequiredArgsConstructor
public class SecurityService {

    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    private static final String CACHE_PREFIX = "loginuser:";

    private final SysUserMapper sysUserMapper;
    private final MemberMapper memberMapper;
    private final CacheService cacheService;

    /* ==================== 1. 登录态装载 ==================== */

    public LoginUser loadLoginUser(Claims claims) {
        Long userId = extractUserId(claims);
        if (userId == null) {
            return null;
        }
        String userType = String.valueOf(claims.get(JwtTokenProvider.CLAIM_USER_TYPE));
        return "MP".equals(userType) ? loadMemberUser(userId) : loadAdminUser(userId);
    }

    public LoginUser loadAdminUser(Long userId) {
        LoginUser cached = cacheService.get(CACHE_PREFIX + "ADMIN:" + userId, LoginUser.class);
        if (cached != null) {
            return cached;
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || (user.getDeleted() != null && user.getDeleted() == 1)) {
            return null;
        }
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(user.getId());
        loginUser.setUsername(user.getUsername());
        loginUser.setPassword(user.getPassword());
        loginUser.setRealName(user.getRealName());
        loginUser.setUserType("ADMIN");
        loginUser.setStoreId(user.getStoreId());
        loginUser.setDataScope(user.getDataScope());
        loginUser.setScopeStoreIds(user.getScopeStoreIds());
        loginUser.setStatus(user.getStatus());
        loginUser.setRoles(new HashSet<>(sysUserMapper.selectRoleCodes(userId)));
        loginUser.setPerms(new HashSet<>(sysUserMapper.selectPermCodes(userId)));
        cacheService.set(CACHE_PREFIX + "ADMIN:" + userId, loginUser, 5, TimeUnit.MINUTES);
        return loginUser;
    }

    private LoginUser loadMemberUser(Long memberId) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            return null;
        }
        if (member.getStatus() != null && member.getStatus() == 3) {
            throw new BizException(ErrorCode.MEMBER_CANCELLED);
        }
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(member.getId());
        loginUser.setUsername(member.getMemberNo());
        loginUser.setRealName(member.getNickname());
        loginUser.setUserType("MP");
        loginUser.setStoreId(member.getStoreId());
        loginUser.setDataScope(3);
        loginUser.setStatus(member.getStatus() == null ? 1 : (member.getStatus() == 2 ? 0 : 1));
        loginUser.setRoles(Set.of("MEMBER"));
        loginUser.setPerms(Set.of("mp:member"));
        return loginUser;
    }

    public void evictAdmin(Long userId) {
        cacheService.delete(CACHE_PREFIX + "ADMIN:" + userId);
    }

    /* ==================== 2. 权限校验（SpEL: @ps） ==================== */

    public boolean has(String perm) {
        LoginUser user = currentUser();
        if (user == null) {
            return false;
        }
        if (isSuperAdmin(user)) {
            return true;
        }
        return user.getPerms() != null && user.getPerms().contains(perm);
    }

    public boolean hasAny(String... perms) {
        return Arrays.stream(perms).anyMatch(this::has);
    }

    public boolean hasAll(String... perms) {
        return Arrays.stream(perms).allMatch(this::has);
    }

    /** 店长及以上（审批类操作） */
    public boolean isManager() {
        LoginUser user = currentUser();
        if (user == null) {
            return false;
        }
        if (isSuperAdmin(user)) {
            return true;
        }
        Set<String> roles = user.getRoles() == null ? Set.of() : user.getRoles();
        return roles.contains("OPERATION") || roles.contains("STORE_MANAGER") || roles.contains("FINANCE");
    }

    public LoginUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LoginUser lu)) {
            return null;
        }
        return lu;
    }

    public LoginUser requireUser() {
        LoginUser user = currentUser();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    /** 小程序端：取当前会员 id */
    public Long requireMemberId() {
        LoginUser user = requireUser();
        if (!user.isMemberSide()) {
            throw new BizException(ErrorCode.FORBIDDEN, "该接口仅会员可访问");
        }
        return user.getUserId();
    }

    public Long currentUserId() {
        LoginUser user = currentUser();
        return user == null ? null : user.getUserId();
    }

    public Long currentStoreId() {
        LoginUser user = currentUser();
        return user == null ? null : user.getStoreId();
    }

    /** 数据权限：全部可见门店 id；返回 null 表示不限 */
    public List<Long> dataScopeStoreIds() {
        LoginUser user = currentUser();
        if (user == null) {
            return List.of(-1L);
        }
        Integer scope = user.getDataScope();
        if (scope == null || scope == 1) {
            return null;
        }
        if (scope == 2) {
            return user.getStoreId() == null ? List.of(-1L) : List.of(user.getStoreId());
        }
        if (scope == 4 && user.getScopeStoreIds() != null && !user.getScopeStoreIds().isBlank()) {
            return Arrays.stream(user.getScopeStoreIds().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(Long::valueOf).toList();
        }
        return List.of(-1L);
    }

    private boolean isSuperAdmin(LoginUser user) {
        return user.getRoles() != null && user.getRoles().contains(ROLE_SUPER_ADMIN);
    }

    /** 供日志使用 */
    public OperatorContext.Operator currentOperator() {
        LoginUser user = currentUser();
        return user == null ? null : user.toOperator();
    }

    private Long extractUserId(Claims claims) {
        Object v = claims.get(JwtTokenProvider.CLAIM_USER_ID);
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
