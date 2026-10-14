package com.company.member.modules.account.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.member.common.annotation.OpAudit;
import com.company.member.common.annotation.Idempotent;
import com.company.member.common.result.R;
import com.company.member.modules.account.dto.RechargeCmd;
import com.company.member.modules.account.dto.RechargeResult;
import com.company.member.modules.account.entity.RechargePlan;
import com.company.member.modules.account.mapper.RechargePlanMapper;
import com.company.member.modules.account.service.RechargeService;
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

import java.util.List;

/**
 * 管理端 · 充值
 */
@Tag(name = "管理端-充值")
@RestController
@RequestMapping("/api/admin/recharge")
@RequiredArgsConstructor
public class RechargeController {

    private final RechargeService rechargeService;
    private final RechargePlanMapper planMapper;
    private final SecurityService securityService;

    @Operation(summary = "充值（现金/线下扫码记账）")
    @PostMapping
    @PreAuthorize("@ps.has('account:recharge')")
    @Idempotent(prefix = "recharge", desc = "该笔充值正在处理中，请勿重复提交")
    @OpAudit(opType = "RECHARGE", desc = "充值办理")
    public R<RechargeResult> recharge(@Valid @RequestBody RechargeCmd cmd) {
        return R.ok(rechargeService.recharge(cmd, securityService.currentUserId(), null, null));
    }

    @Operation(summary = "充值撤销（扣回本金/赠送/赠分，作废未使用赠券）")
    @PostMapping("/{orderNo}/revoke")
    @PreAuthorize("@ps.has('account:revoke')")
    @Idempotent(prefix = "recharge-revoke", desc = "该充值单正在撤销中")
    @OpAudit(opType = "REFUND", desc = "充值撤销")
    public R<Void> revoke(@PathVariable String orderNo,
                          @RequestParam(required = false) String reason) {
        rechargeService.revoke(orderNo, securityService.currentUserId(), reason);
        return R.ok();
    }

    /* ==================== 充值方案 ==================== */

    @Operation(summary = "充值方案列表")
    @GetMapping("/plans")
    @PreAuthorize("@ps.hasAny('account:recharge','account:plan')")
    public R<List<RechargePlan>> plans(@RequestParam(defaultValue = "true") boolean onlyEnabled) {
        LambdaQueryWrapper<RechargePlan> w = new LambdaQueryWrapper<RechargePlan>()
                .eq(RechargePlan::getDeleted, 0)
                .eq(onlyEnabled, RechargePlan::getStatus, 1)
                .orderByAsc(RechargePlan::getSortNo);
        return R.ok(planMapper.selectList(w));
    }

    @Operation(summary = "新增充值方案")
    @PostMapping("/plans")
    @PreAuthorize("@ps.has('account:plan')")
    public R<RechargePlan> createPlan(@RequestBody RechargePlan plan) {
        plan.setId(null);
        plan.setDeleted(0);
        if (plan.getStatus() == null) {
            plan.setStatus(1);
        }
        planMapper.insert(plan);
        return R.ok(plan);
    }

    @Operation(summary = "修改充值方案")
    @PutMapping("/plans/{id}")
    @PreAuthorize("@ps.has('account:plan')")
    public R<RechargePlan> updatePlan(@PathVariable Long id, @RequestBody RechargePlan plan) {
        plan.setId(id);
        planMapper.updateById(plan);
        return R.ok(planMapper.selectById(id));
    }

    @Operation(summary = "删除充值方案（逻辑删除）")
    @DeleteMapping("/plans/{id}")
    @PreAuthorize("@ps.has('account:plan')")
    public R<Void> deletePlan(@PathVariable Long id) {
        RechargePlan plan = new RechargePlan();
        plan.setId(id);
        plan.setDeleted(1);
        planMapper.updateById(plan);
        return R.ok();
    }
}
