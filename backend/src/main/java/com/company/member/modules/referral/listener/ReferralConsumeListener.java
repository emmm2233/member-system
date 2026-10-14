package com.company.member.modules.referral.listener;

import com.company.member.modules.cashier.entity.Order;
import com.company.member.modules.member.event.ConsumeCompletedEvent;
import com.company.member.modules.referral.service.ReferralService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 消费完成 → 推荐有礼首单结算
 *
 * <p>AFTER_COMMIT 是必须的：结算要判定「这是不是被推荐人的首单」，
 * 而首单判定的依据是 m_order 里已支付订单的条数。只有订单真正提交后
 * 这个判定才成立（在事务内查会漏掉当前这笔，导致首单被误判为第 0 笔）。
 *
 * <p>结算失败不影响收银——每日兜底任务 {@code ReferralService#settleByScan}
 * 会用订单数据反推补结算。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferralConsumeListener {

    private final ReferralService referralService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConsumeCompleted(ConsumeCompletedEvent event) {
        if (event.getMemberId() == null) {
            return;
        }
        try {
            boolean settled = referralService.settleIfEligible(
                    event.getMemberId(), event.getPaidAmount(), event.getOrderNo(), Order.TYPE_CONSUME);
            if (settled) {
                log.info("[事件] 推荐有礼结算完成 memberId={}, orderNo={}", event.getMemberId(), event.getOrderNo());
            }
        } catch (Exception e) {
            log.warn("[事件] 推荐有礼结算失败（将由定时任务兜底）memberId={}, orderNo={}, 原因={}",
                    event.getMemberId(), event.getOrderNo(), e.getMessage());
        }
    }
}
