package com.company.member.modules.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.member.common.annotation.OpAudit;
import com.company.member.common.cache.CacheReadinessChecker;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.result.PageResult;
import com.company.member.common.result.R;
import com.company.member.modules.system.entity.SysMenu;
import com.company.member.modules.system.entity.SysRole;
import com.company.member.modules.system.entity.SysUser;
import com.company.member.modules.system.mapper.SysMenuMapper;
import com.company.member.modules.system.mapper.SysRoleMapper;
import com.company.member.modules.system.mapper.SysUserMapper;
import com.company.member.modules.system.service.SchedulerLockService;
import com.company.member.security.SecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端 · 系统管理（用户 / 角色 / 菜单）
 *
 * <p>权限变更后立即清除权限缓存，保证前端下一次请求即生效。
 */
@Slf4j
@Tag(name = "管理端-系统管理")
@RestController
@RequestMapping("/api/admin/system")
@RequiredArgsConstructor
public class SystemController {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysMenuMapper menuMapper;
    private final PasswordEncoder passwordEncoder;
    private final SecurityService securityService;
    private final CacheReadinessChecker cacheReadinessChecker;
    private final SchedulerLockService schedulerLockService;

    /* ==================================================================
     *  用户
     * ================================================================== */

    @Operation(summary = "系统用户分页")
    @GetMapping("/users")
    @PreAuthorize("@ps.has('system:user:list')")
    public R<PageResult<Map<String, Object>>> users(@RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) Integer status,
                                                    @RequestParam(defaultValue = "1") int pageNum,
                                                    @RequestParam(defaultValue = "20") int pageSize) {
        LambdaQueryWrapper<SysUser> w = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0)
                .eq(status != null, SysUser::getStatus, status)
                .and(keyword != null && !keyword.isBlank(),
                        q -> q.like(SysUser::getUsername, keyword).or().like(SysUser::getRealName, keyword))
                .orderByDesc(SysUser::getId);
        Page<SysUser> page = userMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 200)), w);

        List<Map<String, Object>> list = new ArrayList<>();
        for (SysUser u : page.getRecords()) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("realName", u.getRealName());
            m.put("phone", u.getPhone());
            m.put("storeId", u.getStoreId());
            m.put("staffId", u.getStaffId());
            m.put("dataScope", u.getDataScope());
            m.put("scopeStoreIds", u.getScopeStoreIds());
            m.put("status", u.getStatus());
            m.put("lastLoginAt", u.getLastLoginAt());
            m.put("createdAt", u.getCreatedAt());
            m.put("roleCodes", userMapper.selectRoleCodes(u.getId()));
            list.add(m);
        }
        return R.ok(PageResult.of(list, page.getTotal(), pageNum, page.getSize()));
    }

    @Data
    public static class UserSaveCmd {
        private Long id;
        private String username;
        private String password;
        private String realName;
        private String phone;
        private Long storeId;
        private Long staffId;
        /** 1全部 2本店 3本人 4自定义 */
        private Integer dataScope = 2;
        private String scopeStoreIds;
        private Integer status = 1;
        private List<Long> roleIds = new ArrayList<>();
    }

    @Operation(summary = "新增系统用户")
    @PostMapping("/users")
    @PreAuthorize("@ps.has('system:user:add')")
    @Transactional(rollbackFor = Exception.class)
    public R<Long> createUser(@Valid @RequestBody UserSaveCmd cmd) {
        if (cmd.getUsername() == null || cmd.getUsername().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "登录账号不能为空");
        }
        if (cmd.getPassword() == null || cmd.getPassword().length() < 8) {
            throw new BizException(ErrorCode.PARAM_INVALID, "密码长度不少于 8 位");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, cmd.getUsername()).eq(SysUser::getDeleted, 0)) > 0) {
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        }
        SysUser user = new SysUser();
        user.setUsername(cmd.getUsername().trim());
        user.setPassword(passwordEncoder.encode(cmd.getPassword()));
        user.setRealName(cmd.getRealName());
        user.setPhone(cmd.getPhone());
        user.setStoreId(cmd.getStoreId());
        user.setStaffId(cmd.getStaffId());
        user.setDataScope(cmd.getDataScope() == null ? 2 : cmd.getDataScope());
        user.setScopeStoreIds(cmd.getScopeStoreIds());
        user.setStatus(cmd.getStatus() == null ? 1 : cmd.getStatus());
        user.setTokenVersion(0);
        user.setDeleted(0);
        user.setTenantId(0L);
        userMapper.insert(user);
        bindRoles(user.getId(), cmd.getRoleIds());
        log.warn("[系统] 新增用户 {} (id={}), 角色={}", user.getUsername(), user.getId(), cmd.getRoleIds());
        return R.ok(user.getId());
    }

    @Operation(summary = "修改系统用户（不传密码则不修改）")
    @PutMapping("/users/{id}")
    @PreAuthorize("@ps.has('system:user:edit')")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> updateUser(@PathVariable Long id, @RequestBody UserSaveCmd cmd) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "用户不存在");
        }
        SysUser update = new SysUser();
        update.setId(id);
        update.setRealName(cmd.getRealName());
        update.setPhone(cmd.getPhone());
        update.setStoreId(cmd.getStoreId());
        update.setStaffId(cmd.getStaffId());
        update.setDataScope(cmd.getDataScope());
        update.setScopeStoreIds(cmd.getScopeStoreIds());
        update.setStatus(cmd.getStatus());
        if (cmd.getPassword() != null && !cmd.getPassword().isBlank()) {
            if (cmd.getPassword().length() < 8) {
                throw new BizException(ErrorCode.PARAM_INVALID, "密码长度不少于 8 位");
            }
            update.setPassword(passwordEncoder.encode(cmd.getPassword()));
            update.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        }
        userMapper.updateById(update);
        if (cmd.getRoleIds() != null) {
            bindRoles(id, cmd.getRoleIds());
        }
        securityService.evictAdmin(id);
        log.warn("[系统] 修改用户 id={}, 角色={}", id, cmd.getRoleIds());
        return R.ok();
    }

    @Operation(summary = "启用/停用用户（停用即踢下线）")
    @PutMapping("/users/{id}/status")
    @PreAuthorize("@ps.has('system:user:edit')")
    public R<Void> changeUserStatus(@PathVariable Long id, @RequestParam Integer status) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "用户不存在");
        }
        if (id.equals(securityService.currentUserId()) && status == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "不能停用当前登录账号");
        }
        SysUser update = new SysUser();
        update.setId(id);
        update.setStatus(status);
        if (status == 0) {
            update.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        }
        userMapper.updateById(update);
        securityService.evictAdmin(id);
        log.warn("[系统] 用户 {} 状态变更为 {}", user.getUsername(), status);
        return R.ok();
    }

    @Operation(summary = "重置密码")
    @PostMapping("/users/{id}/reset-password")
    @PreAuthorize("@ps.has('system:user:reset')")
    @OpAudit(opType = "UPDATE", desc = "重置用户密码")
    public R<String> resetPassword(@PathVariable Long id, @RequestParam(required = false) String newPassword) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "用户不存在");
        }
        String pwd = newPassword == null || newPassword.isBlank() ? "Member@" + (100000 + (int) (Math.random() * 899999))
                : newPassword;
        if (pwd.length() < 8) {
            throw new BizException(ErrorCode.PARAM_INVALID, "密码长度不少于 8 位");
        }
        SysUser update = new SysUser();
        update.setId(id);
        update.setPassword(passwordEncoder.encode(pwd));
        update.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        userMapper.updateById(update);
        securityService.evictAdmin(id);
        log.warn("[系统] 重置用户 {} 密码", user.getUsername());
        // 仅在创建者主动指定时不回显；自动生成时回显一次便于转告
        return R.ok(newPassword == null || newPassword.isBlank() ? pwd : "已重置");
    }

    private void bindRoles(Long userId, List<Long> roleIds) {
        if (roleIds == null) {
            return;
        }
        // 先清理该用户已有角色，再按新列表绑定
        userMapper.deleteUserRoles(userId);
        for (Long roleId : roleIds.stream().distinct().toList()) {
            try {
                userMapper.insertUserRole(userId, roleId);
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                // 并发重复绑定忽略
            }
        }
    }

    /* ==================================================================
     *  角色
     * ================================================================== */

    @Operation(summary = "角色列表")
    @GetMapping("/roles")
    @PreAuthorize("@ps.has('system:role:list')")
    public R<List<Map<String, Object>>> roles() {
        List<SysRole> roles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getDeleted, 0).orderByAsc(SysRole::getSortNo));
        List<Map<String, Object>> list = new ArrayList<>();
        for (SysRole r : roles) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", r.getId());
            m.put("roleCode", r.getRoleCode());
            m.put("roleName", r.getRoleName());
            m.put("dataScope", r.getDataScope());
            m.put("sortNo", r.getSortNo());
            m.put("status", r.getStatus());
            m.put("remark", r.getRemark());
            m.put("userCount", userMapper.countByRoleId(r.getId()));
            m.put("menuCount", roleMapper.countMenus(r.getId()));
            list.add(m);
        }
        return R.ok(list);
    }

    @Data
    public static class RoleSaveCmd {
        private Long id;
        private String roleCode;
        private String roleName;
        private Integer dataScope = 2;
        private Integer sortNo = 0;
        private Integer status = 1;
        private String remark;
    }

    @Operation(summary = "新增角色")
    @PostMapping("/roles")
    @PreAuthorize("@ps.has('system:role:add')")
    @Transactional(rollbackFor = Exception.class)
    public R<Long> createRole(@Valid @RequestBody RoleSaveCmd cmd) {
        if (cmd.getRoleCode() == null || cmd.getRoleCode().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "角色编码不能为空");
        }
        if (roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, cmd.getRoleCode()).eq(SysRole::getDeleted, 0)) > 0) {
            throw new BizException(ErrorCode.ROLE_CODE_EXISTS);
        }
        SysRole role = new SysRole();
        role.setRoleCode(cmd.getRoleCode().trim().toUpperCase());
        role.setRoleName(cmd.getRoleName());
        role.setDataScope(cmd.getDataScope());
        role.setSortNo(cmd.getSortNo());
        role.setStatus(cmd.getStatus());
        role.setRemark(cmd.getRemark());
        role.setDeleted(0);
        role.setTenantId(0L);
        roleMapper.insert(role);
        log.warn("[系统] 新增角色 {} (id={})", role.getRoleCode(), role.getId());
        return R.ok(role.getId());
    }

    @Operation(summary = "修改角色")
    @PutMapping("/roles/{id}")
    @PreAuthorize("@ps.has('system:role:edit')")
    public R<Void> updateRole(@PathVariable Long id, @RequestBody RoleSaveCmd cmd) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "角色不存在");
        }
        SysRole update = new SysRole();
        update.setId(id);
        update.setRoleName(cmd.getRoleName());
        update.setDataScope(cmd.getDataScope());
        update.setSortNo(cmd.getSortNo());
        update.setStatus(cmd.getStatus());
        update.setRemark(cmd.getRemark());
        roleMapper.updateById(update);
        log.warn("[系统] 修改角色 id={}", id);
        return R.ok();
    }

    @Operation(summary = "角色已授权菜单 id 列表")
    @GetMapping("/roles/{id}/menus")
    @PreAuthorize("@ps.has('system:role:query')")
    public R<List<Long>> roleMenus(@PathVariable Long id) {
        return R.ok(roleMapper.selectMenuIds(id));
    }

    @Operation(summary = "保存角色菜单权限（改动后相关用户权限即时生效）")
    @PutMapping("/roles/{id}/menus")
    @PreAuthorize("@ps.has('system:role:edit')")
    @Transactional(rollbackFor = Exception.class)
    @OpAudit(opType = "UPDATE", desc = "角色菜单授权")
    public R<Void> saveRoleMenus(@PathVariable Long id, @RequestBody List<Long> menuIds) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "角色不存在");
        }
        roleMapper.deleteRoleMenus(id);
        if (menuIds != null) {
            for (Long menuId : menuIds.stream().distinct().toList()) {
                try {
                    roleMapper.insertRoleMenu(id, menuId);
                } catch (org.springframework.dao.DuplicateKeyException ignored) {
                    // 忽略重复
                }
            }
        }
        // 清除该角色下所有用户的权限缓存
        List<Long> userIds = roleMapper.selectUserIdsByRole(id);
        userIds.forEach(securityService::evictAdmin);
        log.warn("[系统] 角色 {} 权限更新，菜单 {} 项，影响用户 {} 人", role.getRoleCode(),
                menuIds == null ? 0 : menuIds.size(), userIds.size());
        return R.ok();
    }

    @Operation(summary = "删除角色（有用户占用则拒绝）")
    @DeleteMapping("/roles/{id}")
    @PreAuthorize("@ps.has('system:role:edit')")
    public R<Void> removeRole(@PathVariable Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "角色不存在");
        }
        if ("SUPER_ADMIN".equals(role.getRoleCode())) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "超级管理员角色不可删除");
        }
        if (userMapper.countByRoleId(id) > 0) {
            throw new BizException(ErrorCode.ROLE_IN_USE);
        }
        SysRole update = new SysRole();
        update.setId(id);
        update.setDeleted(1);
        roleMapper.updateById(update);
        log.warn("[系统] 删除角色 {}", role.getRoleCode());
        return R.ok();
    }

    /* ==================================================================
     *  菜单
     * ================================================================== */

    @Operation(summary = "菜单树（权限分配用，含按钮）")
    @GetMapping("/menus/tree")
    @PreAuthorize("@ps.has('system:menu:list')")
    public R<List<Map<String, Object>>> menuTree() {
        List<SysMenu> menus = menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .orderByAsc(SysMenu::getParentId).orderByAsc(SysMenu::getSortNo));
        Map<Long, List<SysMenu>> byParent = new HashMap<>();
        for (SysMenu m : menus) {
            byParent.computeIfAbsent(m.getParentId() == null ? 0L : m.getParentId(), k -> new ArrayList<>()).add(m);
        }
        return R.ok(buildTree(0L, byParent));
    }

    private List<Map<String, Object>> buildTree(Long parentId, Map<Long, List<SysMenu>> byParent) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (SysMenu m : byParent.getOrDefault(parentId, List.of())) {
            Map<String, Object> node = new HashMap<>();
            node.put("id", m.getId());
            node.put("parentId", m.getParentId());
            node.put("menuType", m.getMenuType());
            node.put("menuName", m.getMenuName());
            node.put("permCode", m.getPermCode());
            node.put("routePath", m.getRoutePath());
            node.put("sortNo", m.getSortNo());
            List<Map<String, Object>> children = buildTree(m.getId(), byParent);
            node.put("children", children);
            list.add(node);
        }
        return list;
    }

    /* ==================================================================
     *  操作日志
     * ================================================================== */

    @Operation(summary = "操作日志分页")
    @GetMapping("/op-logs")
    @PreAuthorize("@ps.has('system:log:list')")
    public R<PageResult<Map<String, Object>>> opLogs(@RequestParam(required = false) String module,
                                                     @RequestParam(required = false) String opType,
                                                     @RequestParam(required = false) Long userId,
                                                     @RequestParam(required = false) LocalDateTime startTime,
                                                     @RequestParam(required = false) LocalDateTime endTime,
                                                     @RequestParam(defaultValue = "1") int pageNum,
                                                     @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(opLogQuery.list(module, opType, userId, startTime, endTime, pageNum, pageSize));
    }

    @Operation(summary = "登录日志分页")
    @GetMapping("/login-logs")
    @PreAuthorize("@ps.has('system:log:list')")
    public R<PageResult<Map<String, Object>>> loginLogs(@RequestParam(required = false) String username,
                                                        @RequestParam(required = false) Integer status,
                                                        @RequestParam(defaultValue = "1") int pageNum,
                                                        @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(opLogQuery.loginLogs(username, status, pageNum, pageSize));
    }

    /**
     * 缓存与多实例就绪状态。
     *
     * <p>为什么值得暴露到管理端而不是只打启动日志：启动日志没人天天看，
     * 而「因为起第二个实例导致幂等键/登录锁定静默失效」是那种<b>不出错、只出错数据</b>的问题。
     * 放在运维能点到的地方，才有可能在扩容前被看到。
     */
    @Operation(summary = "缓存与多实例就绪状态（含风险清单，不返回凭据）")
    @GetMapping("/cache-status")
    @PreAuthorize("@ps.has('system:config:list')")
    public R<Map<String, Object>> cacheStatus() {
        return R.ok(cacheReadinessChecker.status());
    }

    /**
     * 定时任务锁表清单（谁持着锁、持到什么时候、是否已过期）。
     *
     * <p>没有这个视图时，「某个定时任务不跑了」只能靠翻日志猜；有了它，一眼能看出
     * 「锁行还在、locked_by 是某个已下线的实例」还是「锁行被删了」。
     */
    @Operation(summary = "定时任务锁清单（ShedLock，含过期判定）")
    @GetMapping("/scheduler-locks")
    @PreAuthorize("@ps.has('system:config:list')")
    public R<List<Map<String, Object>>> schedulerLocks() {
        return R.ok(schedulerLockService.list());
    }

    /**
     * 重置定时任务锁的「本地记忆」。
     *
     * <p><b>为什么必须提供这个动作</b>：ShedLock 的存储型 provider 会记住「这条锁记录我已插入过」，
     * 之后只做条件 UPDATE。若锁行被表外删除（清表/恢复备份/手工改数据），
     * UPDATE 永远匹配不到，任务会被永久判定为「已被锁住」而静默跳过 —— 直到重启。
     * 清一次本地记忆即可自愈，比重启整个服务代价小得多。
     */
    @Operation(summary = "重置定时任务锁本地记忆（维护过 shedlock 表后必须调用）")
    @PostMapping("/scheduler-locks/reset-cache")
    @PreAuthorize("@ps.has('system:config:edit')")
    public R<Map<String, Object>> resetSchedulerLockCache() {
        return R.ok(schedulerLockService.resetCache());
    }

    private final com.company.member.modules.system.service.OpLogQueryService opLogQuery;
}
