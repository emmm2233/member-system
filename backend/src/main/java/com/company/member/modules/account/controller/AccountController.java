package com.company.member.modules.account.controller;

import com.company.member.common.annotation.Idempotent;
import com.company.member.common.aspect.IdempotentAspect;
import com.company.member.common.annotation.OpAudit;
import com.company.member.common.result.PageResult;
import com.company.member.common.result.R;
import com.company.member.modules.account.dto.AccountAdjustCmd;
import com.company.member.modules.account.entity.MemberAccount;
import com.company.member.modules.account.entity.PointBatch;
import com.company.member.modules.account.service.AccountQueryService;
import com.company.member.modules.account.service.AccountService;
import com.company.member.modules.account.vo.AccountChangeResult;
import com.company.member.security.SecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 管理端 · 资产账务
 */
@Tag(name = "管理端-资产账务")
@RestController
@RequestMapping("/api/admin/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final AccountQueryService queryService;
    private final SecurityService securityService;

    @Operation(summary = "会员资产账户详情")
    @GetMapping("/{memberId}")
    @PreAuthorize("@ps.has('account:query')")
    public R<MemberAccount> account(@PathVariable Long memberId) {
        return R.ok(accountService.getAccount(memberId));
    }

    @Operation(summary = "余额流水分页")
    @GetMapping("/{memberId}/balance-flows")
    @PreAuthorize("@ps.has('account:query')")
    public R<PageResult<Map<String, Object>>> balanceFlows(@PathVariable Long memberId,
                                                           @RequestParam(required = false) Integer accountType,
                                                           @RequestParam(required = false) Integer bizType,
                                                           @RequestParam(required = false) LocalDate startDate,
                                                           @RequestParam(required = false) LocalDate endDate,
                                                           @RequestParam(defaultValue = "1") int pageNum,
                                                           @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(queryService.balanceFlows(memberId, accountType, bizType, startDate, endDate, pageNum, pageSize));
    }

    @Operation(summary = "积分流水分页")
    @GetMapping("/{memberId}/point-flows")
    @PreAuthorize("@ps.has('account:query')")
    public R<PageResult<Map<String, Object>>> pointFlows(@PathVariable Long memberId,
                                                         @RequestParam(required = false) Integer bizType,
                                                         @RequestParam(required = false) LocalDate startDate,
                                                         @RequestParam(required = false) LocalDate endDate,
                                                         @RequestParam(defaultValue = "1") int pageNum,
                                                         @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(queryService.pointFlows(memberId, bizType, startDate, endDate, pageNum, pageSize));
    }

    @Operation(summary = "成长值流水分页")
    @GetMapping("/{memberId}/growth-flows")
    @PreAuthorize("@ps.has('account:query')")
    public R<PageResult<com.company.member.modules.account.entity.GrowthFlow>> growthFlows(
            @PathVariable Long memberId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(queryService.growthFlows(memberId, pageNum, pageSize));
    }

    @Operation(summary = "积分批次（含到期时间）")
    @GetMapping("/{memberId}/point-batches")
    @PreAuthorize("@ps.has('account:query')")
    public R<List<PointBatch>> pointBatches(@PathVariable Long memberId,
                                            @RequestParam(defaultValue = "false") boolean onlyValid,
                                            @RequestParam(defaultValue = "0") int expiringDays) {
        if (expiringDays > 0) {
            return R.ok(queryService.expiringBatches(memberId, expiringDays));
        }
        return R.ok(queryService.pointBatches(memberId, onlyValid));
    }

    @Operation(summary = "资产调整（余额/赠送/积分/成长值，必填原因）")
    @PostMapping("/adjust")
    @PreAuthorize("@ps.has('account:adjust')")
    @Idempotent(prefix = "account-adjust", desc = "该笔资产调整正在处理中，请勿重复提交")
    @OpAudit(module = "资产账务", opType = "ADJUST", desc = "资产调整")
    public R<AccountChangeResult> adjust(@Valid @RequestBody AccountAdjustCmd cmd) {
        // 幂等键优先取请求体，取不到再回落到请求头。
        // 只靠切面读请求头是不够的：那样服务层拿不到键、只能自造业务单号，
        // 数据库唯一索引这层防线就失效了（缓存过期后的重放会真的再调一次）。
        String idemKey = cmd.getIdempotencyKey() != null && !cmd.getIdempotencyKey().isBlank()
                ? cmd.getIdempotencyKey()
                : IdempotentAspect.currentKey();
        return R.ok(accountService.adjust(cmd.getMemberId(), cmd.getBalanceDelta(), cmd.getGiftDelta(),
                cmd.getPointsDelta(), cmd.getGrowthDelta(), cmd.getReason(),
                securityService.currentUserId(), securityService.currentStoreId(),
                idemKey));
    }

    @Operation(summary = "对账（余额/积分 vs 流水与批次）")
    @GetMapping("/reconcile")
    @PreAuthorize("@ps.has('account:reconcile')")
    public R<Map<String, Object>> reconcile() {
        return R.ok(queryService.reconcile());
    }

    @Operation(summary = "单会员账实核对（排障）")
    @GetMapping("/{memberId}/reconcile")
    @PreAuthorize("@ps.has('account:reconcile')")
    public R<Map<String, Object>> reconcileOne(@PathVariable Long memberId) {
        return R.ok(queryService.reconcileOne(memberId));
    }

    @Operation(summary = "储值/积分负债汇总")
    @GetMapping("/liability")
    @PreAuthorize("@ps.hasAny('account:reconcile','report:finance')")
    public R<Map<String, Object>> liability() {
        return R.ok(queryService.liability());
    }
}
