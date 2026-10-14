package com.company.member.modules.referral.job;

import com.company.member.modules.referral.service.ReferralService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 推荐有礼补偿任务
 *
 * <p>三道兜底，覆盖事件驱动模型的全部失败面：
 * <ol>
 *   <li><b>04:10 兜底结算</b>：扫已绑定未结算的关系，用订单数据反推是否已达成条件
 *       （覆盖事件丢失：进程重启、监听器异常、事务提交后崩溃）</li>
 *   <li><b>04:20 奖励重试</b>：重发「待发放/发放失败」的奖励
 *       （覆盖券模板临时下架、账户被冻结等瞬时故障）</li>
 *   <li><b>04:30 奖励对账</b>：已结算但奖励记录缺失的关系补建
 *       （覆盖「结算标记已提交、进程在发奖前挂掉」的极端窗口）</li>
 * </ol>
 *
 * <p>单实例部署用 Spring @Scheduled；多实例部署需加 ShedLock（依赖已引入）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferralJob {

    private final ReferralService referralService;

    /** 兜底结算：每日 04:10 */
    @Scheduled(cron = "${member.referral.settle-cron:0 10 4 * * ?}")
    @SchedulerLock(name = "ReferralJob#settleScanJob",
            lockAtMostFor = "PT20M",
            lockAtLeastFor = "PT1M")
    public void settleScanJob() {
        try {
            int settled = referralService.settleByScan(500);
            if (settled > 0) {
                log.info("[推荐任务] 兜底结算完成，共 {} 笔", settled);
            }
        } catch (Exception e) {
            log.error("[推荐任务] 兜底结算异常", e);
        }
    }

    /** 奖励重试：每日 04:20 */
    @Scheduled(cron = "${member.referral.reward-retry-cron:0 20 4 * * ?}")
    @SchedulerLock(name = "ReferralJob#rewardRetryJob",
            lockAtMostFor = "PT20M",
            lockAtLeastFor = "PT1M")
    public void rewardRetryJob() {
        try {
            int done = referralService.retryPendingRewards(200);
            if (done > 0) {
                log.info("[推荐任务] 奖励重试完成，共 {} 笔", done);
            }
        } catch (Exception e) {
            log.error("[推荐任务] 奖励重试异常", e);
        }
    }

    /** 奖励对账：每日 04:30 */
    @Scheduled(cron = "${member.referral.reconcile-cron:0 30 4 * * ?}")
    @SchedulerLock(name = "ReferralJob#reconcileJob",
            lockAtMostFor = "PT20M",
            lockAtLeastFor = "PT1M")
    public void reconcileJob() {
        try {
            int fixed = referralService.reconcileSettled(200);
            if (fixed > 0) {
                log.warn("[推荐任务] 对账发现并补建 {} 笔缺失奖励", fixed);
            }
        } catch (Exception e) {
            log.error("[推荐任务] 奖励对账异常", e);
        }
    }
}
