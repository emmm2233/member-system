package com.company.member.modules.cashier.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.member.common.constant.OperatorContext;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.util.BizNoGenerator;
import com.company.member.common.util.MoneyUtil;
import com.company.member.modules.cashier.dto.HoldOrderCmd;
import com.company.member.modules.cashier.entity.Order;
import com.company.member.modules.cashier.mapper.OrderMapper;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.mapper.MemberMapper;
import com.company.member.modules.store.mapper.StoreMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 挂单/取单/弃单服务
 *
 * <p>设计要点：
 * <ul>
 *   <li>挂单**不产生任何资产变动**，只保存购物车快照（status=7 挂单）</li>
 *   <li>快照存于 {@code m_order.remark} 的结构化 JSON 段（{@code [CART:{...}]}），
 *       不新增表/字段即可完整还原购物车（含商品 id、名称、单价、数量）</li>
 *   <li>取单返回快照供前端重建购物车；正式结算时把挂单标记为「已取消」，
 *       结算本身生成新的正式订单，避免挂单与正式单状态混用</li>
 *   <li>{@code HoldOrderCleanJob} 定时把超过 24 小时的挂单置为已取消</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HoldOrderService {

    /** 购物车快照在备注中的标记 */
    private static final String CART_OPEN = "[CART:";
    private static final String CART_CLOSE = "]";
    /** 挂单默认保留小时数 */
    private static final int HOLD_EXPIRE_HOURS = 24;

    private final OrderMapper orderMapper;
    private final MemberMapper memberMapper;
    private final StoreMapper storeMapper;
    private final BizNoGenerator noGenerator;
    private final ObjectMapper objectMapper;

    /* ==================================================================
     *  挂单
     * ================================================================== */

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> hold(HoldOrderCmd cmd) {
        if (cmd.getItems() == null || cmd.getItems().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "挂单明细不能为空");
        }
        Long storeId = cmd.getStoreId() != null ? cmd.getStoreId() : OperatorContext.storeId();
        Long memberId = cmd.getMemberId();
        String memberName = cmd.getMemberName();
        if (memberId != null && (memberName == null || memberName.isBlank())) {
            Member member = memberMapper.selectById(memberId);
            memberName = member == null ? null : member.getNickname();
        }

        // 计算挂单金额（仅用于展示，不作为应收）
        BigDecimal total = BigDecimal.ZERO;
        for (HoldOrderCmd.Item it : cmd.getItems()) {
            if (it.getQty() == null || it.getQty().signum() <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "数量必须大于 0");
            }
            total = total.add(MoneyUtil.mul(it.getPrice(), it.getQty()));
        }

        String orderNo = noGenerator.generateWithRetry(() -> noGenerator.custom("H"),
                no -> orderMapper.selectByOrderNo(no) != null);

        String cartJson = serializeCart(cmd.getItems());
        StringBuilder remark = new StringBuilder();
        if (cmd.getRemark() != null && !cmd.getRemark().isBlank()) {
            remark.append(cmd.getRemark()).append(' ');
        }
        if (memberName != null && !memberName.isBlank()) {
            remark.append("[MEMBER:").append(memberName).append("] ");
        }
        remark.append(CART_OPEN).append(cartJson).append(CART_CLOSE);

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setOrderType(Order.TYPE_CONSUME);
        order.setMemberId(memberId == null ? 0L : memberId);
        order.setStoreId(storeId);
        order.setCashierId(OperatorContext.userId());
        order.setTotalAmount(MoneyUtil.scale(total));
        order.setPayableAmount(MoneyUtil.scale(total));
        order.setPaidAmount(BigDecimal.ZERO);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setCouponAmount(BigDecimal.ZERO);
        order.setPointDeductAmount(BigDecimal.ZERO);
        order.setPointDeductPoints(0L);
        order.setGiftAmount(BigDecimal.ZERO);
        order.setPointsGained(0L);
        order.setGrowthGained(0L);
        order.setRefundAmount(BigDecimal.ZERO);
        order.setStatus(Order.STATUS_HOLD);
        order.setShiftNo(cmd.getShiftNo());
        order.setIdempotencyKey(cmd.getIdempotencyKey() == null || cmd.getIdempotencyKey().isBlank()
                ? orderNo : cmd.getIdempotencyKey());
        order.setRemark(remark.toString());
        order.setExpireTime(LocalDateTime.now().plusHours(HOLD_EXPIRE_HOURS));
        order.setTenantId(0L);
        orderMapper.insert(order);

        log.info("[挂单] 成功 holdNo={}, memberId={}, 明细={} 项, 金额={}",
                orderNo, memberId, cmd.getItems().size(), total);

        Map<String, Object> result = new HashMap<>();
        result.put("holdNo", orderNo);
        result.put("memberId", memberId);
        result.put("memberName", memberName);
        result.put("itemCount", cmd.getItems().size());
        result.put("totalAmount", MoneyUtil.scale(total));
        result.put("expireTime", order.getExpireTime());
        result.put("tip", "挂单保留 " + HOLD_EXPIRE_HOURS + " 小时，超时自动取消");
        return result;
    }

    /* ==================================================================
     *  挂单列表
     * ================================================================== */

    public List<Map<String, Object>> list(Long storeId, Long cashierId, boolean onlyMine) {
        LambdaQueryWrapper<Order> w = new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, Order.STATUS_HOLD)
                .orderByDesc(Order::getId);
        // 数据范围：店长看全店，收银员默认看自己的
        if (onlyMine) {
            Long me = OperatorContext.userId();
            w.eq(me != null, Order::getCashierId, me);
        } else {
            Long scopeStore = storeId != null ? storeId : OperatorContext.storeId();
            w.eq(scopeStore != null, Order::getStoreId, scopeStore);
            if (cashierId != null) {
                w.eq(Order::getCashierId, cashierId);
            }
        }

        List<Order> holds = orderMapper.selectList(w);
        List<Map<String, Object>> list = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Order o : holds) {
            Map<String, Object> m = new HashMap<>();
            m.put("holdNo", o.getOrderNo());
            m.put("memberId", o.getMemberId() == null || o.getMemberId() == 0 ? null : o.getMemberId());
            m.put("memberName", extractMemberName(o.getRemark()));
            m.put("storeId", o.getStoreId());
            m.put("storeName", o.getStoreId() == null ? null : storeMapper.selectNameById(o.getStoreId()));
            m.put("cashierId", o.getCashierId());
            m.put("totalAmount", o.getTotalAmount());
            m.put("shiftNo", o.getShiftNo());
            m.put("createdAt", o.getCreatedAt());
            m.put("expireTime", o.getExpireTime());
            m.put("expired", o.getExpireTime() != null && now.isAfter(o.getExpireTime()));
            List<HoldOrderCmd.Item> items = deserializeCart(o.getRemark());
            m.put("itemCount", items.size());
            m.put("items", items);
            m.put("note", extractNote(o.getRemark()));
            list.add(m);
        }
        return list;
    }

    /* ==================================================================
     *  取单
     * ================================================================== */

    /**
     * 取单：返回购物车快照，并把挂单标记为已取消（正式结算会生成新订单）
     *
     * <p>取单即锁定该挂单，避免两个收银台同时取到同一单。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> resume(String holdNo) {
        Order hold = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, holdNo).last("LIMIT 1"));
        if (hold == null) {
            throw new BizException(ErrorCode.HOLD_ORDER_NOT_FOUND);
        }
        if (hold.getStatus() == null || hold.getStatus() != Order.STATUS_HOLD) {
            throw new BizException(ErrorCode.HOLD_ORDER_NOT_FOUND, "该挂单已被取用或已取消");
        }
        if (hold.getExpireTime() != null && LocalDateTime.now().isAfter(hold.getExpireTime())) {
            orderMapper.updateStatusIfPending(hold.getId(), Order.STATUS_CANCELLED);
            throw new BizException(ErrorCode.HOLD_ORDER_NOT_FOUND, "该挂单已超时，请重新开单");
        }

        // 乐观占用：仅当仍为挂单时可流转
        int rows = orderMapper.updateStatusIfPending(hold.getId(), Order.STATUS_CANCELLED);
        if (rows == 0) {
            throw new BizException(ErrorCode.HOLD_ORDER_NOT_FOUND, "该挂单已被其他收银台取用");
        }

        List<HoldOrderCmd.Item> items = deserializeCart(hold.getRemark());
        Map<String, Object> result = new HashMap<>();
        result.put("holdNo", hold.getOrderNo());
        result.put("memberId", hold.getMemberId() == null || hold.getMemberId() == 0 ? null : hold.getMemberId());
        result.put("memberName", extractMemberName(hold.getRemark()));
        result.put("storeId", hold.getStoreId());
        result.put("items", items);
        result.put("itemCount", items.size());
        result.put("totalAmount", hold.getTotalAmount());
        result.put("note", extractNote(hold.getRemark()));
        log.info("[挂单] 取单 holdNo={}, {} 项", holdNo, items.size());
        return result;
    }

    /* ==================================================================
     *  弃单
     * ================================================================== */

    @Transactional(rollbackFor = Exception.class)
    public void cancel(String holdNo, String reason) {
        Order hold = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, holdNo).last("LIMIT 1"));
        if (hold == null) {
            throw new BizException(ErrorCode.HOLD_ORDER_NOT_FOUND);
        }
        if (hold.getStatus() == null || hold.getStatus() != Order.STATUS_HOLD) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID, "该单不是挂单状态");
        }
        hold.setStatus(Order.STATUS_CANCELLED);
        hold.setRemark((hold.getRemark() == null ? "" : hold.getRemark())
                + " | 弃单：" + (reason == null ? "未说明" : reason));
        orderMapper.updateById(hold);
        log.info("[挂单] 弃单 holdNo={}, 原因={}", holdNo, reason);
    }

    /* ==================================================================
     *  超时清理（定时任务调用）
     * ================================================================== */

    @Transactional(rollbackFor = Exception.class)
    public int cleanExpired(int limit) {
        List<Order> expired = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, Order.STATUS_HOLD)
                .isNotNull(Order::getExpireTime)
                .lt(Order::getExpireTime, LocalDateTime.now())
                .orderByAsc(Order::getId)
                .last("LIMIT " + (limit <= 0 ? 200 : limit)));
        int count = 0;
        for (Order o : expired) {
            int rows = orderMapper.updateStatusIfPending(o.getId(), Order.STATUS_CANCELLED);
            if (rows > 0) {
                count++;
            }
        }
        if (count > 0) {
            log.info("[挂单] 超时清理完成，共取消 {} 单", count);
        }
        return count;
    }

    /* ==================================================================
     *  快照序列化
     * ================================================================== */

    private String serializeCart(List<HoldOrderCmd.Item> items) {
        try {
            List<Map<String, Object>> plain = new ArrayList<>();
            for (HoldOrderCmd.Item it : items) {
                Map<String, Object> m = new HashMap<>();
                m.put("itemType", it.getItemType() == null ? 1 : it.getItemType());
                m.put("productId", it.getProductId());
                m.put("productName", it.getProductName());
                m.put("price", it.getPrice() == null ? "0.00" : MoneyUtil.scale(it.getPrice()).toPlainString());
                m.put("qty", it.getQty() == null ? "1" : it.getQty().toPlainString());
                plain.add(m);
            }
            return objectMapper.writeValueAsString(plain);
        } catch (Exception e) {
            log.error("[挂单] 购物车序列化失败", e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "挂单失败，请重试");
        }
    }

    private List<HoldOrderCmd.Item> deserializeCart(String remark) {
        List<HoldOrderCmd.Item> items = new ArrayList<>();
        String json = extractCartJson(remark);
        if (json == null) {
            return items;
        }
        try {
            List<Map<String, Object>> plain = objectMapper.readValue(json, new TypeReference<>() {
            });
            for (Map<String, Object> m : plain) {
                HoldOrderCmd.Item it = new HoldOrderCmd.Item();
                Object itemType = m.get("itemType");
                it.setItemType(itemType == null ? 1 : ((Number) itemType).intValue());
                Object productId = m.get("productId");
                it.setProductId(productId == null ? null : ((Number) productId).longValue());
                it.setProductName(m.get("productName") == null ? null : String.valueOf(m.get("productName")));
                it.setPrice(m.get("price") == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(m.get("price"))));
                it.setQty(m.get("qty") == null ? BigDecimal.ONE : new BigDecimal(String.valueOf(m.get("qty"))));
                items.add(it);
            }
        } catch (Exception e) {
            log.warn("[挂单] 购物车反序列化失败，按空明细返回：{}", e.getMessage());
        }
        return items;
    }

    /**
     * 提取购物车 JSON
     *
     * <p>注意：Markdown 标记是 {@code [CART:...]}，而 JSON 数组本身也以 {@code [} 开头、
     * 以 {@code ]} 结尾，形成 {@code [CART:[{...}]]} 的嵌套结构。
     * 因此**不能**用 {@code indexOf(']')} 找结束位置（会截断到内层数组的右括号），
     * 必须按括号深度配对查找。
     */
    private String extractCartJson(String remark) {
        if (remark == null) {
            return null;
        }
        int start = remark.indexOf(CART_OPEN);
        if (start < 0) {
            return null;
        }
        int contentStart = start + CART_OPEN.length();
        int depth = 1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = contentStart; i < remark.length(); i++) {
            char c = remark.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    return remark.substring(contentStart, i);
                }
            }
        }
        return null;
    }

    private String extractMemberName(String remark) {
        if (remark == null) {
            return null;
        }
        int start = remark.indexOf("[MEMBER:");
        if (start < 0) {
            return null;
        }
        int end = remark.indexOf(']', start);
        return end < 0 ? null : remark.substring(start + "[MEMBER:".length(), end);
    }

    private String extractNote(String remark) {
        if (remark == null || remark.isBlank()) {
            return null;
        }
        String note = remark;
        int cart = note.indexOf(CART_OPEN);
        if (cart > 0) {
            note = note.substring(0, cart);
        }
        int member = note.indexOf("[MEMBER:");
        if (member >= 0) {
            int end = note.indexOf(']', member);
            note = note.substring(0, member) + (end > 0 ? note.substring(end + 1) : "");
        }
        note = note.trim();
        return note.isBlank() ? null : note;
    }
}
