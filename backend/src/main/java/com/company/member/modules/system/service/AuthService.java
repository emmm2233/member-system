package com.company.member.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.member.common.cache.CacheService;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.modules.store.mapper.StoreMapper;
import com.company.member.modules.system.dto.LoginCmd;
import com.company.member.modules.system.entity.SysMenu;
import com.company.member.modules.system.entity.SysUser;
import com.company.member.modules.system.mapper.SysMenuMapper;
import com.company.member.modules.system.mapper.SysUserMapper;
import com.company.member.modules.system.vo.LoginVO;
import com.company.member.modules.system.vo.MenuVO;
import com.company.member.security.JwtTokenProvider;
import com.company.member.security.LoginUser;
import com.company.member.security.SecurityService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 认证服务：管理端登录 / 刷新 / 退出 / 菜单
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String LOGIN_FAIL_PREFIX = "login:fail:";
    private static final int MAX_FAIL_TIMES = 5;
    private static final int LOCK_MINUTES = 15;

    private final SysUserMapper sysUserMapper;
    private final SysMenuMapper sysMenuMapper;
    private final StoreMapper storeMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final SecurityService securityService;
    private final CacheService cacheService;
    private final LoginLogService loginLogService;

    /** 管理端登录 */
    @Transactional(rollbackFor = Exception.class)
    public LoginVO login(LoginCmd cmd, String ip) {
        String failKey = LOGIN_FAIL_PREFIX + cmd.getUsername();
        String failCount = cacheService.getString(failKey);
        if (failCount != null && Integer.parseInt(failCount) >= MAX_FAIL_TIMES) {
            loginLogService.adminFail(cmd.getUsername(), ip, null, "登录失败次数过多，已锁定");
            throw new BizException(ErrorCode.LOGIN_LOCKED);
        }

        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, cmd.getUsername())
                .eq(SysUser::getDeleted, 0)
                .last("LIMIT 1"));
        if (user == null || !passwordEncoder.matches(cmd.getPassword(), user.getPassword())) {
            long count = cacheService.increment(failKey, 1, 1);
            log.warn("[登录] 失败 username={}, ip={}, 累计失败={}", cmd.getUsername(), ip, count);
            loginLogService.adminFail(cmd.getUsername(), ip, null, "用户名或密码错误（累计失败 " + count + " 次）");
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            loginLogService.adminFail(cmd.getUsername(), ip, null, "账号已停用");
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        cacheService.delete(failKey);

        LoginUser loginUser = securityService.loadAdminUser(user.getId());
        if (loginUser == null) {
            loginLogService.adminFail(cmd.getUsername(), ip, null, "用户状态异常");
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        securityService.evictAdmin(user.getId()); // 清缓存，保证权限最新
        loginUser = securityService.loadAdminUser(user.getId());

        sysUserMapper.updateLoginInfo(user.getId(), LocalDateTime.now(), ip);
        loginLogService.adminSuccess(user.getId(), user.getUsername(), ip, null);
        log.info("[登录] 成功 username={}, userId={}, ip={}", user.getUsername(), user.getId(), ip);
        return buildLoginVO(loginUser, user);
    }

    /** 刷新令牌 */
    public LoginVO refresh(String refreshToken) {
        Claims claims;
        try {
            claims = tokenProvider.parse(refreshToken);
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "刷新令牌无效或已过期，请重新登录");
        }
        if (!tokenProvider.isRefreshToken(claims)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "令牌类型不正确");
        }
        LoginUser loginUser = securityService.loadLoginUser(claims);
        if (loginUser == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户不存在或已停用");
        }
        SysUser user = sysUserMapper.selectById(loginUser.getUserId());
        return buildLoginVO(loginUser, user);
    }

    /** 退出登录：递增 token_version 使旧 token 失效 */
    @Transactional(rollbackFor = Exception.class)
    public void logout(Long userId) {
        if (userId == null) {
            return;
        }
        sysUserMapper.bumpTokenVersion(userId);
        securityService.evictAdmin(userId);
        log.info("[登录] 退出 userId={}", userId);
    }

    /** 修改密码（成功后强制重新登录） */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BizException(ErrorCode.OLD_PASSWORD_WRONG);
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new BizException(ErrorCode.PARAM_INVALID, "新密码长度不少于 8 位");
        }
        SysUser update = new SysUser();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(newPassword));
        update.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        sysUserMapper.updateById(update);
        securityService.evictAdmin(userId);
        log.info("[登录] 修改密码 userId={}", userId);
    }

    /** 当前用户信息 */
    public LoginVO.UserInfoVO currentUserInfo() {
        LoginUser loginUser = securityService.requireUser();
        SysUser user = sysUserMapper.selectById(loginUser.getUserId());
        return buildUserInfo(loginUser, user);
    }

    /** 当前用户菜单树 */
    public List<MenuVO> menus() {
        LoginUser loginUser = securityService.requireUser();
        List<SysMenu> menus = isSuperAdmin(loginUser)
                ? sysMenuMapper.selectAllVisible()
                : sysMenuMapper.selectByUserId(loginUser.getUserId());
        return buildTree(menus);
    }

    /* ==================== 内部方法 ==================== */

    private LoginVO buildLoginVO(LoginUser loginUser, SysUser user) {
        LoginVO vo = new LoginVO();
        vo.setAccessToken(tokenProvider.createAccessToken(loginUser));
        vo.setRefreshToken(tokenProvider.createRefreshToken(loginUser));
        vo.setExpiresIn(tokenProvider.getAccessTtl());
        vo.setUserInfo(buildUserInfo(loginUser, user));
        return vo;
    }

    private LoginVO.UserInfoVO buildUserInfo(LoginUser loginUser, SysUser user) {
        LoginVO.UserInfoVO info = new LoginVO.UserInfoVO();
        info.setUserId(loginUser.getUserId());
        info.setUsername(loginUser.getUsername());
        info.setRealName(loginUser.getRealName());
        info.setStoreId(loginUser.getStoreId());
        info.setDataScope(loginUser.getDataScope());
        info.setUserType(loginUser.getUserType());
        info.setRoles(new ArrayList<>(loginUser.getRoles() == null ? java.util.Set.of() : loginUser.getRoles()));
        info.setPerms(loginUser.getPerms() == null ? java.util.Set.of() : loginUser.getPerms());
        if (user != null) {
            info.setPhone(user.getPhone());
        }
        if (loginUser.getStoreId() != null) {
            info.setStoreName(storeMapper.selectNameById(loginUser.getStoreId()));
        }
        return info;
    }

    private boolean isSuperAdmin(LoginUser user) {
        return user.getRoles() != null && user.getRoles().contains(SecurityService.ROLE_SUPER_ADMIN);
    }

    /** 扁平菜单 → 树 */
    private List<MenuVO> buildTree(List<SysMenu> menus) {
        Map<Long, MenuVO> map = menus.stream().collect(Collectors.toMap(SysMenu::getId, this::toMenuVO, (a, b) -> a));
        List<MenuVO> roots = new ArrayList<>();
        for (SysMenu menu : menus) {
            MenuVO node = map.get(menu.getId());
            Long parentId = menu.getParentId();
            if (parentId == null || parentId == 0 || !map.containsKey(parentId)) {
                roots.add(node);
            } else {
                map.get(parentId).getChildren().add(node);
            }
        }
        sortTree(roots);
        return roots;
    }

    private void sortTree(List<MenuVO> nodes) {
        nodes.sort(Comparator.comparing(m -> m.getSortNo() == null ? 0 : m.getSortNo()));
        nodes.forEach(n -> sortTree(n.getChildren()));
    }

    private MenuVO toMenuVO(SysMenu menu) {
        MenuVO vo = new MenuVO();
        vo.setId(menu.getId());
        vo.setParentId(menu.getParentId());
        vo.setMenuType(menu.getMenuType());
        vo.setMenuName(menu.getMenuName());
        vo.setPermCode(menu.getPermCode());
        vo.setPath(menu.getRoutePath());
        vo.setComponent(menu.getComponent());
        vo.setIcon(menu.getIcon());
        vo.setSortNo(menu.getSortNo());
        vo.setHidden(menu.getVisible() != null && menu.getVisible() == 0);
        return vo;
    }

    /** 供登录失败计数使用（测试友好） */
    public void resetLoginFail(String username) {
        cacheService.delete(LOGIN_FAIL_PREFIX + username);
    }

    /** 缓存预热：把常用数据放入缓存（可选） */
    public void warmUp() {
        cacheService.set("app:warmup", "1", 1, TimeUnit.MINUTES);
    }
}
