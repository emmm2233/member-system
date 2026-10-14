package com.company.member.modules.marketing.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.member.common.annotation.Idempotent;
import com.company.member.common.annotation.NoAudit;
import com.company.member.common.annotation.OpAudit;
import com.company.member.common.result.PageResult;
import com.company.member.common.result.R;
import com.company.member.modules.marketing.dto.CouponGrantCmd;
import com.company.member.modules.marketing.dto.CouponTplSaveCmd;
import com.company.member.modules.marketing.entity.CouponTpl;
import com.company.member.modules.marketing.entity.MemberCoupon;
import com.company.member.modules.marketing.mapper.CouponTplMapper;
import com.company.member.modules.marketing.mapper.MemberCouponMapper;
import com.company.member.modules.marketing.service.CouponService;
import com.company.member.modules.marketing.service.CouponTplService;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端 · 卡券（模板管理、发券向导、券查询、核销、统计）
 */
@Tag(name = "管理端-卡券")
@RestController
@RequestMapping("/api/admin/marketing")
@RequiredArgsConstructor
public class CouponController {

    private final CouponTplMapper tplMapper;
    private final MemberCouponMapper couponMapper;
    private final CouponService couponService;
    private final CouponTplService tplService;
    private final SecurityService securityService;

    @Operation(summary = "券模板列表")
    @GetMapping("/coupon-tpls")
    @PreAuthorize("@ps.hasAny('coupon:tpl:list','coupon:grant')")
    public R<List<CouponTpl>> templates(@RequestParam(defaultValue = "true") boolean onlyEnabled) {
        return R.ok(tplMapper.selectList(new LambdaQueryWrapper<CouponTpl>()
                .eq(CouponTpl::getDeleted, 0)
                .eq(onlyEnabled, CouponTpl::getStatus, 1)
                .orderByAsc(CouponTpl::getSortNo)));
    }

