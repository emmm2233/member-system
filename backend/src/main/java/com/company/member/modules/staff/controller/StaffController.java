package com.company.member.modules.staff.controller;

import com.company.member.common.annotation.OpAudit;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.result.PageResult;
import com.company.member.common.result.R;
import com.company.member.modules.staff.dto.StaffQuery;
import com.company.member.modules.staff.dto.StaffSaveCmd;
import com.company.member.modules.staff.entity.Staff;
import com.company.member.modules.staff.service.StaffPerformanceService;
import com.company.member.modules.staff.service.StaffService;
import com.company.member.security.SecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端 · 员工管理
 *
 * <p>接口按「员工生命周期」和「账号关联」两组划分，刻意<b>不提供</b>
 * 「停用关联账号」这种联动接口 —— 停账号属于系统管理的职责，
 * 员工侧只给出提示（见 {@link StaffService#leave}）。
 */
@Tag(name = "管理端-员工管理")
@RestController
@RequestMapping("/api/admin/staff")
@RequiredArgsConstructor
public class StaffController {

    private final StaffService staffService;
    private final StaffPerformanceService performanceService;
    private final SecurityService securityService;

    /* ==================== 档案 ==================== */

    @Operation(summary = "员工分页（keyword 匹配工号/姓名/手机）")
    @GetMapping
    @PreAuthorize("@ps.has('staff:list')")
    public R<PageResult<Map<String, Object>>> page(StaffQuery query) {
        return R.ok(staffService.page(query));
    }

    @Operation(summary = "员工详情（附关联账号、账号状态告警、业绩笔数）")
    @GetMapping("/{id}")
    @PreAuthorize("@ps.has('staff:list')")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(staffService.detail(id));
    }

    @Operation(summary = "新增员工（工号留空则自动生成 E001/E002…）")
    @PostMapping
    @PreAuthorize("@ps.has('staff:add')")
    public R<Map<String, Object>> create(@Valid @RequestBody StaffSaveCmd cmd) {
        return R.ok(staffService.create(cmd));
    }

    @Operation(summary = "编辑员工")
    @PutMapping("/{id}")
    @PreAuthorize("@ps.has('staff:edit')")
    public R<Map<String, Object>> update(@PathVariable Long id, @Valid @RequestBody StaffSaveCmd cmd) {
        return R.ok(staffService.update(id, cmd));
    }

    @Operation(summary = "删除员工（有历史业绩者拒绝，请改用离职）")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ps.has('staff:edit')")
    public R<Void> remove(@PathVariable Long id) {
        staffService.remove(id);
        return R.ok();
    }

    /* ==================== 在职状态 ==================== */

    @Operation(summary = "办理离职（只改员工状态，不动登录账号，返回账号处理建议）")
    @PostMapping("/{id}/leave")
    @PreAuthorize("@ps.has('staff:leave')")
    @OpAudit(opType = "UPDATE", desc = "员工离职")
    public R<Map<String, Object>> leave(@PathVariable Long id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        LocalDate leaveDate = null;
        String reason = null;
        if (body != null) {
            Object d = body.get("leaveDate");
            if (d != null && !String.valueOf(d).isBlank()) {
                leaveDate = LocalDate.parse(String.valueOf(d));
            }
            reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        }
        return R.ok(staffService.leave(id, leaveDate, reason));
    }

    @Operation(summary = "复职")
    @PostMapping("/{id}/reinstate")
    @PreAuthorize("@ps.has('staff:leave')")
    @OpAudit(opType = "UPDATE", desc = "员工复职")
    public R<Map<String, Object>> reinstate(@PathVariable Long id) {
        return R.ok(staffService.reinstate(id));
    }

    /* ==================== 账号关联 ==================== */

    @Operation(summary = "关联登录账号")
    @PostMapping("/{id}/account")
    @PreAuthorize("@ps.has('staff:account')")
    @OpAudit(opType = "UPDATE", desc = "员工关联账号")
    public R<Map<String, Object>> bindAccount(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object uid = body.get("userId");
        if (uid == null || String.valueOf(uid).isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请选择要关联的登录账号");
        }
        return R.ok(staffService.bindAccount(id, Long.valueOf(String.valueOf(uid))));
    }

    @Operation(summary = "解绑登录账号（账号本身不受影响）")
    @DeleteMapping("/{id}/account")
    @PreAuthorize("@ps.has('staff:account')")
    @OpAudit(opType = "UPDATE", desc = "员工解绑账号")
    public R<Void> unbindAccount(@PathVariable Long id) {
        staffService.unbindAccount(id);
        return R.ok();
    }

    @Operation(summary = "可关联的登录账号（未绑定任何员工的账号）")
    @GetMapping("/account-candidates")
    @PreAuthorize("@ps.has('staff:account')")
    public R<List<Map<String, Object>>> accountCandidates() {
        return R.ok(staffService.accountCandidates());
    }

    /* ==================== 下拉与字典 ==================== */

    @Operation(summary = "在职员工下拉（可按门店过滤；收银台选业绩归属用）")
    @GetMapping("/options")
    @PreAuthorize("@ps.hasAny('staff:list','order:add')")
    public R<List<Map<String, Object>>> options(@RequestParam(required = false) Long storeId) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Staff s : staffService.activeByStore(storeId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("staffNo", s.getStaffNo());
            m.put("staffName", s.getStaffName());
            m.put("storeId", s.getStoreId());
            m.put("position", s.getPosition());
            out.add(m);
        }
        return R.ok(out);
    }

    @Operation(summary = "岗位候选")
    @GetMapping("/positions")
    @PreAuthorize("@ps.has('staff:list')")
    public R<List<String>> positions() {
        return R.ok(staffService.positions());
    }

    @Operation(summary = "门店下拉（含在职人数）")
    @GetMapping("/store-options")
    @PreAuthorize("@ps.hasAny('staff:list','store:list')")
    public R<List<Map<String, Object>>> storeOptions() {
        return R.ok(staffService.storeOptions());
    }

    @Operation(summary = "当前登录账号绑定的员工（收银台自动带出业绩归属）")
    @GetMapping("/me")
    public R<Map<String, Object>> me() {
        Staff staff = staffService.findByAccount(securityService.currentUserId());
        if (staff == null) {
            return R.ok(null);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", staff.getId());
        m.put("staffNo", staff.getStaffNo());
        m.put("staffName", staff.getStaffName());
        m.put("storeId", staff.getStoreId());
        m.put("position", staff.getPosition());
        return R.ok(m);
    }

    /* ==================== 业绩 ==================== */

    @Operation(summary = "员工业绩排行（按 m_order.staff_id 归属，可筛门店与日期）")
    @GetMapping("/performance")
    @PreAuthorize("@ps.has('staff:perf')")
    public R<List<Map<String, Object>>> performance(@RequestParam(required = false) Long storeId,
                                                    @RequestParam(required = false) String startDate,
                                                    @RequestParam(required = false) String endDate,
                                                    @RequestParam(defaultValue = "20") int limit) {
        LocalDate end = endDate == null || endDate.isBlank() ? LocalDate.now() : LocalDate.parse(endDate);
        LocalDate start = startDate == null || startDate.isBlank() ? end.withDayOfMonth(1) : LocalDate.parse(startDate);
        return R.ok(performanceService.ranking(storeId, start, end, limit));
    }

    @Operation(summary = "员工业绩汇总（排行页顶部卡片）")
    @GetMapping("/performance/summary")
    @PreAuthorize("@ps.has('staff:perf')")
    public R<Map<String, Object>> performanceSummary(@RequestParam(required = false) Long storeId,
                                                     @RequestParam(required = false) String startDate,
                                                     @RequestParam(required = false) String endDate) {
        LocalDate end = endDate == null || endDate.isBlank() ? LocalDate.now() : LocalDate.parse(endDate);
        LocalDate start = startDate == null || startDate.isBlank() ? end.withDayOfMonth(1) : LocalDate.parse(startDate);
        return R.ok(performanceService.summary(storeId, start, end));
    }

    @Operation(summary = "单个员工业绩明细（含日趋势）")
    @GetMapping("/{id}/performance")
    @PreAuthorize("@ps.has('staff:perf')")
    public R<Map<String, Object>> staffPerformance(@PathVariable Long id,
                                                   @RequestParam(required = false) String startDate,
                                                   @RequestParam(required = false) String endDate) {
        LocalDate end = endDate == null || endDate.isBlank() ? LocalDate.now() : LocalDate.parse(endDate);
        LocalDate start = startDate == null || startDate.isBlank() ? end.withDayOfMonth(1) : LocalDate.parse(startDate);
        return R.ok(performanceService.detail(id, start, end));
    }
}
