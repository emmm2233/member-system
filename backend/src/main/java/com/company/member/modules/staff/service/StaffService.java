package com.company.member.modules.staff.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.result.PageResult;
import com.company.member.modules.staff.dto.StaffQuery;
import com.company.member.modules.staff.dto.StaffSaveCmd;
import com.company.member.modules.staff.entity.Staff;
import com.company.member.modules.staff.mapper.StaffMapper;
import com.company.member.modules.store.entity.Store;
import com.company.member.modules.store.mapper.StoreMapper;
import com.company.member.modules.system.entity.SysUser;
import com.company.member.modules.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 员工档案
 *
 * <p><b>本服务的核心职责是让「员工」与「登录账号」成为两条独立的生命周期</b>：
 *
 * <pre>
 *   员工离职（leave）    → m_staff.status=0 + leave_date，账号一动不动，
 *                         但返回提示「该员工仍有启用中的账号，建议一并停用」
 *   账号停用（系统管理）  → m_sys_user.status=0，员工档案一动不动
 *   员工复职（reinstate）→ status=1、清 leave_date，原关联账号自动恢复可用性判断
 * </pre>
 *
 * <p>为什么不自动联动？因为「人走了」和「钥匙收了」在真实门店里经常不同步：
 * 员工离职当天可能还要用账号交接，而财务/运营岗可能压根没有员工档案。
 * 自动联动看似省事，实际会制造「账号被系统悄悄停掉，人却还在上班」的故障。
 * 因此这里只<b>提示</b>，由人来决定。
 *
 * <p><b>业绩归属</b>：{@code m_order.staff_id} 指向本表。
 * 因此离职绝不能删记录 —— 删了历史订单的业绩就悬空了。
 * 有业绩的员工连逻辑删除都会被拒绝，只能离职。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffService {

    /** 工号前缀与序号位数（种子数据用的是 E001 风格，保持人眼友好） */
    private static final String NO_PREFIX = "E";
    private static final int NO_WIDTH = 3;

    private final StaffMapper staffMapper;
    private final StoreMapper storeMapper;
    private final SysUserMapper sysUserMapper;

    /* ==================== 1. 查询 ==================== */

    public PageResult<Map<String, Object>> page(StaffQuery query) {
        LambdaQueryWrapper<Staff> w = new LambdaQueryWrapper<Staff>()
                .eq(Staff::getDeleted, 0)
                .eq(query.getStoreId() != null, Staff::getStoreId, query.getStoreId())
                .eq(query.getStatus() != null, Staff::getStatus, query.getStatus())
                .eq(query.getPosition() != null && !query.getPosition().isBlank(),
                        Staff::getPosition, query.getPosition())
                .and(query.getKeyword() != null && !query.getKeyword().isBlank(),
                        q -> q.like(Staff::getStaffName, query.getKeyword())
                                .or().like(Staff::getStaffNo, query.getKeyword())
                                .or().like(Staff::getPhone, query.getKeyword()));
        // 在职在前，同店内按工号排（离职的人沉到列表底部，不干扰日常操作）
        w.orderByDesc(Staff::getStatus).orderByAsc(Staff::getStoreId).orderByAsc(Staff::getStaffNo);

        Page<Staff> page = staffMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), w);
        Set<Long> storeIds = new HashSet<>();
        page.getRecords().forEach(s -> {
            if (s.getStoreId() != null) {
                storeIds.add(s.getStoreId());
            }
        });
        Map<Long, String> storeNames = loadStoreNames(storeIds);
        Map<Long, SysUser> accounts = loadAccounts(page.getRecords().stream().map(Staff::getId).toList());

        return PageResult.of(page, s -> toVo(s, storeNames, accounts.get(s.getId()), false));
    }

    /** 员工详情：附关联账号与业绩汇总 */
    public Map<String, Object> detail(Long id) {
        Staff staff = requireStaff(id);
        Set<Long> storeIds = staff.getStoreId() == null ? Set.of() : Set.of(staff.getStoreId());
        Map<Long, String> storeNames = loadStoreNames(storeIds);
        SysUser account = findAccountByStaffId(id);
        Map<String, Object> vo = toVo(staff, storeNames, account, true);
        vo.put("orderCount", staffMapper.countOrders(id));
        return vo;
    }

    /** 可选登录账号：未绑定任何员工、且未删除的账号 */
    public List<Map<String, Object>> accountCandidates() {
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0)
                .isNull(SysUser::getStaffId)
                .orderByAsc(SysUser::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SysUser u : users) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("realName", u.getRealName());
            m.put("storeId", u.getStoreId());
            m.put("status", u.getStatus());
            out.add(m);
        }
        return out;
    }

    /** 岗位候选（门店常见岗位 + 已使用过的岗位，避免写死） */
    public List<String> positions() {
        List<String> out = new ArrayList<>(List.of("店长", "副店长", "收银员", "导购", "库管", "运营"));
        List<Staff> all = staffMapper.selectList(new LambdaQueryWrapper<Staff>()
                .eq(Staff::getDeleted, 0)
                .select(Staff::getPosition));
        for (Staff s : all) {
            String p = s.getPosition();
            if (p != null && !p.isBlank() && !out.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 门店下拉（含在职人数），供员工表单与收银台选人使用 */
    public List<Map<String, Object>> storeOptions() {        List<Store> stores = storeMapper.selectList(new LambdaQueryWrapper<Store>()
                .eq(Store::getDeleted, 0)
                .orderByAsc(Store::getSortNo).orderByAsc(Store::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Store s : stores) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("storeCode", s.getStoreCode());
            m.put("storeName", s.getStoreName());
            m.put("status", s.getStatus());
            m.put("staffCount", staffMapper.countActiveByStoreId(s.getId()));
            out.add(m);
        }
        return out;
    }

    /** 在职员工（收银台按门店选业绩归属用） */
    public List<Staff> activeByStore(Long storeId) {
        return staffMapper.selectActiveByStore(storeId);
    }

    /* ==================== 2. 增改 ==================== */

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(StaffSaveCmd cmd) {
        requireStore(cmd.getStoreId());
        Staff staff = new Staff();
        staff.setStaffNo(resolveStaffNo(cmd.getStaffNo(), null));
        applyEditable(staff, cmd);
        staff.setStatus(cmd.getStatus() == null ? Staff.ST_ACTIVE : cmd.getStatus());
        staff.setTenantId(0L);
        staff.setDeleted(0);
        try {
            staffMapper.insert(staff);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "工号已存在：" + staff.getStaffNo());
        }
        if (cmd.getUserId() != null) {
            bindAccount(staff.getId(), cmd.getUserId());
        }
        log.info("[员工] 新增 id={}, 工号={}, 姓名={}, 门店={}",
                staff.getId(), staff.getStaffNo(), staff.getStaffName(), staff.getStoreId());
        return detail(staff.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(Long id, StaffSaveCmd cmd) {
        Staff exist = requireStaff(id);
        requireStore(cmd.getStoreId());
        if (cmd.getStaffNo() != null && !cmd.getStaffNo().isBlank()
                && !cmd.getStaffNo().equals(exist.getStaffNo())) {
            exist.setStaffNo(resolveStaffNo(cmd.getStaffNo(), id));
        }
        applyEditable(exist, cmd);
        if (cmd.getStatus() != null) {
            // 通过编辑表单直接改状态时，同步维护离职日期，避免出现「离职但没离职日期」
            if (cmd.getStatus() == Staff.ST_LEFT && exist.getLeaveDate() == null) {
                exist.setLeaveDate(LocalDate.now());
            }
            if (cmd.getStatus() == Staff.ST_ACTIVE) {
                exist.setLeaveDate(null);
            }
            exist.setStatus(cmd.getStatus());
        }
        try {
            staffMapper.updateById(exist);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "工号已存在：" + exist.getStaffNo());
        }
        if (cmd.getUserId() != null) {
            bindAccount(id, cmd.getUserId());
        }
        log.info("[员工] 更新 id={}, 工号={}", id, exist.getStaffNo());
        return detail(id);
    }

    private void applyEditable(Staff staff, StaffSaveCmd cmd) {
        staff.setStaffName(cmd.getStaffName());
        staff.setPhone(cmd.getPhone());
        staff.setStoreId(cmd.getStoreId());
        staff.setPosition(cmd.getPosition());
        staff.setEntryDate(cmd.getEntryDate() == null && staff.getEntryDate() == null
                ? LocalDate.now() : (cmd.getEntryDate() == null ? staff.getEntryDate() : cmd.getEntryDate()));
        if (cmd.getLeaveDate() != null) {
            staff.setLeaveDate(cmd.getLeaveDate());
        }
    }

    /* ==================== 3. 离职 / 复职 ==================== */

    /**
     * 办理离职
     *
     * <p><b>刻意不动账号</b>：只改员工状态，并把「该员工还挂着启用中的账号」作为提示返回。
     * 自动停账号会造成「人还在交接、账号先被系统锁了」的现场事故。
     *
     * @return 离职结果 + 账号处理建议
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> leave(Long id, LocalDate leaveDate, String reason) {
        Staff staff = requireStaff(id);
        if (!staff.isActive()) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "该员工已是离职状态");
        }
        LocalDate date = leaveDate == null ? LocalDate.now() : leaveDate;
        if (staff.getEntryDate() != null && date.isBefore(staff.getEntryDate())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "离职日期不能早于入职日期");
        }
        if (staffMapper.markLeft(id, date) == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "离职失败，该员工状态已变更，请刷新后重试");
        }
        SysUser account = findAccountByStaffId(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("staffId", id);
        out.put("staffNo", staff.getStaffNo());
        out.put("leaveDate", date);
        out.put("reason", reason);
        out.put("orderCount", staffMapper.countOrders(id));
        boolean accountStillOn = account != null && account.getStatus() != null && account.getStatus() == 1;
        out.put("accountStillActive", accountStillOn);
        out.put("accountUsername", account == null ? null : account.getUsername());
        out.put("tip", accountStillOn
                ? "员工已离职；其登录账号 " + account.getUsername() + " 仍处于启用状态，如需禁止登录请到「用户与权限」停用该账号"
                : "员工已离职，未发现启用中的关联账号");
        log.info("[员工] 离职 id={}, 工号={}, 离职日期={}, 账号仍启用={}", id, staff.getStaffNo(), date, accountStillOn);
        return out;
    }

    /** 复职：恢复在职、清离职日期；原关联账号若已停用会一并提示 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reinstate(Long id) {
        Staff staff = requireStaff(id);
        if (staff.isActive()) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "该员工已是在职状态");
        }
        if (staffMapper.markReinstated(id, LocalDate.now()) == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "复职失败，该员工状态已变更，请刷新后重试");
        }
        SysUser account = findAccountByStaffId(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("staffId", id);
        out.put("staffNo", staff.getStaffNo());
        boolean accountOn = account != null && account.getStatus() != null && account.getStatus() == 1;
        out.put("accountUsername", account == null ? null : account.getUsername());
        out.put("accountActive", accountOn);
        out.put("tip", account == null
                ? "员工已复职；该员工尚未关联登录账号"
                : (accountOn ? "员工已复职，关联账号可正常登录"
                        : "员工已复职，但其账号 " + account.getUsername() + " 仍是停用状态，请到「用户与权限」启用"));
        log.info("[员工] 复职 id={}, 工号={}, 账号可登录={}", id, staff.getStaffNo(), accountOn);
        return out;
    }

    /**
     * 删除（逻辑删除）
     *
     * <p>有业绩的员工一律拒绝删除 —— 否则历史订单的 {@code staff_id} 会指向一条已删除的记录，
     * 报表里这个人就凭空消失了。正确做法是「离职」。
     */
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        Staff staff = requireStaff(id);
        int orders = staffMapper.countOrders(id);
        if (orders > 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED,
                    "该员工有 " + orders + " 笔历史业绩，不能删除；请改为「办理离职」，以保留业绩归属");
        }
        SysUser account = findAccountByStaffId(id);
        if (account != null) {
            // 先解绑，否则账号会挂着一个已删除的 staff_id
            sysUserMapper.clearStaffId(account.getId());
        }
        // 门店店长引用也清掉，避免 m_store.manager_id 悬空
        storeMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Store>()
                .eq(Store::getManagerId, id)
                .set(Store::getManagerId, null));
        staffMapper.softDelete(id);
        log.info("[员工] 删除 id={}, 工号={}", id, staff.getStaffNo());
    }

    /* ==================== 4. 账号关联 ==================== */

    /**
     * 关联登录账号
     *
     * <p>三条约束：账号存在且未删除、账号未绑定其他员工、员工未绑定其他账号。
     * 若账号还没有默认门店，顺手同步成员工所属门店 —— 否则该账号的数据权限会是空的。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> bindAccount(Long staffId, Long userId) {
        Staff staff = requireStaff(staffId);
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || (user.getDeleted() != null && user.getDeleted() == 1)) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "登录账号不存在");
        }
        if (user.getStaffId() != null && !user.getStaffId().equals(staffId)) {
            Staff other = staffMapper.selectAliveById(user.getStaffId());
            throw new BizException(ErrorCode.OPERATION_FAILED,
                    "账号 " + user.getUsername() + " 已关联员工「"
                            + (other == null ? user.getStaffId() : other.getStaffName()) + "」，请先解绑");
        }
        SysUser bound = findAccountByStaffId(staffId);
        if (bound != null && !bound.getId().equals(userId)) {
            throw new BizException(ErrorCode.OPERATION_FAILED,
                    "该员工已关联账号 " + bound.getUsername() + "，请先解绑");
        }
        int updated = sysUserMapper.bindStaff(userId, staffId);
        if (updated == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "关联失败，请刷新后重试");
        }
        // 账号没有默认门店时同步员工门店，否则数据权限落在空门店上
        boolean storeSynced = false;
        if (user.getStoreId() == null && staff.getStoreId() != null) {
            storeSynced = sysUserMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysUser>()
                            .eq(SysUser::getId, userId)
                            .set(SysUser::getStoreId, staff.getStoreId())) > 0;
        }
        log.info("[员工] 关联账号 staffId={}, userId={}, 同步门店={}", staffId, userId, storeSynced);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("staffId", staffId);
        out.put("userId", userId);
        out.put("username", user.getUsername());
        out.put("storeSynced", storeSynced);
        out.put("tip", storeSynced
                ? "已关联账号并同步默认门店；如需数据权限生效，请确认该账号的角色数据范围"
                : "已关联账号");
        return out;
    }

    /** 解绑登录账号（账号本身不受影响，只是不再代表某个员工） */
    @Transactional(rollbackFor = Exception.class)
    public void unbindAccount(Long staffId) {
        requireStaff(staffId);
        SysUser account = findAccountByStaffId(staffId);
        if (account == null) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "该员工未关联登录账号");
        }
        sysUserMapper.clearStaffId(account.getId());
        log.info("[员工] 解绑账号 staffId={}, userId={}", staffId, account.getId());
    }

    /** 当前登录账号绑定的员工（结算时自动带出业绩归属用） */
    public Staff findByAccount(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getStaffId() == null) {
            return null;
        }
        Staff staff = staffMapper.selectAliveById(user.getStaffId());
        return staff != null && staff.isActive() ? staff : null;
    }

    /* ==================== 5. 内部工具 ==================== */

    public Staff requireStaff(Long id) {
        Staff staff = id == null ? null : staffMapper.selectAliveById(id);
        if (staff == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "员工不存在或已删除");
        }
        return staff;
    }

    /**
     * 校验门店存在且未删除
     *
     * <p>员工必属于某个门店（DDL 层 NOT NULL + 数据权限锚点），
     * 所以这里把「门店不存在」挡在插入之前，而不是等数据库抛
     * {@code Field 'store_id' doesn't have a default value} 这种看不懂的报错。
     */
    private void requireStore(Long storeId) {
        if (storeId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "所属门店不能为空");
        }
        Store store = storeMapper.selectById(storeId);
        if (store == null || (store.getDeleted() != null && store.getDeleted() == 1)) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "所选门店不存在或已删除");
        }
    }

    /**
     * 生成/校验工号
     *
     * <p>自动生成走 {@code E + 最大序号 + 1}，并靠 {@code uk_staff_no} 兜底：
     * 并发下两个请求可能算出同一个号，插入时其中一个会撞唯一键并拿到明确报错，
     * 而不是静默产生两条同号员工。
     */
    private String resolveStaffNo(String input, Long selfId) {
        if (input != null && !input.isBlank()) {
            String no = input.trim();
            Staff exist = staffMapper.selectByStaffNo(no);
            if (exist != null && !exist.getId().equals(selfId)) {
                throw new BizException(ErrorCode.OPERATION_FAILED, "工号已存在：" + no);
            }
            return no;
        }
        long next = staffMapper.maxStaffNoSeq() + 1;
        return NO_PREFIX + String.format("%0" + NO_WIDTH + "d", next);
    }

    private SysUser findAccountByStaffId(Long staffId) {
        List<SysUser> list = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStaffId, staffId)
                .eq(SysUser::getDeleted, 0)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private Map<Long, SysUser> loadAccounts(List<Long> staffIds) {
        Map<Long, SysUser> out = new LinkedHashMap<>();
        if (staffIds == null || staffIds.isEmpty()) {
            return out;
        }
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getStaffId, staffIds)
                .eq(SysUser::getDeleted, 0));
        for (SysUser u : users) {
            out.putIfAbsent(u.getStaffId(), u);
        }
        return out;
    }

    private Map<Long, String> loadStoreNames(Set<Long> ids) {
        Map<Long, String> out = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        for (Store s : storeMapper.selectBatchIds(ids)) {
            out.put(s.getId(), s.getStoreName());
        }
        return out;
    }

    private Map<String, Object> toVo(Staff s, Map<Long, String> storeNames, SysUser account, boolean withTip) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("staffNo", s.getStaffNo());
        m.put("staffName", s.getStaffName());
        m.put("phone", s.getPhone());
        m.put("storeId", s.getStoreId());
        m.put("storeName", s.getStoreId() == null ? null : storeNames.get(s.getStoreId()));
        m.put("position", s.getPosition());
        m.put("entryDate", s.getEntryDate());
        m.put("leaveDate", s.getLeaveDate());
        m.put("status", s.getStatus());
        m.put("statusText", Staff.statusText(s.getStatus()));
        m.put("createdAt", s.getCreatedAt());
        if (account == null) {
            m.put("account", null);
        } else {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", account.getId());
            a.put("username", account.getUsername());
            a.put("realName", account.getRealName());
            a.put("status", account.getStatus());
            a.put("statusText", account.getStatus() != null && account.getStatus() == 1 ? "启用" : "停用");
            m.put("account", a);
            if (withTip) {
                // 把「员工状态」与「账号状态」不一致的情况显式暴露出来，而不是让运营自己对比两张表
                if (s.isActive() && account.getStatus() != null && account.getStatus() == 0) {
                    m.put("accountWarning", "员工在职，但关联账号已停用，该员工将无法登录");
                } else if (!s.isActive() && account.getStatus() != null && account.getStatus() == 1) {
                    m.put("accountWarning", "员工已离职，但关联账号仍可登录，建议停用");
                }
            }
        }
        return m;
    }
}
