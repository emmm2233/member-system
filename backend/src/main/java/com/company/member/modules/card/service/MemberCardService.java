package com.company.member.modules.card.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.util.BizNoGenerator;
import com.company.member.common.util.MoneyUtil;
import com.company.member.modules.card.entity.CardProduct;
import com.company.member.modules.card.entity.MemberCard;
import com.company.member.modules.card.entity.MemberCardItem;
import com.company.member.modules.card.mapper.CardProductMapper;
import com.company.member.modules.card.mapper.MemberCardItemMapper;
import com.company.member.modules.card.mapper.MemberCardMapper;
import com.company.member.modules.cashier.entity.Order;
import com.company.member.modules.cashier.entity.OrderItem;
import com.company.member.modules.cashier.entity.OrderPayment;
import com.company.member.modules.cashier.mapper.OrderItemMapper;
import com.company.member.modules.cashier.mapper.OrderMapper;
import com.company.member.modules.cashier.mapper.OrderPaymentMapper;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.mapper.MemberMapper;
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
 * 次卡服务：售卡 / 核销 / 冻结 / 延期 / 退卡
 *
 * <p>关键设计：
 * <ul>
 *   <li>售卡生成「售卡订单 + 会员卡 + 权益项」，与资产账务解耦（次卡不占用储值余额）</li>
 *   <li>核销采用行锁 + SQL 层剩余次数校验，防并发超扣</li>
 *   <li>核销记录写 before/after 次数快照，可完整追溯</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberCardService {

    private final CardProductMapper productMapper;
    private final MemberCardMapper cardMapper;
    private final MemberCardItemMapper itemMapper;
    private final MemberMapper memberMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderPaymentMapper orderPaymentMapper;
    private final BizNoGenerator noGenerator;

    /* ==================================================================
     *  售卡
     * ================================================================== */

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> sell(Long memberId, Long cardProductId, Integer payMethod,
                                    Long storeId, Long operatorId, String outTradeNo, String idempotencyKey) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BizException(ErrorCode.MEMBER_NOT_FOUND);
        }
        if (member.isFrozen() || member.isCancelled()) {
            throw new BizException(member.isFrozen() ? ErrorCode.MEMBER_FROZEN : ErrorCode.MEMBER_CANCELLED);
        }
        CardProduct product = productMapper.selectById(cardProductId);
        if (product == null || (product.getStatus() != null && product.getStatus() == 0)) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "卡产品不存在或已下架");
        }

        String orderNo = noGenerator.generateWithRetry(noGenerator::cardSaleOrderNo,
                no -> orderMapper.selectByOrderNo(no) != null);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime validEnd = product.getValidDays() != null && product.getValidDays() > 0
                ? now.plusDays(product.getValidDays()) : null;

        // 1. 售卡订单
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setOrderType(Order.TYPE_CARD_SALE);
        order.setMemberId(memberId);
        order.setStoreId(storeId != null ? storeId : member.getStoreId());
        order.setCashierId(operatorId);
        order.setTotalAmount(MoneyUtil.scale(product.getSellPrice()));
        order.setPayableAmount(MoneyUtil.scale(product.getSellPrice()));
        order.setPaidAmount(MoneyUtil.scale(product.getSellPrice()));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setCouponAmount(BigDecimal.ZERO);
        order.setPointDeductAmount(BigDecimal.ZERO);
        order.setPointDeductPoints(0L);
        order.setGiftAmount(BigDecimal.ZERO);
        order.setPointsGained(0L);
        order.setGrowthGained(0L);
        order.setRefundAmount(BigDecimal.ZERO);
        order.setStatus(Order.STATUS_PAID);
        order.setIdempotencyKey(idempotencyKey == null || idempotencyKey.isBlank() ? orderNo : idempotencyKey);
        order.setRemark("购买次卡：" + product.getCardName());
        order.setPaidAt(now);
        order.setTenantId(0L);
        orderMapper.insert(order);

        // 2. 订单明细
        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setOrderNo(orderNo);
        item.setItemType(OrderItem.TYPE_CARD);
        item.setProductName(product.getCardName());
        item.setProductCode(product.getCardCode());
        item.setPrice(MoneyUtil.scale(product.getSellPrice()));
        item.setQty(BigDecimal.ONE);
        item.setTotalAmount(MoneyUtil.scale(product.getSellPrice()));
        item.setDiscountAmount(BigDecimal.ZERO);
        item.setActualAmount(MoneyUtil.scale(product.getSellPrice()));
        item.setJoinDiscount(0);
        item.setJoinPoints(0);
        orderItemMapper.insert(item);

        // 3. 支付明细
        OrderPayment payment = new OrderPayment();
        payment.setOrderId(order.getId());
        payment.setOrderNo(orderNo);
        payment.setPayMethod(payMethod == null ? OrderPayment.METHOD_CASH : payMethod);
        payment.setAmount(MoneyUtil.scale(product.getSellPrice()));
        payment.setOutTradeNo(outTradeNo);
        payment.setTradeStatus("SUCCESS");
        payment.setPayTime(now);
        payment.setIdempotencyKey(orderNo + ":PAY");
        orderPaymentMapper.insert(payment);

        // 4. 会员卡
        MemberCard card = new MemberCard();
        card.setCardNo(noGenerator.generateWithRetry(() -> noGenerator.custom("MC"),
                no -> cardMapper.selectByCardNo(no) != null));
        card.setMemberId(memberId);
        card.setCardProductId(product.getId());
        card.setCardName(product.getCardName());
        card.setCardType(product.getCardType());
        card.setOrderId(order.getId());
        card.setPaidAmount(MoneyUtil.scale(product.getSellPrice()));
        card.setTotalTimes(product.getTotalTimes() == null ? 0 : product.getTotalTimes());
        card.setUsedTimes(0);
        card.setRemainTimes(card.getTotalTimes());
        card.setValidStart(now);
        card.setValidEnd(validEnd);
        card.setStatus(MemberCard.STATUS_NORMAL);
        card.setBuyStoreId(order.getStoreId());
        cardMapper.insert(card);

        // 5. 权益项（次卡默认单项，后续可扩展卡产品权益配置）
        MemberCardItem cardItem = new MemberCardItem();
        cardItem.setMemberCardId(card.getId());
        cardItem.setMemberId(memberId);
        cardItem.setItemName(product.getCardName());
        cardItem.setTotalTimes(card.getTotalTimes());
        cardItem.setUsedTimes(0);
        cardItem.setRemainTimes(card.getRemainTimes());
        cardItem.setValidStart(now);
        cardItem.setValidEnd(validEnd);
        cardItem.setStatus(MemberCardItem.STATUS_NORMAL);
        cardItem.setSortNo(0);
        itemMapper.insert(cardItem);

        log.info("[次卡] 售卡成功 orderNo={}, cardNo={}, memberId={}, 金额={}, 次数={}",
                orderNo, card.getCardNo(), memberId, product.getSellPrice(), card.getTotalTimes());

        Map<String, Object> result = new HashMap<>();
        result.put("orderNo", orderNo);
        result.put("cardNo", card.getCardNo());
        result.put("cardName", card.getCardName());
        result.put("totalTimes", card.getTotalTimes());
        result.put("validEnd", card.getValidEnd());
        result.put("paidAmount", card.getPaidAmount());
        result.put("statusText", MemberCard.statusText(card.getStatus()));
        return result;
    }

    /* ==================================================================
     *  核销
     * ================================================================== */

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> verify(String cardNo, Long itemId, int times, Long storeId,
                                      Long operatorId, String remark, String idempotencyKey) {
        if (times < 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "核销次数必须大于 0");
        }
        MemberCard card = cardMapper.selectForUpdateByCardNo(cardNo);
        if (card == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "次卡不存在");
        }
        if (card.getStatus() != null && card.getStatus() == MemberCard.STATUS_FROZEN) {
            throw new BizException(ErrorCode.CARD_UNAVAILABLE, "次卡已冻结，无法核销");
        }
        if (card.getStatus() != null && card.getStatus() == MemberCard.STATUS_REFUNDED) {
            throw new BizException(ErrorCode.CARD_UNAVAILABLE, "次卡已退卡");
        }
        if (card.getValidEnd() != null && LocalDateTime.now().isAfter(card.getValidEnd())) {
            cardMapper.markExpired(card.getId());
            throw new BizException(ErrorCode.CARD_UNAVAILABLE, "次卡已过期");
        }
        if (card.getRemainTimes() == null || card.getRemainTimes() < times) {
            throw new BizException(ErrorCode.CARD_TIMES_NOT_ENOUGH,
                    "剩余次数不足：当前剩余 " + (card.getRemainTimes() == null ? 0 : card.getRemainTimes()) + " 次");
        }

        MemberCardItem cardItem;
        if (itemId != null) {
            cardItem = itemMapper.selectForUpdate(itemId);
            if (cardItem == null || !cardItem.getMemberCardId().equals(card.getId())) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND, "权益项不存在");
            }
        } else {
            List<MemberCardItem> items = itemMapper.selectByCardId(card.getId());
            cardItem = items.stream()
                    .filter(i -> i.getStatus() != null && i.getStatus() == MemberCardItem.STATUS_NORMAL
                            && i.getRemainTimes() != null && i.getRemainTimes() > 0)
                    .findFirst()
                    .orElseThrow(() -> new BizException(ErrorCode.CARD_TIMES_NOT_ENOUGH, "没有可核销的权益项"));
        }
        if (cardItem.getRemainTimes() == null || cardItem.getRemainTimes() < times) {
            throw new BizException(ErrorCode.CARD_TIMES_NOT_ENOUGH,
                    "该权益项剩余 " + (cardItem.getRemainTimes() == null ? 0 : cardItem.getRemainTimes()) + " 次");
        }

        int beforeCard = card.getRemainTimes();
        int beforeItem = cardItem.getRemainTimes();

        // 卡与权益项同时扣减，SQL 层校验防超扣
        int cardRows = cardMapper.verifyTimes(card.getId(), times);
        int itemRows = itemMapper.verifyTimes(cardItem.getId(), times);
        if (cardRows == 0 || itemRows == 0) {
            throw new BizException(ErrorCode.CONCURRENT_MODIFY, "核销冲突，请重试");
        }

        // 核销记录（before/after 快照）
        var verifyLog = new com.company.member.modules.card.entity.CardVerifyLog();
        verifyLog.setVerifyNo(noGenerator.verifyNo());
        verifyLog.setMemberCardId(card.getId());
        verifyLog.setCardNo(card.getCardNo());
        verifyLog.setMemberCardItemId(cardItem.getId());
        verifyLog.setMemberId(card.getMemberId());
        verifyLog.setItemName(cardItem.getItemName());
        verifyLog.setVerifyTimes(times);
        verifyLog.setBeforeTimes(beforeItem);
        verifyLog.setAfterTimes(beforeItem - times);
        verifyLog.setStoreId(storeId);
        verifyLog.setOperatorId(operatorId);
        verifyLog.setVerifyType(1);
        verifyLog.setRemark(remark);
        verifyLog.setIdempotencyKey(idempotencyKey);
        verifyLog.setVerifiedAt(LocalDateTime.now());
        verifyLogMapperInsert(verifyLog);

        log.info("[次卡] 核销成功 cardNo={}, item={}, 次数={}, 卡剩余={}->{}, 项剩余={}->{}",
                cardNo, cardItem.getItemName(), times, beforeCard, beforeCard - times, beforeItem, beforeItem - times);

        Map<String, Object> result = new HashMap<>();
        result.put("verifyNo", verifyLog.getVerifyNo());
        result.put("cardNo", card.getCardNo());
        result.put("itemName", cardItem.getItemName());
        result.put("verifyTimes", times);
        result.put("beforeTimes", beforeItem);
        result.put("afterTimes", beforeItem - times);
        result.put("cardRemainTimes", beforeCard - times);
        result.put("verifiedAt", verifyLog.getVerifiedAt());
        return result;
    }

    private final com.company.member.modules.card.mapper.CardVerifyLogMapper verifyLogMapper;

    private void verifyLogMapperInsert(com.company.member.modules.card.entity.CardVerifyLog logRow) {
        verifyLogMapper.insert(logRow);
    }

    /* ==================================================================
     *  冻结 / 延期 / 退卡
     * ================================================================== */

    @Transactional(rollbackFor = Exception.class)
    public void freeze(String cardNo, boolean freeze, Long operatorId, String remark) {
        MemberCard card = cardMapper.selectByCardNo(cardNo);
        if (card == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "次卡不存在");
        }
        if (card.getStatus() != null
                && (card.getStatus() == MemberCard.STATUS_REFUNDED || card.getStatus() == MemberCard.STATUS_USED_UP)) {
            throw new BizException(ErrorCode.CARD_UNAVAILABLE, "当前状态不允许操作");
        }
        int status = freeze ? MemberCard.STATUS_FROZEN : MemberCard.STATUS_NORMAL;
        cardMapper.changeStatus(card.getId(), status, remark);
        itemMapper.changeStatusByCardId(card.getId(),
                freeze ? MemberCardItem.STATUS_FROZEN : MemberCardItem.STATUS_NORMAL);
        log.info("[次卡] {} cardNo={}, 操作人={}", freeze ? "冻结" : "解冻", cardNo, operatorId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void extend(String cardNo, int days, Long operatorId, String reason) {
        if (days <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "延期天数必须大于 0");
        }
        MemberCard card = cardMapper.selectByCardNo(cardNo);
        if (card == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "次卡不存在");
        }
        if (card.getStatus() != null && card.getStatus() == MemberCard.STATUS_REFUNDED) {
            throw new BizException(ErrorCode.CARD_UNAVAILABLE, "已退卡的卡不可延期");
        }
        if (card.getValidEnd() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该卡为永久有效，无需延期");
        }
        cardMapper.extendValidEnd(card.getId(), days);
        // 权益项同步延期
        for (MemberCardItem item : itemMapper.selectByCardId(card.getId())) {
            if (item.getValidEnd() != null) {
                item.setValidEnd(item.getValidEnd().plusDays(days));
                itemMapper.updateById(item);
            }
        }
        log.info("[次卡] 延期 cardNo={}, 天数={}, 原因={}, 操作人={}", cardNo, days, reason, operatorId);
    }

    /**
     * 退卡：按剩余比例折算退款金额，卡置为已退卡
     *
     * <p>折算规则：退款额 = 实付 × 剩余次数 / 总次数（向下取整到分）
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> refund(String cardNo, Long operatorId, String reason) {
        MemberCard card = cardMapper.selectByCardNo(cardNo);
        if (card == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "次卡不存在");
        }
        if (card.getStatus() != null && card.getStatus() == MemberCard.STATUS_REFUNDED) {
            throw new BizException(ErrorCode.CARD_UNAVAILABLE, "该卡已退卡");
        }
        CardProduct product = productMapper.selectById(card.getCardProductId());
        if (product != null && product.getAllowRefund() != null && product.getAllowRefund() == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "该卡产品不支持退款");
        }
        int total = card.getTotalTimes() == null ? 0 : card.getTotalTimes();
        int remain = card.getRemainTimes() == null ? 0 : card.getRemainTimes();
        BigDecimal refundAmount = BigDecimal.ZERO;
        if (total > 0) {
            refundAmount = MoneyUtil.mul(card.getPaidAmount(),
                    BigDecimal.valueOf(remain).divide(BigDecimal.valueOf(total), 6, java.math.RoundingMode.DOWN));
        }
        cardMapper.changeStatus(card.getId(), MemberCard.STATUS_REFUNDED,
                (reason == null ? "退卡" : reason) + "，退款 " + refundAmount + " 元");
        itemMapper.changeStatusByCardId(card.getId(), MemberCardItem.STATUS_EXPIRED);

        Map<String, Object> result = new HashMap<>();
        result.put("cardNo", cardNo);
        result.put("totalTimes", total);
        result.put("remainTimes", remain);
        result.put("usedTimes", total - remain);
        result.put("paidAmount", card.getPaidAmount());
        result.put("refundAmount", refundAmount);
        result.put("tip", "退款金额为按剩余次数折算的参考值，实际退款请走门店退款流程");
        log.warn("[次卡] 退卡 cardNo={}, 剩余 {}/{} 次, 折算退款={}, 操作人={}, 原因={}",
                cardNo, remain, total, refundAmount, operatorId, reason);
        return result;
    }

    /* ==================================================================
     *  查询
     * ================================================================== */

    public List<Map<String, Object>> memberCards(Long memberId, Integer status) {
        List<MemberCard> cards = cardMapper.selectList(new LambdaQueryWrapper<MemberCard>()
                .eq(MemberCard::getMemberId, memberId)
                .eq(status != null, MemberCard::getStatus, status)
                .orderByDesc(MemberCard::getId));
        List<Map<String, Object>> list = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (MemberCard c : cards) {
            // 惰性过期
            if (c.getStatus() != null && c.getStatus() == MemberCard.STATUS_NORMAL
                    && c.getValidEnd() != null && now.isAfter(c.getValidEnd())) {
                cardMapper.markExpired(c.getId());
                c.setStatus(MemberCard.STATUS_EXPIRED);
            }
            Map<String, Object> m = cardMap(c);
            List<MemberCardItem> items = itemMapper.selectByCardId(c.getId());
            m.put("items", items.stream().map(i -> {
                Map<String, Object> im = new HashMap<>();
                im.put("id", i.getId());
                im.put("itemName", i.getItemName());
                im.put("totalTimes", i.getTotalTimes());
                im.put("usedTimes", i.getUsedTimes());
                im.put("remainTimes", i.getRemainTimes());
                im.put("status", i.getStatus());
                im.put("validEnd", i.getValidEnd());
                return im;
            }).toList());
            list.add(m);
        }
        return list;
    }

    public Map<String, Object> cardDetail(String cardNo) {
        MemberCard card = cardMapper.selectByCardNo(cardNo);
        if (card == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "次卡不存在");
        }
        Map<String, Object> m = cardMap(card);
        m.put("items", itemMapper.selectByCardId(card.getId()));
        m.put("verifyLogs", verifyLogMapper.selectList(
                new LambdaQueryWrapper<com.company.member.modules.card.entity.CardVerifyLog>()
                        .eq(com.company.member.modules.card.entity.CardVerifyLog::getMemberCardId, card.getId())
                        .orderByDesc(com.company.member.modules.card.entity.CardVerifyLog::getId)));
        return m;
    }

    private Map<String, Object> cardMap(MemberCard c) {
        Map<String, Object> m = new HashMap<>();
        m.put("cardNo", c.getCardNo());
        m.put("cardName", c.getCardName());
        m.put("cardType", c.getCardType());
        m.put("memberId", c.getMemberId());
        m.put("paidAmount", c.getPaidAmount());
        m.put("totalTimes", c.getTotalTimes());
        m.put("usedTimes", c.getUsedTimes());
        m.put("remainTimes", c.getRemainTimes());
        m.put("validStart", c.getValidStart());
        m.put("validEnd", c.getValidEnd());
        m.put("status", c.getStatus());
        m.put("statusText", MemberCard.statusText(c.getStatus()));
        m.put("createdAt", c.getCreatedAt());
        return m;
    }

    /** 次卡过期任务 */
    @Transactional(rollbackFor = Exception.class)
    public int expireCards(int limit) {
        int total = 0;
        long lastId = 0L;
        while (true) {
            List<MemberCard> list = cardMapper.selectExpired(LocalDateTime.now(), lastId, limit);
            if (list.isEmpty()) {
                break;
            }
            for (MemberCard c : list) {
                total += cardMapper.markExpired(c.getId());
                itemMapper.expireByCardId(c.getId());
                lastId = c.getId();
            }
            if (list.size() < limit) {
                break;
            }
        }
        return total;
    }
}
