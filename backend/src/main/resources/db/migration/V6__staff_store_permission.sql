-- =====================================================================
--  V6: 员工与门店 —— 把「员工」从「登录账号」里彻底拆出来
--
--  背景:
--    m_staff 与 m_sys_user.staff_id 在第一轮建表时就已存在，
--    菜单 1101/1102/1103（门店管理/员工管理/业绩排行）也早已定义，
--    但一直没有对应的代码与页面 —— 三个菜单都是死链，员工表是无人维护的孤岛。
--
--    结果是「员工离职」和「账号停用」在界面上是同一件事，
--    而 m_order.staff_id（业绩归属）虽然一直在写，报表却按 cashier_id（账号）统计：
--    代班时业绩串人，离职后员工查不到自己的业绩。
--
--  本迁移只补权限码，不改表结构 —— 表早就拆好了。
-- =====================================================================

USE `member_system`;

-- ---------------------------------------------------------------------
-- 1. 按钮权限（menu_type=3）
--    staff:list  已在 1102 菜单上（无需新增）
--    store:list  已在 1101 菜单上（无需新增）
--    staff:perf  已在 1103 菜单上（无需新增）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `m_sys_menu`
(`id`,`parent_id`,`menu_type`,`menu_name`,`perm_code`,`route_path`,`component`,`icon`,`sort_no`,`visible`,`status`,`created_at`,`updated_at`)
VALUES
-- 员工管理
(1110,1102,3,'新增员工','staff:add',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(1111,1102,3,'编辑员工','staff:edit',NULL,NULL,NULL,2,0,1,NOW(),NOW()),
(1112,1102,3,'离职复职','staff:leave',NULL,NULL,NULL,3,0,1,NOW(),NOW()),
(1113,1102,3,'关联账号','staff:account',NULL,NULL,NULL,4,0,1,NOW(),NOW()),
-- 门店管理
(1120,1101,3,'新增门店','store:edit',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
-- 业绩排行：导出与查看明细沿用 staff:perf，此处补一个「全部门店」的越权保护位
(1130,1103,3,'查看全员业绩','staff:perf:all',NULL,NULL,NULL,1,0,1,NOW(),NOW());

-- ---------------------------------------------------------------------
-- 2. 授权
--    超级管理员：全部菜单
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `m_sys_role_menu` (`role_id`,`menu_id`,`created_at`)
SELECT 1, id, NOW() FROM `m_sys_menu`;

-- 运营主管：员工与门店全权限
INSERT IGNORE INTO `m_sys_role_menu` (`role_id`,`menu_id`,`created_at`)
SELECT r.id, m.id, NOW()
  FROM `m_sys_role` r
  JOIN `m_sys_menu` m ON m.id IN (1101,1102,1103,1110,1111,1112,1113,1120,1130)
 WHERE r.role_code = 'OPERATION';

-- 店长：看员工与业绩、可编辑本店员工，但不能新增门店、不能改员工账号关联
INSERT IGNORE INTO `m_sys_role_menu` (`role_id`,`menu_id`,`created_at`)
SELECT r.id, m.id, NOW()
  FROM `m_sys_role` r
  JOIN `m_sys_menu` m ON m.id IN (1101,1102,1103,1110,1111,1112)
 WHERE r.role_code = 'STORE_MANAGER';

-- 财务：只能看业绩排行（提成核算需要），不能碰员工与门店档案
INSERT IGNORE INTO `m_sys_role_menu` (`role_id`,`menu_id`,`created_at`)
SELECT r.id, m.id, NOW()
  FROM `m_sys_role` r
  JOIN `m_sys_menu` m ON m.id IN (1102,1103,1130)
 WHERE r.role_code = 'FINANCE';

-- 收银员：只能看到员工下拉（staff:list 不授予，靠 /api/admin/staff/options 的 order:add 权限）
--         这里不额外授权，保持最小权限。
