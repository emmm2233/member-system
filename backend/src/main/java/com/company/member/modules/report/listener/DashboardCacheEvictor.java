package com.company.member.modules.report.listener;

import com.company.member.modules.member.event.ConsumeCompletedEvent;
import com.company.member.modules.report.service.DashboardCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 消费完成 → 看板缓存失效
 *
 * <p>看板缓存 TTL 默认 600 秒。如果没有主动失效，收银员结完账回看板会看到
 * <b>十分钟前的数字</b> —— 这是运营最容易投诉的一类问题（"我刚收的钱没算进去"）。
 *
 * <p>AFTER_COMMIT 保证只有订单真正落库后才清缓存：若事务回滚，看板不该被无谓地清空。
 * 失效失败只记日志 —— 缓存脏一会儿远好过因为清缓存失败而影响收银。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardCacheEvictor {

    private final DashboardCache dashboardCache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConsumeCompleted(ConsumeCompletedEvent event) {
        try {
            dashboardCache.evictAll();
        } catch (Exception e) {
            log.warn("[看板] 消费后缓存失效失败（TTL 到点会自愈）：{}", e.getMessage());
        }
    }
}
