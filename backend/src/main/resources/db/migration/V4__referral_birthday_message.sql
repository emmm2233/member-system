-- =====================================================================
--  V4: 第三轮 —— 推荐有礼 / 生日礼 / 消息中心（订阅消息）
--
--  说明:
--    1. m_message_tpl 增加微信模板字段映射列（订阅消息下发必需）
--    2. 新增 m_birthday_gift_log（生日礼发放日志，唯一索引保证一年一次）
--    3. 新增 m_message_subscribe（订阅消息授权额度，按模板维度记账）
--    4. 补齐 referral.* / birthday.* / wx.miniapp.* 系统参数
--    5. 补齐营销中心与系统管理下的菜单与权限
--
--  全部使用 INSERT IGNORE / IF NOT EXISTS，保证重复执行幂等。
-- =====================================================================

USE `member_system`;

-- ---------------------------------------------------------------------
-- 1. 消息模板：微信订阅消息字段映射
-- ---------------------------------------------------------------------
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = 'member_system' AND table_name = 'm_message_tpl'
        AND column_name = 'wx_data_map') = 0,
    'ALTER TABLE `m_message_tpl` ADD COLUMN `wx_data_map` varchar(500) NULL COMMENT ''微信模板字段映射JSON,如{"thing1":"{memberName}","amount2":"{amount}"}'' AFTER `wx_template_id`',
    'SELECT 1'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 2. 生日礼发放日志
