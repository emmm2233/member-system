package com.company.member.job;

import com.company.member.modules.marketing.service.CouponService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import com.company.member.modules.payment.entity.PaymentOrder;
import com.company.member.modules.payment.service.PayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 支付相关定时任务（预留：小程序在线支付）
 *
 * <ul>
 *   <li>{@code PaySyncJob}：掉单补偿 —— 每 30 秒查待支付/支付中支付单，渠道返回成功则补入账</li>
 *   <li>{@code PayCloseJob}：超时关单 —— 每分钟关闭过期支付单</li>
 *   <li>{@code CouponUnlockJob}：释放超时的券锁定 —— 每 5 分钟</li>
 * </ul>
 *
 * <p>说明：多实例部署时应接入 ShedLock（已在 pom 中引入依赖）保证同一任务只在一个实例执行。
 * 本期单实例部署，先用 Spring @Scheduled。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentJob {

    private final PayService payService;
    private final CouponService couponService;

    /** 掉单补偿：每 30 秒 */
    @Scheduled(fixedDelayString = "${member.pay.sync-interval-ms:30000}")
    @SchedulerLock(name = "PaymentJob#paySyncJob",
            lockAtMostFor = "PT25S",
            lockAtLeastFor = "PT5S")
    public void paySyncJob() {
        try {
            List<PaymentOrder> pending = payService.pendingForSync(50);
            if (pending.isEmpty()) {
                return;
            }
            int synced = 0;
            for (PaymentOrder po : pending) {
                try {
                    if (payService.sync(po.getPaymentNo())) {
                        synced++;
                    }
                } catch (Exception e) {
                    log.warn("[支付任务] 查单失败 paymentNo={}, 原因={}", po.getPaymentNo(), e.getMessage());
                }
            }
            if (synced > 0) {
                log.warn("[支付任务] 掉单补偿完成，补入账 {} 笔", synced);
            }
        } catch (Exception e) {
            log.error("[支付任务] 掉单补偿异常", e);
        }
    }

    /** 超时关单：每分钟 */
    @Scheduled(fixedDelayString = "${member.pay.close-interval-ms:60000}")
    @SchedulerLock(name = "PaymentJob#payCloseJob",
            lockAtMostFor = "PT50S",
            lockAtLeastFor = "PT5S")
    public void payCloseJob() {
        try {
            List<PaymentOrder> timeout = payService.timeoutOrders(100);
            for (PaymentOrder po : timeout) {
                try {
                    payService.close(po.getPaymentNo());
                    log.info("[支付任务] 超时关单 paymentNo={}", po.getPaymentNo());
                } catch (Exception e) {
                    log.warn("[支付任务] 关单失败 paymentNo={}, 原因={}", po.getPaymentNo(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[支付任务] 超时关单异常", e);
        }
    }

    /** 释放超时券锁定：每 5 分钟 */
    @Scheduled(fixedDelayString = "${member.coupon.unlock-interval-ms:300000}")
    @SchedulerLock(name = "PaymentJob#couponUnlockJob",
            lockAtMostFor = "PT4M",
            lockAtLeastFor = "PT30S")
    public void couponUnlockJob() {
        try {
            int released = couponService.unlockTimeout(5);
            if (released > 0) {
                log.info("[券任务] 释放超时锁定 {} 张", released);
            }
        } catch (Exception e) {
            log.error("[券任务] 释放锁定异常", e);
        }
    }
}
