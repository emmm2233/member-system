-- =====================================================================
--  V2: 菜单与权限初始化 + 角色授权
--  说明: V1 建表并初始化了门店/员工/等级/参数等，但未含菜单树。
--        本脚本补齐 m_sys_menu 与 m_sys_role_menu，使 RBAC 真正可用。
--  幂等: 使用固定 id + INSERT IGNORE，可重复执行
-- =====================================================================

USE `member_system`;

-- ---------------------------------------------------------------------
-- 1. 菜单与权限码（menu_type: 1目录 2菜单 3按钮）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `m_sys_menu` (`id`,`parent_id`,`menu_type`,`menu_name`,`perm_code`,`route_path`,`component`,`icon`,`sort_no`,`visible`,`status`,`created_at`,`updated_at`) VALUES
-- 工作台
(100,0,2,'工作台','report:view','/dashboard','dashboard/index','DataLine',1,1,1,NOW(),NOW()),

-- 收银台（全屏）
(200,0,2,'收银台','order:add','/cashier','cashier/index','ShoppingCart',2,1,1,NOW(),NOW()),

-- 订单管理
(300,0,1,'订单管理',NULL,NULL,NULL,'List',3,1,1,NOW(),NOW()),
(301,300,2,'消费订单','order:list','/order/consume','order/consume/index',NULL,1,1,1,NOW(),NOW()),
(302,300,2,'充值记录','account:flow:list','/order/recharge','order/recharge/index',NULL,2,1,1,NOW(),NOW()),
(303,300,2,'退单管理','order:refund:list','/order/refund','order/refund/index',NULL,3,1,1,NOW(),NOW()),
(304,300,2,'支付单管理','payment:list','/order/payment','order/payment/index',NULL,4,1,1,NOW(),NOW()),
(305,300,2,'交接班记录','shift:summary','/order/shift','order/shift/index',NULL,5,1,1,NOW(),NOW()),
(306,300,2,'次卡核销记录','card:list','/order/card-verify','card/verifyLog/index',NULL,6,1,1,NOW(),NOW()),

