package com.company.member.job;

import com.company.member.modules.card.service.MemberCardService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import com.company.member.modules.member.service.LevelEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 会员与次卡维护任务
 *
 * <ul>
 *   <li>次卡过期：每日 02:00</li>
 *   <li>等级评定（保级/降级）：每日 03:00</li>
 * </ul>
 *
 * <p>说明：单实例部署用 Spring @Scheduled；多实例部署需启用 ShedLock（依赖已引入）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberJob {

    private final MemberCardService cardService;
    private final LevelEvaluationService levelEvaluationService;

    /** 次卡过期检查：每日 02:00 */
    @Scheduled(cron = "${member.card.expire-cron:0 0 2 * * ?}")
    @SchedulerLock(name = "MemberJob#cardExpireJob",
            lockAtMostFor = "PT20M",
            lockAtLeastFor = "PT1M")
    public void cardExpireJob() {
        try {
            int expired = cardService.expireCards(500);
            log.info("[次卡任务] 过期处理完成，共 {} 张", expired);
        } catch (Exception e) {
            log.error("[次卡任务] 过期处理异常", e);
        }
    }

    /**
     * 等级评定：每日 03:00
     *
     * <p>实时升级在消费完成后已由业务链路触发，此任务用于：
     * ① 补齐历史数据；② 启用降级策略时的定期重新评定。
     */
    @Scheduled(cron = "${member.level.evaluate-cron:0 0 3 * * ?}")
    @SchedulerLock(name = "MemberJob#levelEvaluateJob",
            lockAtMostFor = "PT30M",
            lockAtLeastFor = "PT1M")
    public void levelEvaluateJob() {
        try {
            var summary = levelEvaluationService.evaluateAll(500);
            log.info("[等级任务] 评定完成：{}", summary);
        } catch (Exception e) {
            log.error("[等级任务] 评定异常", e);
        }
    }
}
