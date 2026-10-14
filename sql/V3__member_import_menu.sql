-- =====================================================================
--  V3: 补充会员导入菜单与按钮权限
--  说明: 第一轮 V2 未包含"会员导入"的上传按钮权限，此处补齐；
--        同时修正会员导入菜单的权限码。使用 INSERT IGNORE 保证幂等。
-- =====================================================================

USE `member_system`;

-- 会员导入（已存在则忽略）
INSERT IGNORE INTO `m_sys_menu`
(`id`,`parent_id`,`menu_type`,`menu_name`,`perm_code`,`route_path`,`component`,`icon`,`sort_no`,`visible`,`status`,`created_at`,`updated_at`)
VALUES
(404,400,2,'会员导入','member:import','/member/import','member/import/index',NULL,4,1,1,NOW(),NOW()),
(405,400,2,'会员导出','member:export','/member/export','member/import/index',NULL,5,1,1,NOW(),NOW());

-- 授权：超管全部菜单
INSERT IGNORE INTO `m_sys_role_menu` (`role_id`,`menu_id`,`created_at`)
SELECT 1, id, NOW() FROM `m_sys_menu`;

-- 授权：运营主管与店长（会员管理范围）
INSERT IGNORE INTO `m_sys_role_menu` (`role_id`,`menu_id`,`created_at`)
SELECT r.id, 404, NOW() FROM `m_sys_role` r WHERE r.role_code IN ('OPERATION','STORE_MANAGER');