-- 会员管理
(400,0,1,'会员管理',NULL,NULL,NULL,'User',4,1,1,NOW(),NOW()),
(401,400,2,'会员列表','member:list','/member/list','member/list/index',NULL,1,1,1,NOW(),NOW()),
(402,400,2,'会员标签','member:tag','/member/tag','member/tag/index',NULL,2,1,1,NOW(),NOW()),
(403,400,2,'会员导入','member:import','/member/import','member/import/index',NULL,3,1,1,NOW(),NOW()),
-- 会员按钮权限
(490,401,3,'会员查询','member:query',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(491,401,3,'新增会员','member:add',NULL,NULL,NULL,2,0,1,NOW(),NOW()),
(492,401,3,'编辑会员','member:edit',NULL,NULL,NULL,3,0,1,NOW(),NOW()),
(493,401,3,'注销会员','member:remove',NULL,NULL,NULL,4,0,1,NOW(),NOW()),
(494,401,3,'调整等级','member:level',NULL,NULL,NULL,5,0,1,NOW(),NOW()),
(495,401,3,'会员合并','member:merge',NULL,NULL,NULL,6,0,1,NOW(),NOW()),
(496,401,3,'导出会员','member:export',NULL,NULL,NULL,7,0,1,NOW(),NOW()),

-- 资产账务
(500,0,1,'资产账务',NULL,NULL,NULL,'Wallet',5,1,1,NOW(),NOW()),
(501,500,2,'资产总览','account:query','/account/overview','account/overview/index',NULL,1,1,1,NOW(),NOW()),
(502,500,2,'余额流水','account:flow:list','/account/balance-flow','account/balanceFlow/index',NULL,2,1,1,NOW(),NOW()),
(503,500,2,'积分流水','account:flow:list','/account/point-flow','account/pointFlow/index',NULL,3,1,1,NOW(),NOW()),
(504,500,2,'资产调整','account:adjust','/account/adjust','account/adjust/index',NULL,4,1,1,NOW(),NOW()),
(505,500,2,'对账中心','account:reconcile','/account/reconcile','account/reconcile/index',NULL,5,1,1,NOW(),NOW()),
-- 资产按钮权限
(590,501,3,'会员充值','account:recharge',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(591,501,3,'充值撤销','account:revoke',NULL,NULL,NULL,2,0,1,NOW(),NOW()),
(592,501,3,'新增/编辑充值方案','account:plan',NULL,NULL,NULL,3,0,1,NOW(),NOW()),

-- 收银相关按钮权限（挂在 200 收银台菜单下）
(290,200,3,'发起退单','order:refund:apply',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(291,200,3,'审批退单','order:refund:approve',NULL,NULL,NULL,2,0,1,NOW(),NOW()),
(292,200,3,'订单查询','order:query',NULL,NULL,NULL,3,0,1,NOW(),NOW()),
(293,200,3,'交班结账','shift:close',NULL,NULL,NULL,4,0,1,NOW(),NOW()),

-- 营销中心
(600,0,1,'营销中心',NULL,NULL,NULL,'Discount',6,1,1,NOW(),NOW()),
(601,600,2,'优惠券模板','coupon:tpl:list','/marketing/coupon-tpl','marketing/couponTpl/index',NULL,1,1,1,NOW(),NOW()),
(602,600,2,'会员券查询','coupon:list','/marketing/coupon','marketing/coupon/index',NULL,2,1,1,NOW(),NOW()),
(603,600,2,'发券管理','coupon:grant','/marketing/grant','marketing/grant/index',NULL,3,1,1,NOW(),NOW()),
(604,600,2,'券核销记录','coupon:list','/marketing/coupon-use','marketing/couponUse/index',NULL,4,1,1,NOW(),NOW()),
(605,600,2,'推荐有礼','referral:list','/marketing/referral','marketing/referral/index',NULL,5,1,1,NOW(),NOW()),
-- 营销按钮权限
(690,601,3,'新增券模板','coupon:tpl:add',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(691,601,3,'编辑券模板','coupon:tpl:edit',NULL,NULL,NULL,2,0,1,NOW(),NOW()),
(692,602,3,'作废券','coupon:void',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(693,602,3,'核销券','coupon:verify',NULL,NULL,NULL,2,0,1,NOW(),NOW()),
(694,605,3,'推荐规则配置','referral:config',NULL,NULL,NULL,1,0,1,NOW(),NOW()),

-- 次卡管理
(700,0,1,'次卡管理',NULL,NULL,NULL,'Ticket',7,1,1,NOW(),NOW()),
(701,700,2,'卡产品','card:list','/card/product','card/product/index',NULL,1,1,1,NOW(),NOW()),
(702,700,2,'会员持卡','card:list','/card/member','card/member/index',NULL,2,1,1,NOW(),NOW()),
(703,700,2,'售卡','card:sell','/card/sell','card/sell/index',NULL,3,1,1,NOW(),NOW()),
(704,700,2,'核销记录','card:list','/card/verify-log','card/verifyLog/index',NULL,4,1,1,NOW(),NOW()),
(790,701,3,'新增卡产品','card:add',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(791,701,3,'编辑卡产品','card:edit',NULL,NULL,NULL,2,0,1,NOW(),NOW()),
(792,702,3,'次卡核销','card:verify',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(793,702,3,'次卡退款','card:refund',NULL,NULL,NULL,2,0,1,NOW(),NOW()),

-- 商品管理
(800,0,1,'商品管理',NULL,NULL,NULL,'Goods',8,1,1,NOW(),NOW()),
(801,800,2,'商品/服务','product:list','/product/list','product/list/index',NULL,1,1,1,NOW(),NOW()),
(802,800,2,'商品分类','product:query','/product/category','product/category/index',NULL,2,1,1,NOW(),NOW()),
(803,800,2,'充值方案','account:plan','/product/recharge-plan','product/rechargePlan/index',NULL,3,1,1,NOW(),NOW()),
(890,801,3,'新增商品','product:add',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(891,801,3,'编辑商品','product:edit',NULL,NULL,NULL,2,0,1,NOW(),NOW()),
(892,801,3,'删除商品','product:remove',NULL,NULL,NULL,3,0,1,NOW(),NOW()),

-- 等级与积分
(900,0,1,'等级与积分',NULL,NULL,NULL,'Medal',9,1,1,NOW(),NOW()),
(901,900,2,'等级设置','level:list','/level/list','level/list/index',NULL,1,1,1,NOW(),NOW()),
(902,900,2,'积分规则','points:config','/level/points-rule','level/pointsRule/index',NULL,2,1,1,NOW(),NOW()),
(903,900,2,'积分商品','points:list','/level/points-goods','level/pointsGoods/index',NULL,3,1,1,NOW(),NOW()),
(990,901,3,'新增等级','level:add',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(991,901,3,'编辑等级','level:edit',NULL,NULL,NULL,2,0,1,NOW(),NOW()),
(992,901,3,'等级重算','level:evaluate',NULL,NULL,NULL,3,0,1,NOW(),NOW()),

-- 数据看板
(1000,0,1,'数据看板',NULL,NULL,NULL,'TrendCharts',10,1,1,NOW(),NOW()),
(1001,1000,2,'经营总览','report:view','/report/overview','report/overview/index',NULL,1,1,1,NOW(),NOW()),
(1002,1000,2,'会员分析','report:view','/report/member','report/member/index',NULL,2,1,1,NOW(),NOW()),
(1003,1000,2,'消费分析','report:view','/report/consume','report/consume/index',NULL,3,1,1,NOW(),NOW()),
(1004,1000,2,'储值负债','report:finance','/report/liability','report/liability/index',NULL,4,1,1,NOW(),NOW()),
(1005,1000,2,'报表导出','report:export','/report/export','report/export/index',NULL,5,1,1,NOW(),NOW()),

-- 门店与员工
(1100,0,1,'门店与员工',NULL,NULL,NULL,'OfficeBuilding',11,1,1,NOW(),NOW()),
(1101,1100,2,'门店管理','store:list','/store/list','store/list/index',NULL,1,1,1,NOW(),NOW()),
(1102,1100,2,'员工管理','staff:list','/store/staff','store/staff/index',NULL,2,1,1,NOW(),NOW()),
(1103,1100,2,'业绩排行','staff:perf','/store/performance','store/performance/index',NULL,3,1,1,NOW(),NOW()),
(1190,1101,3,'新增门店','store:add',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(1191,1101,3,'编辑门店','store:edit',NULL,NULL,NULL,2,0,1,NOW(),NOW()),
(1192,1102,3,'新增员工','staff:add',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(1193,1102,3,'编辑员工','staff:edit',NULL,NULL,NULL,2,0,1,NOW(),NOW()),

-- 系统管理
(1200,0,1,'系统管理',NULL,NULL,NULL,'Setting',12,1,1,NOW(),NOW()),
(1201,1200,2,'用户管理','system:user:list','/system/user','system/user/index',NULL,1,1,1,NOW(),NOW()),
(1202,1200,2,'角色权限','system:role:list','/system/role','system/role/index',NULL,2,1,1,NOW(),NOW()),
(1203,1200,2,'菜单管理','system:menu:list','/system/menu','system/menu/index',NULL,3,1,1,NOW(),NOW()),
(1204,1200,2,'数据字典','system:dict:edit','/system/dict','system/dict/index',NULL,4,1,1,NOW(),NOW()),
(1205,1200,2,'系统参数','system:config:list','/system/config','system/config/index',NULL,5,1,1,NOW(),NOW()),
(1206,1200,2,'消息模板','system:msg:list','/system/message-tpl','system/messageTpl/index',NULL,6,1,1,NOW(),NOW()),
(1207,1200,2,'支付渠道配置','payment:config','/system/pay-channel','system/payChannel/index',NULL,7,1,1,NOW(),NOW()),
(1208,1200,2,'定时任务','system:job:list','/system/job','system/job/index',NULL,8,1,1,NOW(),NOW()),
(1209,1200,2,'操作日志','system:log:list','/system/op-log','system/opLog/index',NULL,9,1,1,NOW(),NOW()),
(1210,1200,2,'登录日志','system:log:list','/system/login-log','system/loginLog/index',NULL,10,1,1,NOW(),NOW()),
-- 系统按钮权限
(1290,1201,3,'新增用户','system:user:add',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(1291,1201,3,'编辑用户','system:user:edit',NULL,NULL,NULL,2,0,1,NOW(),NOW()),
(1292,1201,3,'重置密码','system:user:reset',NULL,NULL,NULL,3,0,1,NOW(),NOW()),
(1293,1202,3,'新增角色','system:role:add',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(1294,1202,3,'编辑角色','system:role:edit',NULL,NULL,NULL,2,0,1,NOW(),NOW()),
(1295,1202,3,'角色查询','system:role:query',NULL,NULL,NULL,3,0,1,NOW(),NOW()),
(1296,1205,3,'修改系统参数','system:config:edit',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(1297,1206,3,'重发消息','system:msg:edit',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(1298,1208,3,'执行定时任务','system:job:run',NULL,NULL,NULL,1,0,1,NOW(),NOW()),

-- 支付相关权限（预留）
(1300,0,1,'支付中心(预留)',NULL,NULL,NULL,'CreditCard',13,1,1,NOW(),NOW()),
(1301,1300,2,'支付对账','payment:reconcile','/order/payment/reconcile','order/payment/reconcile',NULL,1,1,1,NOW(),NOW()),
(1390,1300,3,'支付查询','payment:query',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(1391,1300,3,'支付同步','payment:sync',NULL,NULL,NULL,2,0,1,NOW(),NOW()),
(1392,1300,3,'支付编辑','payment:edit',NULL,NULL,NULL,3,0,1,NOW(),NOW()),
(1393,1300,3,'支付退款','payment:refund',NULL,NULL,NULL,4,0,1,NOW(),NOW()),
(1394,1300,3,'支付统计','payment:stat',NULL,NULL,NULL,5,0,1,NOW(),NOW());

-- ---------------------------------------------------------------------
-- 2. 角色授权
-- ---------------------------------------------------------------------
-- 2.1 超级管理员：全部菜单（含按钮）
INSERT IGNORE INTO `m_sys_role_menu` (`role_id`,`menu_id`,`created_at`)
SELECT 1, id, NOW() FROM `m_sys_menu`;

-- 2.2 运营主管(2)：会员、营销、等级积分、看板、商品、次卡、收银台（不含系统管理）
INSERT IGNORE INTO `m_sys_role_menu` (`role_id`,`menu_id`,`created_at`)
SELECT 2, id, NOW() FROM `m_sys_menu`
 WHERE id < 1200 OR id = 1301;

-- 2.3 店长(3)：工作台、收银台、订单、会员、资产、次卡、商品、看板、员工
INSERT IGNORE INTO `m_sys_role_menu` (`role_id`,`menu_id`,`created_at`)
SELECT 3, id, NOW() FROM `m_sys_menu`
 WHERE id IN (100,200,290,291,292,293)
    OR (id BETWEEN 300 AND 306)
    OR (id BETWEEN 400 AND 403) OR (id BETWEEN 490 AND 496)
    OR (id BETWEEN 500 AND 505) OR (id BETWEEN 590 AND 592)
    OR (id BETWEEN 700 AND 704) OR (id BETWEEN 790 AND 793)
    OR (id BETWEEN 800 AND 803)
    OR (id BETWEEN 1000 AND 1005)
    OR (id BETWEEN 1100 AND 1103)
    OR (id BETWEEN 1190 AND 1193);

-- 2.4 收银员(4)：仅收银台 + 会员建档查询 + 订单查询 + 核销
INSERT IGNORE INTO `m_sys_role_menu` (`role_id`,`menu_id`,`created_at`)
SELECT 4, id, NOW() FROM `m_sys_menu`
 WHERE id IN (200,290,292,293,
              401,402,490,491,492,
              501,502,503,590,
              701,702,703,704,792,
              801,803);

-- 2.5 财务(5)：看板 + 流水 + 负债 + 对账 + 支付对账 + 导出
INSERT IGNORE INTO `m_sys_role_menu` (`role_id`,`menu_id`,`created_at`)
SELECT 5, id, NOW() FROM `m_sys_menu`
 WHERE id IN (100,300,301,302,303,304,305,
              500,501,502,503,505,
              1000,1001,1003,1004,1005,
              1300,1301,1390,1394);

-- ---------------------------------------------------------------------
-- 3. 校验
-- ---------------------------------------------------------------------
-- SELECT COUNT(*) AS menu_cnt FROM m_sys_menu;                    -- 期望 ~100
-- SELECT r.role_code, COUNT(rm.menu_id) FROM m_sys_role r
--   LEFT JOIN m_sys_role_menu rm ON rm.role_id = r.id GROUP BY r.id;
-- 超管权限码数量（期望 > 60）
-- SELECT COUNT(DISTINCT m.perm_code) FROM m_sys_role_menu rm
--   JOIN m_sys_menu m ON m.id = rm.menu_id
--  WHERE rm.role_id = 1 AND m.perm_code IS NOT NULL AND m.perm_code <> '';
