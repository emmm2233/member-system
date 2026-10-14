package com.company.member.modules.message.job;

import com.company.member.modules.message.service.MessageService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 消息补偿任务
 *
 * <p>失败消息按指数退避重试（最多 5 次）。之所以需要它：
 * <ul>
 *   <li>access_token 过期、微信接口抖动这类瞬时故障</li>
 *   <li>订阅额度当时用尽、运营后来补配了模板</li>
 *   <li>业务事务已提交但进程在发消息前被重启</li>
 * </ul>
 *
 * <p>单实例部署用 Spring @Scheduled；多实例部署需加 ShedLock（依赖已引入）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageJob {

    private final MessageService messageService;

    /** 每 5 分钟补偿一次 */
    @Scheduled(cron = "${member.message.retry-cron:0 */5 * * * ?}")
    @SchedulerLock(name = "MessageJob#retryJob",
            lockAtMostFor = "PT4M",
            lockAtLeastFor = "PT30S")
    public void retryJob() {
        try {
            int done = messageService.retryPending(200);
            if (done > 0) {
                log.info("[消息任务] 重试完成，共处理 {} 条", done);
            }
        } catch (Exception e) {
            log.error("[消息任务] 重试异常", e);
        }
    }
}
