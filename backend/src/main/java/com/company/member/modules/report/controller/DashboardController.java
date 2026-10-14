package com.company.member.modules.report.controller;

import com.company.member.common.annotation.OpAudit;
import com.company.member.common.result.R;
import com.company.member.modules.member.service.LevelAdminService;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.ArrayList;
import com.company.member.modules.member.service.LevelEvaluationService;
import com.company.member.modules.report.service.DashboardCache;
import com.company.member.modules.report.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 管理端 · 看板与报表
 */
@Tag(name = "管理端-看板报表")
@RestController
@RequestMapping("/api/admin/report")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final DashboardCache dashboardCache;
    private final LevelEvaluationService levelEvaluationService;

    @Operation(summary = "看板缓存状态（TTL 与命中率，用于确认缓存真的在生效）")
    @GetMapping("/dashboard/cache-status")
    @PreAuthorize("@ps.has('report:view')")
    public R<Map<String, Object>> cacheStatus() {
        return R.ok(dashboardCache.status());
    }

    @Operation(summary = "手动清空看板缓存")
    @PostMapping("/dashboard/cache-evict")
    @PreAuthorize("@ps.has('report:view')")
    public R<Integer> cacheEvict() {
        return R.ok(dashboardCache.evictAll());
    }

    @Operation(summary = "经营总览（今日/昨日/本月/区间 + 负债 + 分布）")
    @GetMapping("/dashboard/overview")
    @PreAuthorize("@ps.has('report:view')")
    public R<Map<String, Object>> overview(@RequestParam(required = false) Long storeId,
                                           @RequestParam(defaultValue = "30") int days) {
        return R.ok(dashboardCache.get("overview:" + storeId + ":" + days, () -> dashboardService.overview(storeId, days)));
    }

    @Operation(summary = "会员增长与消费趋势（按日）")
    @GetMapping("/dashboard/member-trend")
    @PreAuthorize("@ps.has('report:view')")
    public R<List<Map<String, Object>>> memberTrend(@RequestParam(defaultValue = "30") int days) {
        return R.ok(dashboardCache.get("trend:member:" + days, () -> dashboardService.memberTrend(days)));
    }

    @Operation(summary = "消费趋势（按日）")
    @GetMapping("/dashboard/consume-trend")
    @PreAuthorize("@ps.has('report:view')")
    public R<List<Map<String, Object>>> consumeTrend(@RequestParam(defaultValue = "30") int days) {
        return R.ok(dashboardCache.get("trend:consume:" + days, () -> dashboardService.consumeTrend(days)));
    }

    @Operation(summary = "会员等级分布")
    @GetMapping("/dashboard/level-distribution")
    @PreAuthorize("@ps.has('report:view')")
    public R<List<Map<String, Object>>> levelDistribution(@RequestParam(required = false) Long storeId) {
        return R.ok(dashboardCache.get("levelDist:" + storeId, () -> dashboardService.levelDistribution(storeId)));
    }

    @Operation(summary = "留存分析（入会月份队列）")
    @GetMapping("/dashboard/retention")
    @PreAuthorize("@ps.has('report:view')")
    public R<List<Map<String, Object>>> retention(@RequestParam(defaultValue = "6") int months) {
        return R.ok(dashboardCache.get("retention:" + months, () -> dashboardService.retention(months)));
    }

    @Operation(summary = "RFM 客户分层")
    @GetMapping("/dashboard/rfm")
    @PreAuthorize("@ps.has('report:view')")
    public R<Map<String, Object>> rfm() {
        return R.ok(dashboardCache.get("rfm", dashboardService::rfm));
    }

    @Operation(summary = "沉睡会员清单（唤醒营销用）")
    @GetMapping("/dashboard/sleeping-members")
    @PreAuthorize("@ps.has('report:view')")
    public R<List<Map<String, Object>>> sleepingMembers(@RequestParam(defaultValue = "60") int days,
                                                         @RequestParam(defaultValue = "200") int limit) {
        return R.ok(dashboardService.sleepingMembers(days, limit));
    }

    @Operation(summary = "储值与积分负债表（财务口径）")
    @GetMapping("/report/liability")
    @PreAuthorize("@ps.hasAny('report:finance','report:view')")
    public R<Map<String, Object>> liability(@RequestParam(required = false) Long storeId) {
        return R.ok(dashboardCache.get("liability:" + storeId, () -> dashboardService.liability(storeId)));
    }

    @Operation(summary = "门店业绩排行")
    @GetMapping("/report/store-ranking")
    @PreAuthorize("@ps.has('report:view')")
    public R<List<Map<String, Object>>> storeRanking(@RequestParam(required = false) LocalDate startDate,
                                                      @RequestParam(required = false) LocalDate endDate,
                                                      @RequestParam(defaultValue = "10") int limit) {
        return R.ok(dashboardService.storeRanking(dateOr(startDate, 30), dateOr(endDate, 0), limit));
    }

    @Operation(summary = "员工业绩排行")
    @GetMapping("/report/staff-ranking")
    @PreAuthorize("@ps.hasAny('report:view','staff:perf')")
    public R<List<Map<String, Object>>> staffRanking(@RequestParam(required = false) LocalDate startDate,
                                                     @RequestParam(required = false) LocalDate endDate,
                                                     @RequestParam(defaultValue = "10") int limit) {
        return R.ok(dashboardService.staffRanking(dateOr(startDate, 30), dateOr(endDate, 0), limit));
    }

    @Operation(summary = "商品销量排行")
    @GetMapping("/report/product-ranking")
    @PreAuthorize("@ps.has('report:view')")
    public R<List<Map<String, Object>>> productRanking(@RequestParam(required = false) LocalDate startDate,
                                                       @RequestParam(required = false) LocalDate endDate,
                                                       @RequestParam(defaultValue = "10") int limit) {
        return R.ok(dashboardService.productRanking(dateOr(startDate, 30), dateOr(endDate, 0), limit));
    }

    @Operation(summary = "门店下拉选项")
    @GetMapping("/report/store-options")
    @PreAuthorize("@ps.has('report:view')")
    public R<List<Map<String, Object>>> storeOptions() {
        return R.ok(dashboardService.storeOptions());
    }

    /* ==================== 等级评定 ==================== */

    @Operation(summary = "等级评定规则解读（管理端展示条件）")
    @GetMapping("/levels/{levelId}/rule-inspect")
    @PreAuthorize("@ps.has('level:list')")
    public R<List<Map<String, Object>>> inspectRule(@PathVariable Long levelId) {
        return R.ok(levelEvaluationService.inspectRule(levelId));
    }

    @Operation(summary = "等级模拟测算（不改数据，预览目标等级）")
    @GetMapping("/levels/simulate/{memberId}")
    @PreAuthorize("@ps.has('level:evaluate')")
    public R<Map<String, Object>> simulate(@PathVariable Long memberId) {
        return R.ok(levelEvaluationService.simulate(memberId));
    }

    @Operation(summary = "单会员等级评定")
    @PostMapping("/levels/evaluate/{memberId}")
    @PreAuthorize("@ps.has('level:evaluate')")
    public R<Map<String, Object>> evaluate(@PathVariable Long memberId) {
        return R.ok(levelEvaluationService.evaluate(memberId));
    }

    @Operation(summary = "全量等级重算（异步任务，建议低峰执行）")
    @PostMapping("/levels/recalculate")
    @PreAuthorize("@ps.has('level:evaluate')")
    @OpAudit(opType = "UPDATE", desc = "等级批量重算")
    public R<Map<String, Object>> recalculate(@RequestParam(defaultValue = "200") int batchSize) {
        return R.ok(levelEvaluationService.evaluateAll(batchSize));
    }

    /* ==================== 等级定义与规则（②） ==================== */

    @Operation(summary = "等级列表（含权益与门槛）")
    @GetMapping("/levels")
    @PreAuthorize("@ps.has('level:list')")
    public R<List<Map<String, Object>>> levels() {
        return R.ok(levelAdminService.list());
    }

    @Data
    public static class LevelSaveCmd {
        private Long id;
        private String levelName;
        private String levelCode;
        private Integer levelValue;
        private java.math.BigDecimal discountRate;
        private java.math.BigDecimal pointsRate;
        private java.math.BigDecimal cardDiscountRate;
        private Long upgradeGiftPoints;
        private Long birthdayCouponTplId;
        private Integer isDefault;
        private Integer validMonths;
        private Integer status;
        private Integer sortNo;
        private String remark;
    }

    @Operation(summary = "新增等级")
    @PostMapping("/levels")
    @PreAuthorize("@ps.has('level:add')")
    public R<Map<String, Object>> createLevel(@RequestBody LevelSaveCmd cmd) {
        return R.ok(levelAdminService.save(null, cmd));
    }

    @Operation(summary = "修改等级")
    @PutMapping("/levels/{id}")
    @PreAuthorize("@ps.has('level:edit')")
    public R<Map<String, Object>> updateLevel(@PathVariable Long id, @RequestBody LevelSaveCmd cmd) {
        return R.ok(levelAdminService.save(id, cmd));
    }

    @Operation(summary = "删除等级（有会员占用则拒绝）")
    @DeleteMapping("/levels/{id}")
    @PreAuthorize("@ps.has('level:edit')")
    public R<Void> removeLevel(@PathVariable Long id) {
        levelAdminService.remove(id);
        return R.ok();
    }

    @Data
    public static class LevelRuleSaveCmd {
        @Schema(description = "条件逻辑 AND / OR")
        private String logicType = "AND";
        @Schema(description = "条件项列表 [{field,op,value}]")
        private List<Map<String, Object>> items = new ArrayList<>();
        @Schema(description = "保级条件（可选）")
        private Map<String, Object> keepRule;
        @Schema(description = "等级有效期（月），0=永久")
        private Integer validMonths;
    }

    @Operation(summary = "读取等级评定规则（结构化）")
    @GetMapping("/levels/{id}/rule")
    @PreAuthorize("@ps.has('level:list')")
    public R<Map<String, Object>> getRule(@PathVariable Long id) {
        return R.ok(levelAdminService.getRule(id));
    }

    @Operation(summary = "保存等级评定规则")
    @PutMapping("/levels/{id}/rule")
    @PreAuthorize("@ps.has('level:edit')")
    @OpAudit(opType = "UPDATE", desc = "等级规则修改")
    public R<Map<String, Object>> saveRule(@PathVariable Long id, @RequestBody LevelRuleSaveCmd cmd) {
        return R.ok(levelAdminService.saveRule(id, cmd));
    }

    @Operation(summary = "规则字段白名单（前端构造器用）")
    @GetMapping("/levels/rule-fields")
    @PreAuthorize("@ps.has('level:list')")
    public R<List<Map<String, Object>>> ruleFields() {
        return R.ok(levelAdminService.ruleFields());
    }

    private final com.company.member.modules.member.service.LevelAdminService levelAdminService;

    private static LocalDate dateOr(LocalDate d, int daysAgo) {
        return d != null ? d : LocalDate.now().minusDays(daysAgo);
    }
}
