-- =====================================================================
--  V7: 操作日志索引 + 幂等键补充说明
--
--  背景（docs/13-优化方案.md §P0-1 / §P0-2）:
--    1. m_op_log 此前从未被写入（0 行）—— 有查询接口、无写入方。
--       现在 OpAuditAspect 采用 opt-out 策略：控制器上所有写映射默认留痕，
--       只有显式 @NoAudit 的接口才跳过。表会开始真实增长，需要索引支撑查询。
--    2. m_op_log 在 V1 里只建了主键，没有 created_at / user_id / module 索引，
--       「操作日志」页按时间倒序 + 模块 + 操作人筛选会全表扫。
--
--  索引选择依据 OpLogQueryService 的实际查询形态:
--    WHERE module=? AND op_type=? AND user_id=? AND created_at>=? AND created_at<?
--    ORDER BY id DESC LIMIT ? OFFSET ?
-- =====================================================================

USE `member_system`;

-- ---------------------------------------------------------------------
-- 1. 操作日志索引
--    单条 INSERT 的表，索引多一个就多一次维护成本，因此只建真正会用到的四个。
-- ---------------------------------------------------------------------

-- 按时间倒序翻页（默认视图，无筛选条件）
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema='member_system' AND table_name='m_op_log' AND index_name='idx_created') = 0,
    'ALTER TABLE `m_op_log` ADD INDEX `idx_created` (`created_at`)',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 「某人最近做了什么」——排查越权/误操作时最常用
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema='member_system' AND table_name='m_op_log' AND index_name='idx_user_time') = 0,
    'ALTER TABLE `m_op_log` ADD INDEX `idx_user_time` (`user_id`,`created_at`)',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 「资产账务模块最近的调整」——按模块复核
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema='member_system' AND table_name='m_op_log' AND index_name='idx_module_time') = 0,
    'ALTER TABLE `m_op_log` ADD INDEX `idx_module_time` (`module`,`op_type`,`created_at`)',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 「这笔订单/这张券被谁动过」——按业务单号反查
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema='member_system' AND table_name='m_op_log' AND index_name='idx_biz_no') = 0,
    'ALTER TABLE `m_op_log` ADD INDEX `idx_biz_no` (`biz_no`)',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 2. 系统参数：操作日志保留天数（0 = 永久保留）
--
--    审计日志与业务流水不同：业务流水不能删，审计日志可以按合规要求定期归档。
--    默认 0（永久）—— 宁可占空间，也不要因为默认值把审计记录悄悄清掉。
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `m_sys_config`
(`config_key`,`config_value`,`config_name`,`config_group`,`value_type`,`editable`,`remark`,`created_at`,`updated_at`)
VALUES
('audit.op_log_keep_days','0','操作日志保留天数','audit','NUMBER',1,'0 表示永久保留；设置后由运维任务清理超期记录',NOW(),NOW());
