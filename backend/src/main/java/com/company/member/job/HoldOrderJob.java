package com.company.member.job;

import com.company.member.modules.cashier.service.HoldOrderService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 挂单清理任务
 *
 * <p>每 10 分钟扫描一次，把超过保留时长（默认 24 小时）的挂单置为已取消，
 * 避免挂单长期堆积影响收银列表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldOrderJob {

    private final HoldOrderService holdOrderService;

    @Scheduled(fixedDelayString = "${member.hold.clean-interval-ms:600000}")
    @SchedulerLock(name = "HoldOrderJob#cleanExpiredHolds",
            lockAtMostFor = "PT4M",
            lockAtLeastFor = "PT30S")
    public void cleanExpiredHolds() {
        try {
            int cleaned = holdOrderService.cleanExpired(200);
            if (cleaned > 0) {
                log.info("[挂单任务] 超时清理 {} 单", cleaned);
            }
        } catch (Exception e) {
            log.error("[挂单任务] 清理异常", e);
        }
    }
}
