package com.company.member.modules.store.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.modules.staff.entity.Staff;
import com.company.member.modules.staff.mapper.StaffMapper;
import com.company.member.modules.store.dto.StoreSaveCmd;
import com.company.member.modules.store.entity.Store;
import com.company.member.modules.store.mapper.StoreMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * 门店管理
 *
 * <p>门店是整个系统的<b>数据权限锚点</b>：用户有默认门店、员工归属门店、订单产生门店、
 * 会员归属门店。因此门店的删除必须极其保守 —— 只要有任何一方还在引用它，
 * 就只能「停业」不能删，否则那些记录的归属会集体悬空。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreMapper storeMapper;
    private final StaffMapper staffMapper;

    public List<Map<String, Object>> list(String keyword, Integer status) {
        List<Store> stores = storeMapper.selectList(new LambdaQueryWrapper<Store>()
                .eq(Store::getDeleted, 0)
                .eq(status != null, Store::getStatus, status)
                .and(keyword != null && !keyword.isBlank(),
                        w -> w.like(Store::getStoreName, keyword)
                                .or().like(Store::getStoreCode, keyword)
                                .or().like(Store::getCity, keyword))
                .orderByAsc(Store::getSortNo)
                .orderByAsc(Store::getId));
        // 一次性把四类引用计数取回来按 store_id 回填。
        // 原来每个门店要发 5 条 COUNT（在职员工/全部员工/订单/会员/账号），
        // 20 家门店就是 100 条 SQL —— 典型的 N+1。
        Map<Long, Map<String, Object>> refs = loadRefCounts(stores.stream().map(Store::getId).toList());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Store s : stores) {
            out.add(toVo(s, refs.get(s.getId())));
        }
        return out;
    }

    /** 批量取引用计数：store_id → {activeStaff, allStaff, orders, members, users} */
    private Map<Long, Map<String, Object>> loadRefCounts(List<Long> storeIds) {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        if (storeIds == null || storeIds.isEmpty()) {
            return out;
        }
        for (Map<String, Object> row : storeMapper.countReferences(storeIds)) {
            Object id = row.get("store_id");
            if (id != null) {
                out.put(((Number) id).longValue(), row);
            }
        }
        return out;
    }

    private static int num(Map<String, Object> refs, String key) {
        if (refs == null) {
            return 0;
        }
        Object v = refs.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    public Map<String, Object> detail(Long id) {
        Store store = requireStore(id);
        return toVo(store, loadRefCounts(List.of(id)).get(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(StoreSaveCmd cmd) {
        requireManager(cmd.getManagerId());
        Store store = new Store();
        store.setStoreCode(resolveCode(cmd.getStoreCode(), null));
        apply(store, cmd);
        store.setStatus(cmd.getStatus() == null ? 1 : cmd.getStatus());
        store.setSortNo(cmd.getSortNo() == null ? 0 : cmd.getSortNo());
        store.setTenantId(0L);
        store.setDeleted(0);
        try {
            storeMapper.insert(store);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "门店编码已存在：" + store.getStoreCode());
        }
        log.info("[门店] 新增 id={}, 编码={}, 名称={}", store.getId(), store.getStoreCode(), store.getStoreName());
        return detail(store.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(Long id, StoreSaveCmd cmd) {
        Store store = requireStore(id);
        requireManager(cmd.getManagerId());
        if (cmd.getStoreCode() != null && !cmd.getStoreCode().isBlank()
                && !cmd.getStoreCode().equals(store.getStoreCode())) {
            store.setStoreCode(resolveCode(cmd.getStoreCode(), id));
        }
        apply(store, cmd);
        if (cmd.getStatus() != null) {
            store.setStatus(cmd.getStatus());
        }
        if (cmd.getSortNo() != null) {
            store.setSortNo(cmd.getSortNo());
        }
        try {
            storeMapper.updateById(store);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "门店编码已存在：" + store.getStoreCode());
        }
        log.info("[门店] 更新 id={}, 名称={}", id, store.getStoreName());
        return detail(id);
    }

    /** 停业/营业：停业后该门店不再出现在收银台的可选门店里，但历史数据完整保留 */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status) {
        Store store = requireStore(id);
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "状态只能为 0 停业 或 1 营业");
        }
        store.setStatus(status);
        storeMapper.updateById(store);
        log.info("[门店] 状态变更 id={}, status={}", id, status);
    }

    /**
     * 删除门店
     *
     * <p><b>门店几乎永远不该被删除</b>：它是数据权限锚点，被订单、会员、员工、用户同时引用。
     * 删掉之后那些记录的归属会集体悬空，报表里会出现一个「看不出来是谁」的门店。
     * 因此这里做<b>引用盘点</b>，只要有任何一方还在引用就拒绝，并明确告诉运营该去处理什么：
     *
     * <pre>
     *   在职员工   → 先调岗或办离职     （最硬的约束：门店没人了才谈得上撤销）
     *   离职员工   → 档案仍引用该店，删店会让他们悬空
     *   历史订单   → 业绩/门店报表的历史口径靠它
     *   归属会员   → 会员的归属门店
     *   默认门店账号 → 数据权限会落在空门店上
     * </pre>
     *
     * <p>结论：正常业务里请用「停业」——停业后门店不再出现在收银台可选列表，
     * 但历史数据完整保留。只有刚建错、还没被任何数据引用的门店才删得掉。
     */
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        Store store = requireStore(id);
        List<String> blockers = new ArrayList<>();
        int activeStaff = storeMapper.countActiveStaff(id);
        if (activeStaff > 0) {
            blockers.add(activeStaff + " 名在职员工");
        }
        int allStaff = storeMapper.countAllStaff(id);
        if (allStaff > activeStaff) {
            blockers.add((allStaff - activeStaff) + " 名离职员工的档案");
        }
        int orders = storeMapper.countOrders(id);
        if (orders > 0) {
            blockers.add(orders + " 笔历史订单");
        }
        int members = storeMapper.countMembers(id);
        if (members > 0) {
            blockers.add(members + " 名归属会员");
        }
        int users = storeMapper.countUsers(id);
        if (users > 0) {
            blockers.add(users + " 个默认门店为该店的账号");
        }
        if (!blockers.isEmpty()) {
            throw new BizException(ErrorCode.OPERATION_FAILED,
                    "门店「" + store.getStoreName() + "」仍被 " + String.join("、", blockers)
                            + " 引用，不能删除。门店是数据权限与历史业绩的锚点，请改用「停业」——"
                            + "停业后不再出现在收银台可选门店，历史数据完整保留。");
        }
        storeMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Store>()
                .eq(Store::getId, id)
                .set(Store::getDeleted, 1));
        log.info("[门店] 删除 id={}, 名称={}（无任何引用）", id, store.getStoreName());
    }

    /* ==================== 内部 ==================== */

    private Store requireStore(Long id) {
        Store store = id == null ? null : storeMapper.selectById(id);
        if (store == null || (store.getDeleted() != null && store.getDeleted() == 1)) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "门店不存在或已删除");
        }
        return store;
    }

    /** 店长必须是存在的在职员工（店长离职后也允许保留历史引用，只是列表会显示其姓名） */
    private void requireManager(Long managerId) {
        if (managerId == null) {
            return;
        }
        Staff manager = staffMapper.selectAliveById(managerId);
        if (manager == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "所选店长不存在或已删除");
        }
    }

    private void apply(Store store, StoreSaveCmd cmd) {
        store.setStoreName(cmd.getStoreName());
        store.setProvince(cmd.getProvince());
        store.setCity(cmd.getCity());
        store.setDistrict(cmd.getDistrict());
        store.setAddress(cmd.getAddress());
        store.setLongitude(cmd.getLongitude());
        store.setLatitude(cmd.getLatitude());
        store.setContactName(cmd.getContactName());
        store.setContactPhone(cmd.getContactPhone());
        store.setBusinessHours(cmd.getBusinessHours());
        store.setManagerId(cmd.getManagerId());
    }

    /** 门店编码：留空则用 S + 序号；指定时必须唯一 */
    private String resolveCode(String input, Long selfId) {
        if (input != null && !input.isBlank()) {
            String code = input.trim();
            Store exist = storeMapper.selectOne(new LambdaQueryWrapper<Store>()
                    .eq(Store::getStoreCode, code).last("LIMIT 1"));
            if (exist != null && !exist.getId().equals(selfId)) {
                throw new BizException(ErrorCode.OPERATION_FAILED, "门店编码已存在：" + code);
            }
            return code;
        }
        Long count = storeMapper.selectCount(new LambdaQueryWrapper<Store>().eq(Store::getDeleted, 0));
        long next = (count == null ? 0 : count) + 1;
        String code = "S" + String.format("%03d", next);
        // 序号可能与历史编码冲突（删过门店），向后探测到空位
        while (storeMapper.selectOne(new LambdaQueryWrapper<Store>()
                .eq(Store::getStoreCode, code).last("LIMIT 1")) != null) {
            next++;
            code = "S" + String.format("%03d", next);
        }
        return code;
    }

    private Map<String, Object> toVo(Store s, Map<String, Object> refs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("storeCode", s.getStoreCode());
        m.put("storeName", s.getStoreName());
        m.put("province", s.getProvince());
        m.put("city", s.getCity());
        m.put("district", s.getDistrict());
        m.put("address", s.getAddress());
        m.put("longitude", s.getLongitude());
        m.put("latitude", s.getLatitude());
        m.put("contactName", s.getContactName());
        m.put("contactPhone", s.getContactPhone());
        m.put("businessHours", s.getBusinessHours());
        m.put("managerId", s.getManagerId());
        if (s.getManagerId() != null) {
            Staff manager = staffMapper.selectAliveById(s.getManagerId());
            m.put("managerName", manager == null ? null : manager.getStaffName());
        } else {
            m.put("managerName", null);
        }
        m.put("status", s.getStatus());
        m.put("statusText", s.getStatus() != null && s.getStatus() == 1 ? "营业中" : "已停业");
        m.put("sortNo", s.getSortNo());

        int activeStaff = num(refs, "active_staff");
        int allStaff = num(refs, "all_staff");
        int orders = num(refs, "orders");
        int members = num(refs, "members");
        int users = num(refs, "users");
        m.put("staffCount", activeStaff);
        // 让前端能解释「为什么这个门店删不掉」，而不是点删除才报错
        m.put("historyStaffCount", Math.max(0, allStaff - activeStaff));
        m.put("orderCount", orders);
        m.put("memberCount", members);
        m.put("userCount", users);
        boolean deletable = allStaff == 0 && orders == 0 && members == 0 && users == 0;
        m.put("deletable", deletable);
        if (!deletable) {
            m.put("deleteBlockReason", "仍被员工/订单/会员/账号引用，请改用停业");
        }
        m.put("createdAt", s.getCreatedAt());
        return m;
    }
}
