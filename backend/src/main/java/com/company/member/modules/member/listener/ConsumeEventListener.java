package com.company.member.modules.member.listener;

import com.company.member.modules.member.event.ConsumeCompletedEvent;
import com.company.member.modules.member.service.LevelEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 消费完成 → 等级实时评定
 *
 * <p>使用 {@link TransactionalPhase#AFTER_COMMIT} 保证：
 * <ul>
 *   <li>收银主链路不被等级评定拖慢（评定失败不影响订单成立）</li>
 *   <li>只有订单真正落库后才评定，避免回滚后误升级</li>
 * </ul>
 * 评定失败仅记录日志，由每日 03:00 的定时任务兜底补齐。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumeEventListener {

    private final LevelEvaluationService levelEvaluationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConsumeCompleted(ConsumeCompletedEvent event) {
        if (event.getMemberId() == null) {
            return;
        }
        try {
            var result = levelEvaluationService.evaluate(event.getMemberId());
            if (Boolean.TRUE.equals(result.get("changed"))) {
                log.info("[事件] 消费后等级变更 memberId={}, orderNo={}, {}",
                        event.getMemberId(), event.getOrderNo(), result.get("action"));
            }
        } catch (Exception e) {
            log.warn("[事件] 等级评定失败（将由定时任务兜底）memberId={}, orderNo={}, 原因={}",
                    event.getMemberId(), event.getOrderNo(), e.getMessage());
        }
    }
}
