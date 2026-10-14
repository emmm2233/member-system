package com.company.member.modules.birthday.job;

import com.company.member.modules.birthday.service.BirthdayGiftService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 生日礼定时任务
 *
 * <p>每日 08:00 发放（选在上班时间而非凌晨：生日祝福在凌晨推送体验很差，
 * 而且凌晨的推送容易被用户当成骚扰消息屏蔽）。
 *
 * <p>幂等由 (member_id, gift_year) 唯一索引保证，任务重复执行无害。
 * 单实例部署用 Spring @Scheduled；多实例部署需加 ShedLock（依赖已引入）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BirthdayJob {

    private final BirthdayGiftService birthdayGiftService;

    /** 生日礼发放：每日 08:00 */
    @Scheduled(cron = "${member.birthday.issue-cron:0 0 8 * * ?}")
    @SchedulerLock(name = "BirthdayJob#issueJob",
            lockAtMostFor = "PT30M",
            lockAtLeastFor = "PT1M")
    public void issueJob() {
        try {
            int issued = birthdayGiftService.issueBatch(2000);
            if (issued > 0) {
                log.info("[生日礼任务] 发放完成，共 {} 人", issued);
            }
        } catch (Exception e) {
            log.error("[生日礼任务] 发放异常", e);
        }
    }

    /** 失败/部分发放修复：每日 09:30 */
    @Scheduled(cron = "${member.birthday.repair-cron:0 30 9 * * ?}")
    @SchedulerLock(name = "BirthdayJob#repairJob",
            lockAtMostFor = "PT20M",
            lockAtLeastFor = "PT1M")
    public void repairJob() {
        try {
            int done = birthdayGiftService.repairFailed(200);
            if (done > 0) {
                log.info("[生日礼任务] 修复完成，共 {} 条", done);
            }
        } catch (Exception e) {
            log.error("[生日礼任务] 修复异常", e);
        }
    }
}