--    uk_member_year 是「一年只送一次」的最终防线：
--    定时任务重跑、多实例并发、手工补发都不会重复发放。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `m_birthday_gift_log` (
  `id`            bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id`     bigint unsigned NOT NULL COMMENT '会员id',
  `gift_year`     int             NOT NULL COMMENT '生日礼归属年份(取生日所在年)',
  `gift_date`     date            NOT NULL COMMENT '对应生日日期',
  `level_id`      bigint unsigned NULL     DEFAULT NULL COMMENT '发放时会员等级id(快照)',
  `points`        bigint          NOT NULL DEFAULT 0 COMMENT '赠送积分',
  `balance`       decimal(12,2)   NOT NULL DEFAULT 0.00 COMMENT '赠送余额(元)',
  `growth`        bigint          NOT NULL DEFAULT 0 COMMENT '赠送成长值',
  `coupon_tpl_id` bigint unsigned NULL     DEFAULT NULL COMMENT '券模板id',
  `coupon_id`     bigint unsigned NULL     DEFAULT NULL COMMENT '发放的券实例id',
  `status`        tinyint         NOT NULL DEFAULT 2 COMMENT '状态:1已发放 2部分发放(进行中/待补) 3发放失败',
  `fail_reason`   varchar(255)    NULL     DEFAULT NULL COMMENT '失败原因(多项以；分隔)',
  `message_sent`  tinyint(1)      NOT NULL DEFAULT 0 COMMENT '是否已发祝福消息',
  `created_at`    datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_year` (`member_id`,`gift_year`),
  KEY `idx_year_status` (`gift_year`,`status`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生日礼发放记录';

-- ---------------------------------------------------------------------
-- 3. 订阅消息授权额度
--    微信一次性订阅消息：用户授权一次只能下发一条，必须按模板维度记账。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `m_message_subscribe` (
  `id`             bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id`      bigint unsigned NOT NULL COMMENT '会员id',
  `wx_template_id` varchar(64)     NOT NULL COMMENT '微信订阅消息模板ID',
  `tpl_code`       varchar(50)     NULL     DEFAULT NULL COMMENT '关联模板编码',
  `remain_count`   int             NOT NULL DEFAULT 0 COMMENT '剩余可发送次数',
  `total_count`    int             NOT NULL DEFAULT 0 COMMENT '累计授权次数',
  `last_grant_at`  datetime        NULL     DEFAULT NULL COMMENT '最近一次授权时间',
  `created_at`     datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_tpl` (`member_id`,`wx_template_id`),
  KEY `idx_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订阅消息授权额度';

-- ---------------------------------------------------------------------
-- 4. 系统参数：微信小程序凭据（需运维填入真实值）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `m_sys_config`
(`config_key`,`config_value`,`config_name`,`config_group`,`value_type`,`editable`,`remark`,`created_at`,`updated_at`)
VALUES
('wx.miniapp.appid','','小程序AppID','wx','STRING',1,'微信公众平台-开发管理-开发设置；留空则订阅消息降级为站内信',NOW(),NOW()),
('wx.miniapp.secret','','小程序AppSecret','wx','STRING',1,'务必保密；修改后需重启或清缓存才会重新获取access_token',NOW(),NOW()),
('wx.miniapp.state','formal','小程序版本','wx','STRING',1,'formal正式版 / trial体验版 / developer开发版',NOW(),NOW()),
('wx.subscribe.enabled','true','订阅消息总开关','wx','BOOLEAN',1,'关闭后所有订阅消息降级为站内信',NOW(),NOW());

-- ---------------------------------------------------------------------
-- 5. 系统参数：推荐有礼
--    默认值配合 V1 的 REFERRAL_30 券模板(id=5) 与 POINTS 奖励。
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `m_sys_config`
(`config_key`,`config_value`,`config_name`,`config_group`,`value_type`,`editable`,`remark`,`created_at`,`updated_at`)
VALUES
('referral.enabled','true','推荐有礼开关','referral','BOOLEAN',1,'关闭后不新增推荐关系，已绑定的也不再结算',NOW(),NOW()),
('referral.settle-mode','FIRST_CONSUME','结算时机','referral','STRING',1,'REGISTER注册即奖 / FIRST_CONSUME被推荐人首单 / FIRST_RECHARGE首次充值',NOW(),NOW()),
('referral.bind-window-days','7','绑定窗口(天)','referral','NUMBER',1,'被推荐人注册后多少天内可绑定，0表示不限',NOW(),NOW()),
('referral.min-amount','0','触发门槛(元)','referral','DECIMAL',1,'被推荐人首单实付金额需达到该值',NOW(),NOW()),
('referral.require-phone','true','要求绑定手机号','referral','BOOLEAN',1,'防批量虚假注册',NOW(),NOW()),
('referral.max-per-referrer-month','20','推荐人每月上限(单)','referral','NUMBER',1,'0表示不限；超限后推荐人不再获奖，被推荐人奖励照发',NOW(),NOW()),
('referral.reward-referrer-type','POINTS','推荐人奖励类型','referral','STRING',1,'NONE/POINTS/BALANCE/GIFT_BALANCE/COUPON/GROWTH',NOW(),NOW()),
('referral.reward-referrer-value','500','推荐人奖励值','referral','DECIMAL',1,'积分或成长值取整数部分',NOW(),NOW()),
('referral.reward-referrer-coupon-tpl-id','0','推荐人券模板ID','referral','NUMBER',1,'奖励类型为COUPON时必填',NOW(),NOW()),
('referral.reward-invitee-type','COUPON','被推荐人奖励类型','referral','STRING',1,'NONE/POINTS/BALANCE/GIFT_BALANCE/COUPON/GROWTH',NOW(),NOW()),
('referral.reward-invitee-value','1','被推荐人奖励值','referral','DECIMAL',1,'券奖励时表示张数，固定按1张发放',NOW(),NOW()),
('referral.reward-invitee-coupon-tpl-id','5','被推荐人券模板ID','referral','NUMBER',1,'默认 REFERRAL_30',NOW(),NOW()),
('referral.invite-secret','member-system-referral-v1','邀请码签名密钥','referral','STRING',1,'生产环境务必修改；修改后所有旧邀请码立即失效',NOW(),NOW()),
('referral.rule-desc','邀请好友成为会员，好友完成首单后双方均可获得奖励。','活动规则文案','referral','STRING',1,'展示在小程序推荐有礼页面',NOW(),NOW()),
('referral.poster-url','','推广海报地址','referral','STRING',1,'留空则小程序前端用默认样式生成',NOW(),NOW());

-- ---------------------------------------------------------------------
-- 6. 系统参数：生日礼
--    默认送 200 积分 + BIRTHDAY_50 券(id=4)
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `m_sys_config`
(`config_key`,`config_value`,`config_name`,`config_group`,`value_type`,`editable`,`remark`,`created_at`,`updated_at`)
VALUES
('birthday.enabled','true','生日礼开关','birthday','BOOLEAN',1,'关闭后定时任务不再发放',NOW(),NOW()),
('birthday.gift-days-before','0','提前天数','birthday','NUMBER',1,'0表示生日当天发放',NOW(),NOW()),
('birthday.gift-points','200','赠送积分','birthday','NUMBER',1,'0表示不赠送',NOW(),NOW()),
('birthday.gift-balance','0','赠送余额(元)','birthday','DECIMAL',1,'计入赠送余额不可退；0表示不赠送',NOW(),NOW()),
('birthday.gift-growth','0','赠送成长值','birthday','NUMBER',1,'0表示不赠送',NOW(),NOW()),
('birthday.gift-coupon-tpl-id','4','赠送券模板ID','birthday','NUMBER',1,'默认 BIRTHDAY_50；0表示不赠券',NOW(),NOW()),
('birthday.notify-enabled','true','发送祝福消息','birthday','BOOLEAN',1,'关闭后仍发礼但不发消息',NOW(),NOW()),
('birthday.wish-text','亲爱的{memberName}，祝您生日快乐！专属生日礼已放入您的卡包，请查收。','祝福文案','birthday','STRING',1,'支持 {memberName} 占位符',NOW(),NOW());

-- ---------------------------------------------------------------------
-- 7. 消息模板：补齐微信字段映射与生日/推荐模板
-- ---------------------------------------------------------------------
UPDATE `m_message_tpl`
   SET `wx_data_map` = '{"thing1":"{memberName}","thing2":"{giftDesc}"}'
 WHERE `tpl_code` = 'BIRTHDAY_GIFT' AND (`wx_data_map` IS NULL OR `wx_data_map` = '');

UPDATE `m_message_tpl`
   SET `wx_data_map` = '{"thing1":"{memberName}","thing2":"{reward}"}'
 WHERE `tpl_code` = 'REFERRAL_REWARD' AND (`wx_data_map` IS NULL OR `wx_data_map` = '');

UPDATE `m_message_tpl`
   SET `wx_data_map` = '{"thing1":"消费成功","amount2":"{amount}","thing3":"{storeName}"}'
 WHERE `tpl_code` = 'CONSUME_SUCCESS' AND (`wx_data_map` IS NULL OR `wx_data_map` = '');

UPDATE `m_message_tpl`
   SET `wx_data_map` = '{"thing1":"充值成功","amount2":"{amount}","amount3":"{balance}"}'
 WHERE `tpl_code` = 'RECHARGE_SUCCESS' AND (`wx_data_map` IS NULL OR `wx_data_map` = '');

-- ---------------------------------------------------------------------
-- 8. 菜单：生日礼 + 消息中心
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `m_sys_menu`
(`id`,`parent_id`,`menu_type`,`menu_name`,`perm_code`,`route_path`,`component`,`icon`,`sort_no`,`visible`,`status`,`created_at`,`updated_at`)
VALUES
(606,600,2,'生日礼','birthday:list','/marketing/birthday','marketing/birthday/index',NULL,6,1,1,NOW(),NOW()),
(607,600,2,'消息中心','message:record:list','/marketing/message','marketing/message/index',NULL,7,1,1,NOW(),NOW());

-- 菜单按钮权限（menu_type=3）
INSERT IGNORE INTO `m_sys_menu`
(`id`,`parent_id`,`menu_type`,`menu_name`,`perm_code`,`route_path`,`component`,`icon`,`sort_no`,`visible`,`status`,`created_at`,`updated_at`)
VALUES
(610,605,3,'推荐规则配置','referral:config',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(611,605,3,'推荐人工结算','referral:settle',NULL,NULL,NULL,2,0,1,NOW(),NOW()),
(612,606,3,'生日礼配置','birthday:config',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(613,606,3,'生日礼发放','birthday:issue',NULL,NULL,NULL,2,0,1,NOW(),NOW()),
(614,607,3,'消息模板编辑','message:tpl:list',NULL,NULL,NULL,1,0,1,NOW(),NOW()),
(615,607,3,'消息模板保存','message:tpl:edit',NULL,NULL,NULL,2,0,1,NOW(),NOW()),
(616,607,3,'消息重发','message:record:resend',NULL,NULL,NULL,3,0,1,NOW(),NOW());

-- 超级管理员：全部菜单
INSERT IGNORE INTO `m_sys_role_menu` (`role_id`,`menu_id`,`created_at`)
SELECT 1, id, NOW() FROM `m_sys_menu`;

-- 运营主管与店长：推荐/生日礼/消息中心
INSERT IGNORE INTO `m_sys_role_menu` (`role_id`,`menu_id`,`created_at`)
SELECT r.id, m.id, NOW()
  FROM `m_sys_role` r
  JOIN `m_sys_menu` m ON m.id IN (606,607,610,611,612,613,614,615,616)
 WHERE r.role_code IN ('OPERATION','STORE_MANAGER');
