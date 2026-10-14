package com.company.member.common.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 定时任务分布式锁（ShedLock）
 *
 * <p><b>为什么必须有</b>：pom 里早就引了 ShedLock、5 个 Job 类的注释也都写着
 * 「多实例部署需启用 ShedLock」，但此前既没有 {@code @EnableSchedulerLock}
 * 也没有 {@code @SchedulerLock} —— 系统事实上只能单实例部署。
 * 起第二个实例的后果不是「慢一点」，而是：
 * <ul>
 *   <li>等级批量重算跑两遍（无幂等保护，高峰期 CPU 翻倍）</li>
 *   <li>生日礼发放 / 推荐奖励重试 / 消息重试被两个实例同时扫描</li>
 *   <li>支付超时关单与掉单补偿对渠道重复发请求</li>
 * </ul>
 * 有些任务靠条件 UPDATE 或唯一索引兜住了重复，但那是「碰巧安全」，
 * 不是设计保证 —— 锁的意义是让所有任务都安全，而不是逐个论证哪个安全。
 *
 * <p><b>两个关键配置</b>：
 * <ol>
 *   <li>{@code usingDbTime()} —— 用<b>数据库时间</b>判定锁是否过期。
 *       默认用各实例的本地时钟，实例间时钟漂移会让锁提前失效或永不释放，
 *       而这是分布式锁最经典的翻车方式。</li>
 *   <li>{@code defaultLockAtMostFor = PT30M} —— 持锁上限兜底：
 *       实例崩溃时锁不会永久占住（否则所有任务从此不再执行）。</li>
 * </ol>
 *
 * <p>锁的粒度由各 Job 上的 {@code @SchedulerLock(name=...)} 决定：
 * 同一个 name 在集群里同一时刻只有一个实例能执行。
 *
 * <p><b>运维必读：不要直接 DELETE {@code shedlock} 的行。</b>
 * ShedLock 的存储型 provider 内部有一份「这条锁记录我已经插入过」的内存记忆，
 * 之后每次加锁只走条件 UPDATE（{@code WHERE name=? AND lock_until<=now}）。
 * 锁行被表外删除后，UPDATE 永远匹配不到行 →
 * 该任务会被<b>永久判定为「已被锁住」而静默跳过，直到应用重启</b>，且不产生任何错误日志。
 * （这不是猜测：round10 自检就是通过「表外改锁 + 删锁」实测出来的，见 docs/14 §4.1。）
 * <p>正确做法：
 * <ul>
 *   <li>要让某个任务尽快恢复，把 {@code lock_until} 改成过去时刻即可（会走 UPDATE 路径，能匹配）；</li>
 *   <li>确实删过行，就调一次 {@code POST /api/admin/system/scheduler-locks/reset-cache}
 *       清掉那份内存记忆，不必重启；</li>
 *   <li>用 {@code GET /api/admin/system/scheduler-locks} 看谁持着锁、锁是否已过期。</li>
 * </ul>
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerLockConfig {

    /**
     * 基于 JDBC 的锁提供者（表 {@code shedlock}，见 Flyway V9）
     *
     * <p>选 JDBC 而不是 Redis：定时任务的锁需要的是「持久、严格互斥、可排查」，
     * 数据库天然满足；而且它不依赖 Redis 是否已就绪 ——
     * 单实例阶段（无 Redis）就能先把多实例安全性准备好。
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName("shedlock")
                        .usingDbTime()
                        .build());
    }
}
