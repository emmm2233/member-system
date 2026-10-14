-- =====================================================================
--  V9: ShedLock 分布式锁表 + 限流/多实例相关系统参数
--
--  依据 docs/13-优化方案.md §P2-1 / §P2-2 / §P2-3。
--
--  背景：pom 里早已引入 shedlock-spring 与 shedlock-provider-jdbc-template，
--  5 个 Job 类的注释也都写着「多实例部署需启用 ShedLock」，
--  但**没有任何 @SchedulerLock、没有 @EnableSchedulerLock、也没有这张表** ——
--  等于系统事实上只能单实例部署，起第二个实例就会重复执行定时任务：
--    · 等级批量重算会跑两遍（无幂等保护，高峰期 CPU 翻倍）
--    · 生日礼/推荐奖励/消息重试会被两个实例同时扫描
--    · 支付超时关单与掉单补偿会对渠道重复发起请求
--
--  本迁移建表，代码侧加 @EnableSchedulerLock + 每个任务 @SchedulerLock。
-- =====================================================================

USE `member_system`;

-- ---------------------------------------------------------------------
-- 1. ShedLock 锁表
--    结构由 ShedLock 官方规定，字段名与类型不可改（库要求 name 为主键）。
--    两个坑都已避开：
--      · lock_until 不加 DEFAULT / ON UPDATE —— 否则「更新行的其他列」会顺带
--        把持锁到期时间自动往后推，锁被静默延长（官方 DDL 同样没有）。
--      · locked_by 不加 DEFAULT —— varchar 上的 DEFAULT CURRENT_TIMESTAMP 在
--        MySQL 8 直接报 Invalid default value；且它必须由持锁实例真实写入，
--        给个假默认值只会掩盖「谁持着锁」这个唯一的排查线索。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `shedlock` (
  `name`       varchar(64)  NOT NULL COMMENT '锁名(任务唯一标识)',
  `lock_until` timestamp(3) NOT NULL COMMENT '锁持有到该时刻(不得加 ON UPDATE)',
  `locked_at`  timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '加锁时刻',
  `locked_by`  varchar(255) NOT NULL COMMENT '持锁实例标识',
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务分布式锁(ShedLock)';

-- ---------------------------------------------------------------------
-- 2. 系统参数：多实例安全开关与限流
--
--    ⚠️ 缓存实现（caffeine / redis）**不放这里**：它必须在 Bean 创建时确定，
--       由 application.yml 的 member.cache.type 决定（见 CacheConfig）。
--       放进 m_sys_config 会造成「改了没生效」的误解 —— 那种配置比没有更糟。
--
--    为什么还需要 cache.multi-instance 这个开关：
--    caffeine 下有几样东西是**正确性数据**而不是性能数据 ——
--      幂等键、登录失败计数、业务序列号、微信 access_token。
--    多实例 + caffeine 会让这些静默失效（锁定失效、跨实例重复提交挡不住、
--    两个实例生成相同单号），而不会有任何报错。
--    置 true 后 CacheReadinessChecker 会在启动日志里给出明确告警 ——
--    让「以为是单实例、其实起了两个」这种情况至少能被喊出来。
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `m_sys_config`
(`config_key`,`config_value`,`config_name`,`config_group`,`value_type`,`editable`,`remark`,`created_at`,`updated_at`)
VALUES
('cache.multi-instance','false','多实例部署','cache','BOOLEAN',1,'置 true 后：若缓存仍是 caffeine，启动与就绪检测都会给出明确告警；缓存实现本身在 application.yml 的 member.cache.type',NOW(),NOW()),
('ratelimit.enabled','true','接口限流开关','ratelimit','BOOLEAN',1,'关闭后 @RateLimit 注解全部失效（仅用于压测）',NOW(),NOW()),
('ratelimit.mp.qps','20','会员端默认限流(次/秒)','ratelimit','NUMBER',1,'按会员维度计数；未登录按 IP 维度',NOW(),NOW()),
('ratelimit.open.qps','50','对外接口默认限流(次/秒)','ratelimit','NUMBER',1,'支付回调等 /api/open/** 接口',NOW(),NOW());