    @Operation(summary = "会员券分页（收银台选券复用）")
    @GetMapping("/coupons")
    @PreAuthorize("@ps.hasAny('coupon:list','order:add')")
    public R<PageResult<Map<String, Object>>> memberCoupons(@RequestParam(required = false) Long memberId,
                                                            @RequestParam(required = false) Integer status,
                                                            @RequestParam(required = false) Long tplId,
                                                            @RequestParam(defaultValue = "1") int pageNum,
                                                            @RequestParam(defaultValue = "50") int pageSize) {
        LambdaQueryWrapper<MemberCoupon> w = new LambdaQueryWrapper<MemberCoupon>()
                .eq(memberId != null, MemberCoupon::getMemberId, memberId)
                .eq(status != null, MemberCoupon::getStatus, status)
                .eq(tplId != null, MemberCoupon::getTplId, tplId)
                .orderByAsc(MemberCoupon::getValidEnd)
                .orderByDesc(MemberCoupon::getId);
        Page<MemberCoupon> page = couponMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 200)), w);
        List<Map<String, Object>> list = new ArrayList<>();
        for (MemberCoupon c : page.getRecords()) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId());
            m.put("couponNo", c.getCouponNo());
            m.put("tplId", c.getTplId());
            m.put("tplName", c.getCouponName());
            m.put("couponType", c.getCouponType());
            m.put("faceValue", c.getFaceValue());
            m.put("minAmount", c.getMinAmount());
            m.put("maxDiscount", c.getMaxDiscount());
            m.put("status", c.getStatus());
            m.put("statusText", MemberCoupon.statusText(c.getStatus()));
            m.put("source", c.getSource());
            m.put("validStart", c.getValidStart());
            m.put("validEnd", c.getValidEnd());
            m.put("usedAt", c.getUsedAt());
            m.put("orderId", c.getOrderId());
            m.put("memberId", c.getMemberId());
            list.add(m);
        }
        return R.ok(PageResult.of(list, page.getTotal(), pageNum, page.getSize()));
    }

    @Operation(summary = "定向发券")
    @PostMapping("/coupons/grant")
    @PreAuthorize("@ps.has('coupon:grant')")
    @OpAudit(opType = "GRANT", desc = "手工发券")
    public R<List<String>> grant(@RequestParam Long memberId,
                                 @RequestParam Long tplId,
                                 @RequestParam(defaultValue = "1") int qty,
                                 @RequestParam(required = false) String reason) {
        List<MemberCoupon> coupons = couponService.grant(memberId, tplId, qty, "GRANT",
                "admin:" + securityService.currentUserId());
        return R.ok(coupons.stream().map(MemberCoupon::getCouponNo).toList());
    }

    @Operation(summary = "作废券")
    @PostMapping("/coupons/{couponNo}/void")
    @PreAuthorize("@ps.has('coupon:void')")
    @OpAudit(opType = "DELETE", desc = "券作废")
    public R<Void> voidCoupon(@PathVariable String couponNo, @RequestParam String reason) {
        MemberCoupon c = couponService.getByCouponNo(couponNo);
        couponService.voidCoupon(c.getId(), reason);
        return R.ok();
    }

    @Operation(summary = "券核销（券码直接核销，不关联订单）")
    @PostMapping("/coupons/verify")
    @PreAuthorize("@ps.has('coupon:verify')")
    @OpAudit(opType = "VERIFY", desc = "券核销")
    @Idempotent(prefix = "coupon-verify", desc = "该券核销正在处理中，请勿重复提交")
    public R<Map<String, Object>> verify(@RequestParam String couponNo,
                                         @RequestParam(required = false) Long storeId) {
        MemberCoupon c = couponService.getByCouponNo(couponNo);
        couponService.validateUnlockedUsable(c);
        couponService.use(c.getId(), null,
                storeId != null ? storeId : securityService.currentStoreId());
        Map<String, Object> m = new HashMap<>();
        m.put("couponNo", couponNo);
        m.put("tplName", c.getCouponName());
        m.put("verifiedAt", java.time.LocalDateTime.now());
        m.put("statusText", "已使用");
        return R.ok(m);
    }

    @Operation(summary = "券统计（发放/核销概览）")
    @GetMapping("/coupons/statistics")
    @PreAuthorize("@ps.has('coupon:list')")
    public R<Map<String, Object>> statistics() {
        List<CouponTpl> tpls = tplMapper.selectList(new LambdaQueryWrapper<CouponTpl>().eq(CouponTpl::getDeleted, 0));
        long issued = tpls.stream().mapToLong(t -> t.getIssuedQty() == null ? 0 : t.getIssuedQty()).sum();
        long used = tpls.stream().mapToLong(t -> t.getUsedQty() == null ? 0 : t.getUsedQty()).sum();
        Map<String, Object> m = new HashMap<>();
        m.put("tplCount", tpls.size());
        m.put("issuedCount", issued);
        m.put("usedCount", used);
        m.put("useRate", issued == 0 ? 0 : Math.round(used * 10000.0 / issued) / 100.0);
        return R.ok(m);
    }

    /* ==================== 券模板 CRUD（②） ==================== */

    @Operation(summary = "券模板列表（支持关键词）")
    @GetMapping("/coupon-tpls/list")
    @PreAuthorize("@ps.has('coupon:tpl:list')")
    public R<List<CouponTpl>> tplList(@RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) Boolean onlyEnabled) {
        return R.ok(tplService.list(onlyEnabled, keyword));
    }

    @Operation(summary = "券模板详情")
    @GetMapping("/coupon-tpls/{id}/detail")
    @PreAuthorize("@ps.has('coupon:tpl:query')")
    public R<CouponTpl> tplDetail(@PathVariable Long id) {
        return R.ok(tplService.detail(id));
    }

    @Operation(summary = "新增券模板")
    @PostMapping("/coupon-tpls")
    @PreAuthorize("@ps.has('coupon:tpl:add')")
    public R<CouponTpl> createTpl(@Valid @RequestBody CouponTplSaveCmd cmd) {
        return R.ok(tplService.create(cmd));
    }

    @Operation(summary = "修改券模板（不影响已发放的券）")
    @PutMapping("/coupon-tpls/{id}")
    @PreAuthorize("@ps.has('coupon:tpl:edit')")
    public R<CouponTpl> updateTpl(@PathVariable Long id, @Valid @RequestBody CouponTplSaveCmd cmd) {
        return R.ok(tplService.update(id, cmd));
    }

    @Operation(summary = "启用/停用券模板")
    @PutMapping("/coupon-tpls/{id}/status")
    @PreAuthorize("@ps.has('coupon:tpl:edit')")
    public R<Void> changeTplStatus(@PathVariable Long id, @RequestParam Integer status) {
        tplService.changeStatus(id, status);
        return R.ok();
    }

    @Operation(summary = "删除券模板（已发放过的不可删除）")
    @DeleteMapping("/coupon-tpls/{id}")
    @PreAuthorize("@ps.has('coupon:tpl:edit')")
    public R<Void> removeTpl(@PathVariable Long id) {
        tplService.remove(id);
        return R.ok();
    }

    @Operation(summary = "单模板效果统计")
    @GetMapping("/coupon-tpls/{id}/statistics")
    @PreAuthorize("@ps.has('coupon:stat')")
    public R<Map<String, Object>> tplStatistics(@PathVariable Long id) {
        return R.ok(tplService.tplStatistics(id));
    }

    /* ==================== 发券向导（②） ==================== */

    @Operation(summary = "发券目标人数预估")
    @PostMapping("/coupons/estimate")
    @PreAuthorize("@ps.has('coupon:grant')")
    @NoAudit("发券人数预估：纯查询无副作用")
    public R<Map<String, Object>> estimate(@RequestBody CouponGrantCmd cmd) {
        return R.ok(tplService.estimate(cmd));
    }

    @Operation(summary = "执行发券（定向/条件/全量）")
    @PostMapping("/coupons/grant-batch")
    @PreAuthorize("@ps.has('coupon:grant')")
    @OpAudit(opType = "GRANT", desc = "批量发券")
    public R<Map<String, Object>> grantBatch(@Valid @RequestBody CouponGrantCmd cmd) {
        return R.ok(tplService.grant(cmd));
    }

    @Operation(summary = "发券任务进度")
    @GetMapping("/coupons/grant-task/{taskNo}")
    @PreAuthorize("@ps.has('coupon:grant')")
    public R<Map<String, Object>> grantProgress(@PathVariable String taskNo) {
        return R.ok(tplService.grantProgress(taskNo));
    }
}
