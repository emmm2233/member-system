package com.company.member.modules.cashier.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.member.common.annotation.NoAudit;
import com.company.member.common.annotation.OpAudit;
import com.company.member.common.annotation.Idempotent;
import com.company.member.common.result.PageResult;
import com.company.member.common.result.R;
import com.company.member.modules.cashier.dto.CashierOrderCmd;
import com.company.member.modules.cashier.dto.HoldOrderCmd;
import com.company.member.modules.cashier.dto.SettleResult;
import com.company.member.modules.cashier.entity.Order;
import com.company.member.modules.cashier.entity.OrderItem;
import com.company.member.modules.cashier.entity.OrderPayment;
import com.company.member.modules.cashier.entity.OrderRefund;
import com.company.member.modules.cashier.mapper.OrderMapper;
import com.company.member.modules.cashier.mapper.OrderRefundMapper;
import com.company.member.modules.cashier.service.CashierService;
import com.company.member.modules.cashier.service.HoldOrderService;
import com.company.member.modules.cashier.service.RefundService;
import com.company.member.modules.store.mapper.StoreMapper;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理端 · 收银台与订单
 */
@Tag(name = "管理端-收银与订单")
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class CashierController {

    private final CashierService cashierService;
    private final HoldOrderService holdOrderService;
    private final RefundService refundService;
    private final OrderMapper orderMapper;
    private final OrderRefundMapper refundMapper;
    private final com.company.member.modules.cashier.mapper.OrderPaymentMapper orderPaymentMapper;
    private final StoreMapper storeMapper;
    private final SecurityService securityService;

    /* ==================== 收银 ==================== */

    @Operation(summary = "结算试算（不落库，返回各项优惠与应收）")
    @PostMapping("/preview")
    @PreAuthorize("@ps.hasAny('order:add','order:list')")
    @NoAudit("开单试算：纯计算无副作用，不产生任何业务变更")
    public R<SettleResult> preview(@Valid @RequestBody CashierOrderCmd cmd) {
        return R.ok(cashierService.preview(cmd));
    }

    @Operation(summary = "开单结算（核心接口，幂等）")
    @PostMapping("/settle")
    @PreAuthorize("@ps.has('order:add')")
    @Idempotent(prefix = "settle", desc = "该单正在结算中，请勿重复提交")
    public R<SettleResult> settle(@Valid @RequestBody CashierOrderCmd cmd) {
        return R.ok(cashierService.settle(cmd));
    }

    /* ==================== 订单查询 ==================== */

    @Operation(summary = "订单分页")
    @GetMapping
    @PreAuthorize("@ps.has('order:list')")
    public R<PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) Integer orderType,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) Long cashierId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        LambdaQueryWrapper<Order> w = new LambdaQueryWrapper<Order>()
                .eq(orderNo != null && !orderNo.isBlank(), Order::getOrderNo, orderNo)
                .eq(memberId != null, Order::getMemberId, memberId)
                .eq(orderType != null, Order::getOrderType, orderType)
                .eq(status != null, Order::getStatus, status)
                .eq(storeId != null, Order::getStoreId, storeId)
                .eq(cashierId != null, Order::getCashierId, cashierId)
                .ge(startDate != null, Order::getCreatedAt, startDate == null ? null : startDate.atStartOfDay())
                .lt(endDate != null, Order::getCreatedAt, endDate == null ? null : endDate.plusDays(1).atStartOfDay())
                .orderByDesc(Order::getId);

        // 数据权限
        List<Long> scopeStores = securityService.dataScopeStoreIds();
        if (scopeStores != null) {
            w.in(Order::getStoreId, scopeStores);
        }

        Page<Order> page = orderMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 200)), w);
        Map<Long, String> storeNames = page.getRecords().isEmpty() ? Map.of()
                : storeMapper.selectBatchIds(page.getRecords().stream().map(Order::getStoreId)
                        .filter(java.util.Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(s -> s.getId(), s -> s.getStoreName(), (a, b) -> a));

        List<Map<String, Object>> list = new ArrayList<>();
        for (Order o : page.getRecords()) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", o.getId());
            m.put("orderNo", o.getOrderNo());
            m.put("orderType", o.getOrderType());
            m.put("orderTypeText", Order.typeText(o.getOrderType()));
            m.put("memberId", o.getMemberId());
            m.put("storeId", o.getStoreId());
            m.put("storeName", storeNames.get(o.getStoreId()));
            m.put("totalAmount", o.getTotalAmount());
            m.put("discountAmount", o.getDiscountAmount());
            m.put("couponAmount", o.getCouponAmount());
            m.put("pointDeductAmount", o.getPointDeductAmount());
            m.put("payableAmount", o.getPayableAmount());
            m.put("paidAmount", o.getPaidAmount());
            m.put("giftAmount", o.getGiftAmount());
            m.put("pointsGained", o.getPointsGained());
            m.put("status", o.getStatus());
            m.put("statusText", Order.statusText(o.getStatus()));
            m.put("refundAmount", o.getRefundAmount());
            m.put("createdAt", o.getCreatedAt());
            m.put("paidAt", o.getPaidAt());
            list.add(m);
        }
        return R.ok(PageResult.of(list, page.getTotal(), pageNum, page.getSize()));
    }

    @Operation(summary = "订单详情（含明细/支付/优惠）")
    @GetMapping("/{orderNo}")
    @PreAuthorize("@ps.has('order:query')")
    public R<Map<String, Object>> detail(@PathVariable String orderNo) {
        Order order = cashierService.getByOrderNo(orderNo);
        Map<String, Object> m = new HashMap<>();
        m.put("order", order);
        m.put("orderTypeText", Order.typeText(order.getOrderType()));
        m.put("statusText", Order.statusText(order.getStatus()));
        m.put("storeName", order.getStoreId() == null ? null : storeMapper.selectNameById(order.getStoreId()));
        m.put("items", cashierService.items(order.getId()));
        m.put("payments", cashierService.payments(order.getId()));
        m.put("refundableAmount", refundService.refundableAmount(orderNo));
        m.put("refunds", refundMapper.selectList(new LambdaQueryWrapper<OrderRefund>()
                .eq(OrderRefund::getOrderNo, orderNo).orderByDesc(OrderRefund::getId)));
        return R.ok(m);
    }

    /* ==================== 退单 ==================== */

    @Operation(summary = "申请退单")
    @PostMapping("/{orderNo}/refund")
    @PreAuthorize("@ps.has('order:refund:apply')")
    @Idempotent(prefix = "refund", desc = "退单正在提交中，请勿重复操作")
    @OpAudit(opType = "REFUND", desc = "退单申请")
    public R<OrderRefund> applyRefund(@PathVariable String orderNo,
                                      @RequestParam BigDecimal refundAmount,
                                      @RequestParam String reason) {
        return R.ok(refundService.apply(orderNo, refundAmount, reason,
                securityService.currentUserId(), null));
    }

    @Operation(summary = "审批退单（pass=true 通过 / false 驳回）")
    @PostMapping("/refunds/{refundNo}/approve")
    @PreAuthorize("@ps.has('order:refund:approve')")
    @OpAudit(opType = "AUDIT", desc = "退单审批")
    @Idempotent(prefix = "refund-approve", desc = "该退单正在审批中，请勿重复提交")
    public R<Void> approve(@PathVariable String refundNo,
                           @RequestParam boolean pass,
                           @RequestParam(required = false) String remark) {
        refundService.approve(refundNo, pass, remark, securityService.currentUserId());
        return R.ok();
    }

    @Operation(summary = "退款单分页")
    @GetMapping("/refunds")
    @PreAuthorize("@ps.has('order:refund:list')")
    public R<PageResult<OrderRefund>> refunds(@RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) String orderNo,
                                              @RequestParam(defaultValue = "1") int pageNum,
                                              @RequestParam(defaultValue = "20") int pageSize) {
        LambdaQueryWrapper<OrderRefund> w = new LambdaQueryWrapper<OrderRefund>()
                .eq(status != null, OrderRefund::getStatus, status)
                .eq(orderNo != null && !orderNo.isBlank(), OrderRefund::getOrderNo, orderNo)
                .orderByDesc(OrderRefund::getId);
        List<Long> scopeStores = securityService.dataScopeStoreIds();
        if (scopeStores != null) {
            w.in(OrderRefund::getStoreId, scopeStores);
        }
        return R.ok(PageResult.of(refundMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 200)), w)));
    }

    /* ==================== 挂单 / 取单 / 弃单 ==================== */

    @Operation(summary = "挂单（保存购物车快照，不产生资产变动）")
    @PostMapping("/hold")
    @PreAuthorize("@ps.has('order:add')")
    @Idempotent(prefix = "order-hold", desc = "挂单正在处理中，请勿重复提交")
    @OpAudit(opType = "CREATE", desc = "挂单")
    public R<Map<String, Object>> hold(@Valid @RequestBody HoldOrderCmd cmd) {
        return R.ok(holdOrderService.hold(cmd));
    }

    @Operation(summary = "挂单列表（onlyMine=true 只看自己的）")
    @GetMapping("/hold/list")
    @PreAuthorize("@ps.has('order:add')")
    public R<List<Map<String, Object>>> holdList(@RequestParam(required = false) Long storeId,
                                                 @RequestParam(required = false) Long cashierId,
                                                 @RequestParam(defaultValue = "false") boolean onlyMine) {
        return R.ok(holdOrderService.list(storeId, cashierId, onlyMine));
    }

    @Operation(summary = "取单（返回购物车快照，挂单置为已取用）")
    @PostMapping("/hold/{holdNo}/resume")
    @PreAuthorize("@ps.has('order:add')")
    @OpAudit(opType = "UPDATE", desc = "取单")
    public R<Map<String, Object>> resumeHold(@PathVariable String holdNo) {
        return R.ok(holdOrderService.resume(holdNo));
    }

    @Operation(summary = "弃单（取消挂单）")
    @DeleteMapping("/hold/{holdNo}")
    @PreAuthorize("@ps.has('order:add')")
    public R<Void> cancelHold(@PathVariable String holdNo,
                              @RequestParam(required = false) String reason) {
        holdOrderService.cancel(holdNo, reason);
        return R.ok();
    }

    @Operation(summary = "手动清理超时挂单（运维用）")
    @PostMapping("/hold/clean-expired")
    @PreAuthorize("@ps.has('order:add')")
    @NoAudit("运维触发清理超时挂单：无人工操作语义")
    public R<Integer> cleanExpiredHolds() {
        return R.ok(holdOrderService.cleanExpired(200));
    }

    /* ==================== 交接班 ==================== */

    @Operation(summary = "交接班汇总")
    @GetMapping("/shift/summary")
    @PreAuthorize("@ps.has('shift:summary')")
    public R<Map<String, Object>> shiftSummary(@RequestParam Long storeId,
                                               @RequestParam(required = false) LocalDate date) {
        LocalDate d = date == null ? LocalDate.now() : date;
        Map<String, Object> sum = orderMapper.sumByStoreAndRange(storeId,
                d.atStartOfDay(), d.plusDays(1).atStartOfDay());
        Map<String, Object> result = new HashMap<>();
        result.put("storeId", storeId);
        result.put("storeName", storeMapper.selectNameById(storeId));
        result.put("statDate", d.toString());
        result.put("orderCount", sum == null ? 0 : sum.get("orderCount"));
        result.put("paidAmount", sum == null ? BigDecimal.ZERO : sum.get("paidAmount"));
        result.put("refundAmount", sum == null ? BigDecimal.ZERO : sum.get("refundAmount"));
        result.put("rechargeAmount", sum == null ? BigDecimal.ZERO : sum.get("rechargeAmount"));
        result.put("cardSoldAmount", sum == null ? BigDecimal.ZERO : sum.get("cardSoldAmount"));
        result.put("cardSoldCount", sum == null ? 0 : sum.get("cardSoldCount"));
        java.util.List<Map<String, Object>> payMethods = new java.util.ArrayList<>();
        for (Map<String, Object> row : orderPaymentMapper.sumByPayMethod(storeId,
                d.atStartOfDay(), d.plusDays(1).atStartOfDay())) {
            Map<String, Object> m = new HashMap<>(row);
            Object method = row.get("payMethod");
            m.put("payMethodText", OrderPayment.methodText(method == null ? null : ((Number) method).intValue()));
            payMethods.add(m);
        }
        result.put("payMethodDetail", payMethods);
        result.put("checkTime", java.time.LocalDateTime.now());
        return R.ok(result);
    }
}
