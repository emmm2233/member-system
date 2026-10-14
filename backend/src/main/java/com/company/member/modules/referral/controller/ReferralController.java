package com.company.member.modules.referral.controller;

import com.company.member.common.annotation.OpAudit;
import com.company.member.common.result.PageResult;
import com.company.member.common.result.R;
import com.company.member.modules.referral.service.ReferralConfigService;
import com.company.member.modules.referral.service.ReferralService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端 · 推荐有礼
 */
@Tag(name = "管理端-推荐有礼")
@RestController
@RequestMapping("/api/referral")
@RequiredArgsConstructor
public class ReferralController {

    private final ReferralService referralService;
    private final ReferralConfigService referralConfigService;

    @Operation(summary = "推荐关系列表")
    @GetMapping("/relations")
    @PreAuthorize("@ps.has('referral:list')")
    public R<PageResult<Map<String, Object>>> relations(@RequestParam(required = false) Long referrerId,
                                                        @RequestParam(required = false) Integer status,
                                                        @RequestParam(defaultValue = "1") int pageNum,
                                                        @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(referralService.pageReferrals(referrerId, status, pageNum, pageSize));
    }

    @Operation(summary = "推荐奖励发放记录")
    @GetMapping("/rewards")
    @PreAuthorize("@ps.has('referral:list')")
    public R<PageResult<Map<String, Object>>> rewards(@RequestParam(required = false) Long memberId,
                                                      @RequestParam(required = false) Integer status,
                                                      @RequestParam(required = false) Integer rewardTarget,
                                                      @RequestParam(defaultValue = "1") int pageNum,
                                                      @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(referralService.pageRewards(memberId, status, rewardTarget, pageNum, pageSize));
    }

    @Operation(summary = "推荐活动概览（绑定/结算/转化率/奖励汇总）")
    @GetMapping("/stats")
    @PreAuthorize("@ps.has('referral:list')")
    public R<Map<String, Object>> stats() {
        return R.ok(referralService.stats());
    }

    @Operation(summary = "推荐规则配置项（含取值范围与说明）")
    @GetMapping("/config")
    @PreAuthorize("@ps.has('referral:config')")
    public R<List<Map<String, Object>>> config() {
        return R.ok(referralConfigService.describe());
    }

    @Operation(summary = "保存推荐规则")
    @PutMapping("/config")
    @PreAuthorize("@ps.has('referral:config')")
    @OpAudit(opType = "UPDATE", desc = "推荐规则修改")
    public R<Void> saveConfig(@RequestBody Map<String, String> values) {
        referralConfigService.update(values);
        return R.ok();
    }

    @Operation(summary = "人工结算/补发奖励")
    @PostMapping("/relations/{id}/settle")
    @PreAuthorize("@ps.has('referral:settle')")
    @OpAudit(opType = "AUDIT", desc = "推荐人工结算")
    public R<Void> manualSettle(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        referralService.manualSettle(id, body == null ? null : body.get("reason"));
        return R.ok();
    }

    @Operation(summary = "作废推荐关系（风控）")
    @PostMapping("/relations/{id}/invalidate")
    @PreAuthorize("@ps.has('referral:settle')")
    @OpAudit(opType = "UPDATE", desc = "推荐关系作废")
    public R<Void> invalidate(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        referralService.invalidate(id, body == null ? null : body.get("reason"));
        return R.ok();
    }

    @Operation(summary = "手动触发兜底结算（扫已绑定未结算）")
    @PostMapping("/tasks/settle-scan")
    @PreAuthorize("@ps.has('referral:settle')")
    public R<Integer> settleScan(@RequestParam(defaultValue = "500") int limit) {
        return R.ok(referralService.settleByScan(limit));
    }

    @Operation(summary = "手动触发奖励重试")
    @PostMapping("/tasks/retry-rewards")
    @PreAuthorize("@ps.has('referral:settle')")
    public R<Integer> retryRewards(@RequestParam(defaultValue = "200") int limit) {
        return R.ok(referralService.retryPendingRewards(limit));
    }
}
