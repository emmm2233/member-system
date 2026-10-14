-- =====================================================================
--  V8: 性能索引 + 成长值流水补齐幂等键
--
--  依据 docs/13-优化方案.md §P1-1 / §P1-2，以及实施第 1 批时暴露出的
--  三张资金流水表结构不一致问题（见第 3 段）。
--
--  ⚠️ 第 4 段含 STORED 生成列，在大表上会触发表重建（锁表）。
--     生产环境请在低峰期执行；若 m_member 已达百万行，
--     建议用 gh-online-schema-change / pt-online-schema-change 单独执行该段 ALTER。
-- =====================================================================

USE `member_system`;

-- ---------------------------------------------------------------------
-- 1. m_order：业绩按 paid_at（收款时间）归集，但索引全以 created_at 结尾
--
--    现有索引：idx_staff_time (staff_id, created_at)
--              idx_status_time (status, created_at)
--    而业绩查询是：
--      SELECT ... FROM m_staff s LEFT JOIN m_order o
--        ON o.staff_id = s.id AND o.status IN (3,4,5)
--       AND o.paid_at >= ? AND o.paid_at < ?
--    paid_at 不在任何索引里 → 排行查询全表扫 m_order。
--
--    为什么建 (staff_id, paid_at) 而不是 (status, paid_at)：
--    驱动表是 m_staff，每行按 staff_id 等值查找订单，再在 paid_at 上做范围扫描；
--    status 留在索引外过滤即可（回表行数已被 paid_at 范围压得很小）。
--    这条索引同时服务「单员工业绩明细」与「业绩排行」两个场景。
-- ---------------------------------------------------------------------
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema='member_system' AND table_name='m_order' AND index_name='idx_staff_paid') = 0,
    'ALTER TABLE `m_order` ADD INDEX `idx_staff_paid` (`staff_id`,`paid_at`)',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 「某段时间全店业绩」这类不按员工分组的统计用得上
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema='member_system' AND table_name='m_order' AND index_name='idx_store_paid') = 0,
    'ALTER TABLE `m_order` ADD INDEX `idx_store_paid` (`store_id`,`paid_at`)',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 2. m_payment_order：支付对账按「已支付未入账」扫描，补状态+时间索引
-- ---------------------------------------------------------------------
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema='member_system' AND table_name='m_payment_order' AND index_name='idx_status_created') = 0,
    'ALTER TABLE `m_payment_order` ADD INDEX `idx_status_created` (`status`,`created_at`)',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 3. m_growth_flow：补齐幂等键（三张资金流水表曾经不一致）
--
--    m_balance_flow 与 m_point_flow 都有
--    idempotency_key + uk_idem(idempotency_key, biz_type)，
--    唯独 m_growth_flow **两者都没有** —— 而成长值同样是资产（决定等级与权益）。
--
--    后果有两层：
--      a) 纯成长值调整（不涉余额与积分）重复提交会真的加两次；
--      b) AccountService 的幂等读侧检查查这一列时报
--         SQLSyntaxErrorException: Unknown column 'idempotency_key'
--         —— 整笔结算回滚。第 1 批把「三张流水表统一查幂等」时被自检抓出来。
--
--    本段把三张表拉平。表中现有数据为 0 行（成长值此前从未真正发放过），
--    加唯一索引无历史冲突风险；即便有数据，NULL 也不参与唯一性约束。
-- ---------------------------------------------------------------------
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema='member_system' AND table_name='m_growth_flow' AND column_name='idempotency_key') = 0,
    'ALTER TABLE `m_growth_flow` ADD COLUMN `idempotency_key` varchar(64) NULL COMMENT ''幂等键(与余额/积分流水同构)'' AFTER `biz_no`',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema='member_system' AND table_name='m_growth_flow' AND index_name='uk_idem') = 0,
    'ALTER TABLE `m_growth_flow` ADD UNIQUE KEY `uk_idem` (`idempotency_key`,`biz_type`)',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema='member_system' AND table_name='m_growth_flow' AND index_name='idx_biz') = 0,
    'ALTER TABLE `m_growth_flow` ADD INDEX `idx_biz` (`biz_no`)',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema='member_system' AND table_name='m_growth_flow' AND index_name='idx_member_time') = 0,
    'ALTER TABLE `m_growth_flow` ADD INDEX `idx_member_time` (`member_id`,`occur_time`)',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 4. m_member：生日扫描可索引化
--
--    原查询：WHERE deleted=0 AND status=1 AND DATE_FORMAT(birthday,'%m-%d') = ?
--    函数作用在列上 → idx_birthday 完全用不上，每天 08:00 全表扫一次 m_member。
--
--    解决：加 STORED 生成列 birthday_md（'MM-dd'）+ 复合索引，查询改为等值匹配。
--    语义完全等价（只取月日、忽略年份）——「建档年份常填 1900」这个业务坑
--    在生成列上依然成立，因为生成列本来就只看月日。
-- ---------------------------------------------------------------------
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema='member_system' AND table_name='m_member' AND column_name='birthday_md') = 0,
    'ALTER TABLE `m_member` ADD COLUMN `birthday_md` char(5) GENERATED ALWAYS AS (DATE_FORMAT(`birthday`, ''%m-%d'')) STORED COMMENT ''生日月日(生成列,供生日礼扫描走索引)''',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema='member_system' AND table_name='m_member' AND index_name='idx_birthday_md') = 0,
    'ALTER TABLE `m_member` ADD INDEX `idx_birthday_md` (`status`,`deleted`,`birthday_md`)',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
