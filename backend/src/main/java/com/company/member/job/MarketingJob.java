package com.company.member.job;

import com.company.member.modules.marketing.service.CouponService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 券与积分维护任务
 *
 * <ul>
 *   <li>券过期：每日 01:30</li>
 *   <li>积分批次过期：每日 01:00（按 FIFO 批次扣减并写流水）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketingJob {

    private final CouponService couponService;
    private final com.company.member.modules.account.service.PointExpireService pointExpireService;

    /** 券过期：每日 01:30 */
    @Scheduled(cron = "${member.coupon.expire-cron:0 30 1 * * ?}")
    @SchedulerLock(name = "MarketingJob#couponExpireJob",
            lockAtMostFor = "PT20M",
            lockAtLeastFor = "PT1M")
    public void couponExpireJob() {
        try {
            int expired = couponService.expireBatch(500);
            log.info("[券任务] 过期处理完成，共 {} 张", expired);
        } catch (Exception e) {
            log.error("[券任务] 过期处理异常", e);
        }
    }

    /** 积分过期：每日 01:00 */
    @Scheduled(cron = "${member.points.expire-cron:0 0 1 * * ?}")
    @SchedulerLock(name = "MarketingJob#pointExpireJob",
            lockAtMostFor = "PT20M",
            lockAtLeastFor = "PT1M")
    public void pointExpireJob() {
        try {
            int expired = pointExpireService.expireBatches(500);
            log.info("[积分任务] 过期处理完成，共 {} 个批次", expired);
        } catch (Exception e) {
            log.error("[积分任务] 过期处理异常", e);
        }
    }
}
