-- =====================================================================
--  会员管理系统 · 初始化建库脚本 (Flyway V1)
--  数据库: MySQL 8.0.35+  引擎: InnoDB  字符集: utf8mb4
--  说明: 本脚本幂等可重跑（CREATE TABLE IF NOT EXISTS / INSERT IGNORE）
--  执行: mysql -uroot -p < V1__init_schema.sql
--  警告: 资金核心表结构变更须走评审，禁止直接改动已发布脚本
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `member_system`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `member_system`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =====================================================================
--  支付模块（预留：小程序在线支付）
--  说明：支付单只覆盖"需经外部支付渠道"的支付（微信 JSAPI/小程序支付）。
--        储值余额支付、现金/线下扫码记账不产生支付单，直接落 m_order_payment。
--        本期 WechatPayGateway 未实现，默认使用 MockPayGateway，
--        置 system.pay.mock-enabled=false 并配置商户参数后即可切真实渠道。
-- =====================================================================

DROP TABLE IF EXISTS `m_payment_order`;
CREATE TABLE `m_payment_order` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `payment_no`       VARCHAR(32)     NOT NULL                COMMENT '支付单号(PAY+yyyyMMdd+序列)',
  `biz_type`         TINYINT         NOT NULL                COMMENT '业务类型:1充值 2积分兑换 3售卡 4商城(预留)',
  `biz_no`           VARCHAR(32)     NOT NULL                COMMENT '业务单号(充值单/订单号)',
  `order_id`         BIGINT UNSIGNED DEFAULT NULL            COMMENT '关联订单id',
  `member_id`        BIGINT UNSIGNED NOT NULL                COMMENT '会员id',
  `openid`           VARCHAR(64)     DEFAULT NULL            COMMENT '支付用户openid(JSAPI必填)',
  `pay_scene`        TINYINT         NOT NULL DEFAULT 1      COMMENT '支付场景:1小程序JSAPI 2H5(预留) 3APP(预留)',
  `channel`          VARCHAR(20)     NOT NULL DEFAULT 'WECHAT' COMMENT '支付渠道:WECHAT/MOCK/ALIPAY(预留)',
  `channel_appid`    VARCHAR(64)     DEFAULT NULL            COMMENT '发起支付的appid(多应用时区分)',
  `mch_id`           VARCHAR(64)     DEFAULT NULL            COMMENT '商户号',
  `amount`           DECIMAL(12,2)   NOT NULL                COMMENT '应付金额(元)',
  `amount_fen`       BIGINT          NOT NULL                COMMENT '应付金额(分,渠道要求整数分)',
  `currency`         VARCHAR(8)      NOT NULL DEFAULT 'CNY'  COMMENT '币种',
  `subject`          VARCHAR(128)    NOT NULL                COMMENT '商品描述(渠道要求)',
  `description`      VARCHAR(255)    DEFAULT NULL            COMMENT '详细描述',
  `channel_order_no` VARCHAR(64)     DEFAULT NULL            COMMENT '渠道侧交易号(微信 transaction_id)',
  `prepay_id`        VARCHAR(128)    DEFAULT NULL            COMMENT '渠道预支付会话标识',
  `status`           TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:1待支付 2支付中 3已支付 4已关闭 5已退款 6部分退款 7支付失败',
  `store_id`         BIGINT UNSIGNED DEFAULT NULL            COMMENT '发起门店(可空,小程序无门店)',
  `idempotency_key`  VARCHAR(64)     DEFAULT NULL            COMMENT '幂等键',
  `expire_time`      DATETIME        NOT NULL                COMMENT '支付单超时时间(默认下单+15分钟)',
  `paid_at`          DATETIME        DEFAULT NULL            COMMENT '支付成功时间',
  `closed_at`        DATETIME        DEFAULT NULL            COMMENT '关闭时间',
  `refund_amount`    DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '累计已退金额',
  `refund_no`        VARCHAR(32)     DEFAULT NULL            COMMENT '最近一次退款单号',
  `refunded_at`      DATETIME        DEFAULT NULL            COMMENT '最近退款完成时间',
  `notify_count`     INT             NOT NULL DEFAULT 0      COMMENT '收到回调次数',
  `last_notify_at`   DATETIME        DEFAULT NULL            COMMENT '最近回调时间',
  `account_credited` TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '是否已完成资产入账(充值类幂等标记)',
  `credited_at`      DATETIME        DEFAULT NULL            COMMENT '入账完成时间',
  `fail_code`        VARCHAR(64)     DEFAULT NULL            COMMENT '失败码',
  `fail_msg`         VARCHAR(500)    DEFAULT NULL            COMMENT '失败原因',
  `attach`           VARCHAR(500)    DEFAULT NULL            COMMENT '附加数据(透传)',
  `remark`           VARCHAR(255)    DEFAULT NULL            COMMENT '备注',
  `tenant_id`        BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '租户id(预留)',
  `created_at`       DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`       DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_no` (`payment_no`),
  UNIQUE KEY `uk_biz` (`biz_type`,`biz_no`),
  UNIQUE KEY `uk_idem` (`idempotency_key`),
  UNIQUE KEY `uk_channel_order` (`channel`,`channel_order_no`),
  KEY `idx_member_status` (`member_id`,`status`),
  KEY `idx_status_expire` (`status`,`expire_time`),
  KEY `idx_channel_status` (`channel`,`status`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付单(预留:小程序在线支付)';

DROP TABLE IF EXISTS `m_payment_notify_log`;
CREATE TABLE `m_payment_notify_log` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `payment_no`     VARCHAR(32)     DEFAULT NULL            COMMENT '支付单号(解析成功后回填)',
  `channel`        VARCHAR(20)     NOT NULL DEFAULT 'WECHAT' COMMENT '渠道',
  `notify_type`    TINYINT         NOT NULL DEFAULT 1      COMMENT '类型:1支付结果 2退款结果',
  `notify_id`      VARCHAR(64)     DEFAULT NULL            COMMENT '渠道通知唯一id(微信 id)',
  `out_trade_no`   VARCHAR(64)     DEFAULT NULL            COMMENT '商户订单号(渠道侧回传)',
  `channel_order_no` VARCHAR(64)   DEFAULT NULL            COMMENT '渠道交易号',
  `trade_state`    VARCHAR(32)     DEFAULT NULL            COMMENT '渠道交易状态',
  `amount_fen`     BIGINT          DEFAULT NULL            COMMENT '通知金额(分)',
  `headers`        VARCHAR(2000)   DEFAULT NULL            COMMENT '请求头(含签名相关)',
  `raw_body`       TEXT                                    COMMENT '原始报文(解密后)',
  `sign_verified`  TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '验签是否通过',
  `verify_mode`    VARCHAR(20)     DEFAULT NULL            COMMENT '验签模式:APIv3_AUTO/SDK',
  `handle_status`  TINYINT         NOT NULL DEFAULT 1      COMMENT '处理状态:1待处理 2处理成功 3处理失败 4重复通知已忽略',
  `handle_result`  VARCHAR(500)    DEFAULT NULL            COMMENT '处理结果说明',
  `handle_ms`      INT             DEFAULT NULL            COMMENT '处理耗时(毫秒)',
  `ip`             VARCHAR(64)     DEFAULT NULL            COMMENT '来源IP',
  `created_at`     DATETIME        NOT NULL                COMMENT '接收时间',
  `handled_at`     DATETIME        DEFAULT NULL            COMMENT '处理完成时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notify_id` (`notify_id`),
  KEY `idx_payment` (`payment_no`),
  KEY `idx_out_trade_no` (`out_trade_no`),
  KEY `idx_status_time` (`handle_status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付回调日志(审计+排障+对账)';

-- =====================================================================
-- 0. 系统权限 RBAC
-- =====================================================================

DROP TABLE IF EXISTS `m_sys_user`;
CREATE TABLE `m_sys_user` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`      VARCHAR(50)     NOT NULL                COMMENT '登录账号',
  `password`      VARCHAR(100)    NOT NULL                COMMENT 'BCrypt 密码',
  `real_name`     VARCHAR(50)     DEFAULT NULL            COMMENT '姓名',
  `phone`         VARCHAR(20)     DEFAULT NULL            COMMENT '手机号',
  `staff_id`      BIGINT UNSIGNED DEFAULT NULL            COMMENT '绑定员工id',
  `store_id`      BIGINT UNSIGNED DEFAULT NULL            COMMENT '默认门店id',
  `data_scope`    TINYINT         NOT NULL DEFAULT 2      COMMENT '数据范围:1全部 2本店 3本人 4自定义',
  `scope_store_ids` VARCHAR(500)  DEFAULT NULL            COMMENT '自定义门店id列表(逗号分隔)',
  `status`        TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0停用 1启用',
  `token_version` INT             NOT NULL DEFAULT 0      COMMENT '令牌版本,改密后+1使旧token失效',
  `last_login_at` DATETIME        DEFAULT NULL            COMMENT '最后登录时间',
  `last_login_ip` VARCHAR(64)     DEFAULT NULL            COMMENT '最后登录IP',
  `tenant_id`     BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '租户id(预留)',
  `deleted`       TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  `created_at`    DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`    DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_store` (`store_id`),
  KEY `idx_staff` (`staff_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户';

DROP TABLE IF EXISTS `m_sys_role`;
CREATE TABLE `m_sys_role` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_code`   VARCHAR(50)     NOT NULL                COMMENT '角色编码,如 ADMIN/STORE_MANAGER/CASHIER',
  `role_name`   VARCHAR(50)     NOT NULL                COMMENT '角色名称',
  `data_scope`  TINYINT         NOT NULL DEFAULT 2      COMMENT '数据范围:1全部 2本店 3本人 4自定义',
  `sort_no`     INT             NOT NULL DEFAULT 0      COMMENT '排序',
  `status`      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0停用 1启用',
  `remark`      VARCHAR(255)    DEFAULT NULL            COMMENT '备注',
  `tenant_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '租户id(预留)',
  `deleted`     TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  `created_at`  DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`  DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色';

DROP TABLE IF EXISTS `m_sys_user_role`;
CREATE TABLE `m_sys_user_role` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`    BIGINT UNSIGNED NOT NULL                COMMENT '用户id',
  `role_id`    BIGINT UNSIGNED NOT NULL                COMMENT '角色id',
  `created_at` DATETIME        NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`,`role_id`),
  KEY `idx_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关系';

DROP TABLE IF EXISTS `m_sys_menu`;
CREATE TABLE `m_sys_menu` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `parent_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '父菜单id,0为顶级',
  `menu_type`   TINYINT         NOT NULL DEFAULT 2      COMMENT '类型:1目录 2菜单 3按钮',
  `menu_name`   VARCHAR(50)     NOT NULL                COMMENT '名称',
  `perm_code`   VARCHAR(100)    DEFAULT NULL            COMMENT '权限码,如 member:edit',
  `route_path`  VARCHAR(200)    DEFAULT NULL            COMMENT '前端路由路径',
  `component`   VARCHAR(200)    DEFAULT NULL            COMMENT '前端组件路径',
  `icon`        VARCHAR(100)    DEFAULT NULL            COMMENT '图标',
  `sort_no`     INT             NOT NULL DEFAULT 0      COMMENT '排序',
  `visible`     TINYINT(1)      NOT NULL DEFAULT 1      COMMENT '是否显示',
  `status`      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0停用 1启用',
  `created_at`  DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`  DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_parent` (`parent_id`),
  KEY `idx_perm` (`perm_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单与权限';

DROP TABLE IF EXISTS `m_sys_role_menu`;
CREATE TABLE `m_sys_role_menu` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_id`    BIGINT UNSIGNED NOT NULL                COMMENT '角色id',
  `menu_id`    BIGINT UNSIGNED NOT NULL                COMMENT '菜单id',
  `created_at` DATETIME        NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`,`menu_id`),
  KEY `idx_menu` (`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色菜单关系';

-- =====================================================================
-- 1. 门店与员工
-- =====================================================================

DROP TABLE IF EXISTS `m_store`;
CREATE TABLE `m_store` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `store_code`    VARCHAR(32)     NOT NULL                COMMENT '门店编码',
  `store_name`    VARCHAR(100)    NOT NULL                COMMENT '门店名称',
  `province`      VARCHAR(50)     DEFAULT NULL            COMMENT '省',
  `city`          VARCHAR(50)     DEFAULT NULL            COMMENT '市',
  `district`      VARCHAR(50)     DEFAULT NULL            COMMENT '区',
  `address`       VARCHAR(255)    DEFAULT NULL            COMMENT '详细地址',
  `longitude`     DECIMAL(10,6)   DEFAULT NULL            COMMENT '经度',
  `latitude`      DECIMAL(10,6)   DEFAULT NULL            COMMENT '纬度',
  `contact_name`  VARCHAR(50)     DEFAULT NULL            COMMENT '联系人',
  `contact_phone` VARCHAR(20)     DEFAULT NULL            COMMENT '联系电话',
  `business_hours` VARCHAR(100)   DEFAULT NULL            COMMENT '营业时间,如 09:00-22:00',
  `manager_id`    BIGINT UNSIGNED DEFAULT NULL            COMMENT '店长员工id',
  `status`        TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0停业 1营业',
  `sort_no`       INT             NOT NULL DEFAULT 0      COMMENT '排序',
  `tenant_id`     BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '租户id(预留)',
  `deleted`       TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  `created_at`    DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`    DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_store_code` (`store_code`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门店';

DROP TABLE IF EXISTS `m_staff`;
CREATE TABLE `m_staff` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `staff_no`     VARCHAR(32)     NOT NULL                COMMENT '员工工号',
  `staff_name`   VARCHAR(50)     NOT NULL                COMMENT '姓名',
  `phone`        VARCHAR(20)     DEFAULT NULL            COMMENT '手机号',
  `store_id`     BIGINT UNSIGNED NOT NULL                COMMENT '所属门店id',
  `position`     VARCHAR(50)     DEFAULT NULL            COMMENT '岗位',
  `entry_date`   DATE            DEFAULT NULL            COMMENT '入职日期',
  `leave_date`   DATE            DEFAULT NULL            COMMENT '离职日期',
  `status`       TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0离职 1在职',
  `tenant_id`    BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '租户id(预留)',
  `deleted`      TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  `created_at`   DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`   DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_staff_no` (`staff_no`),
  KEY `idx_store_status` (`store_id`,`status`),
  KEY `idx_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工';

-- =====================================================================
-- 2. 会员中心
-- =====================================================================

DROP TABLE IF EXISTS `m_member_level`;
CREATE TABLE `m_member_level` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `level_code`     VARCHAR(32)     NOT NULL                COMMENT '等级编码',
  `level_name`     VARCHAR(50)     NOT NULL                COMMENT '等级名称',
  `level_value`    INT             NOT NULL                COMMENT '等级序号,越大越高',
  `icon`           VARCHAR(255)    DEFAULT NULL            COMMENT '等级图标',
  `discount_rate`  DECIMAL(5,4)    NOT NULL DEFAULT 1.0000 COMMENT '商品折扣率,0.9=九折',
  `points_rate`    DECIMAL(5,2)    NOT NULL DEFAULT 1.00   COMMENT '积分倍率',
  `card_discount_rate` DECIMAL(5,4) NOT NULL DEFAULT 1.0000 COMMENT '次卡/服务折扣率',
  `birthday_coupon_tpl_id` BIGINT UNSIGNED DEFAULT NULL    COMMENT '生日礼券模板id',
  `upgrade_gift_points` BIGINT    NOT NULL DEFAULT 0       COMMENT '升级赠送积分',
  `is_default`     TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '是否默认等级(新会员)',
  `valid_months`   INT             NOT NULL DEFAULT 0      COMMENT '等级有效期(月),0=永久',
  `status`         TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0停用 1启用',
  `sort_no`        INT             NOT NULL DEFAULT 0      COMMENT '排序',
  `remark`         VARCHAR(255)    DEFAULT NULL            COMMENT '权益说明',
  `deleted`        TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  `created_at`     DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`     DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_level_code` (`level_code`),
  KEY `idx_level_value` (`level_value`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员等级定义';

DROP TABLE IF EXISTS `m_level_rule`;
CREATE TABLE `m_level_rule` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `level_id`    BIGINT UNSIGNED NOT NULL                COMMENT '等级id',
  `rule_type`   TINYINT         NOT NULL DEFAULT 1      COMMENT '规则类型:1升级 2保级',
  `logic_type`  VARCHAR(8)      NOT NULL DEFAULT 'AND'  COMMENT '条件逻辑:AND/OR',
  `rule_json`   JSON            NOT NULL                COMMENT '条件表达式JSON',
  `priority`    INT             NOT NULL DEFAULT 0      COMMENT '优先级',
  `status`      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0停用 1启用',
  `created_at`  DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`  DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_level_type` (`level_id`,`rule_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='等级评定规则';

DROP TABLE IF EXISTS `m_member`;
CREATE TABLE `m_member` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_no`      VARCHAR(32)     NOT NULL                COMMENT '会员号,如M20260914000123',
  `phone`          VARCHAR(20)     DEFAULT NULL            COMMENT '手机号(会员唯一识别)',
  `openid`         VARCHAR(64)     DEFAULT NULL            COMMENT '微信小程序openid',
  `unionid`        VARCHAR(64)     DEFAULT NULL            COMMENT '微信开放平台unionid',
  `nickname`       VARCHAR(64)     DEFAULT NULL            COMMENT '昵称',
  `avatar`         VARCHAR(500)    DEFAULT NULL            COMMENT '头像URL',
  `real_name`      VARCHAR(50)     DEFAULT NULL            COMMENT '真实姓名',
  `gender`         TINYINT         NOT NULL DEFAULT 0      COMMENT '性别:0未知 1男 2女',
  `birthday`       DATE            DEFAULT NULL            COMMENT '生日(年可默认1900)',
  `level_id`       BIGINT UNSIGNED DEFAULT NULL            COMMENT '当前等级id',
  `level_expire_at` DATETIME       DEFAULT NULL            COMMENT '等级有效期至',
  `status`         TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:1正常 2冻结 3注销',
  `source`         TINYINT         NOT NULL DEFAULT 1      COMMENT '来源:1门店建档 2小程序注册 3批量导入 4推荐注册',
  `referrer_id`    BIGINT UNSIGNED DEFAULT NULL            COMMENT '推荐人会员id',
  `store_id`       BIGINT UNSIGNED DEFAULT NULL            COMMENT '归属门店id',
  `creator_id`     BIGINT UNSIGNED DEFAULT NULL            COMMENT '建档人(系统用户id)',
  `remark`         VARCHAR(255)    DEFAULT NULL            COMMENT '备注',
  `tenant_id`      BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '租户id(预留)',
  `deleted`        TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  `created_at`     DATETIME        NOT NULL                COMMENT '创建时间(入会时间)',
  `updated_at`     DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_no` (`member_no`),
  UNIQUE KEY `uk_phone` (`phone`),
  UNIQUE KEY `uk_openid` (`openid`),
  KEY `idx_store_status` (`store_id`,`status`,`id`),
  KEY `idx_level` (`level_id`),
  KEY `idx_birthday` (`birthday`),
  KEY `idx_referrer` (`referrer_id`),
  KEY `idx_created` (`created_at`),
  KEY `idx_name` (`nickname`),
  KEY `idx_unionid` (`unionid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员主档';

DROP TABLE IF EXISTS `m_member_stat`;
CREATE TABLE `m_member_stat` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id`        BIGINT UNSIGNED NOT NULL                COMMENT '会员id',
  `first_consume_at` DATETIME        DEFAULT NULL            COMMENT '首次消费时间',
  `last_consume_at`  DATETIME        DEFAULT NULL            COMMENT '最近消费时间',
  `pay_amount_30d`   DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '近30天实付金额',
  `pay_amount_90d`   DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '近90天实付金额',
  `pay_amount_12m`   DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '近12个月实付金额',
  `order_count_12m`  INT             NOT NULL DEFAULT 0      COMMENT '近12个月消费笔数',
  `visit_days_30d`   INT             NOT NULL DEFAULT 0      COMMENT '近30天到店天数',
  `avg_order_amount` DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '客单价',
  `consume_store_id` BIGINT UNSIGNED DEFAULT NULL            COMMENT '最常消费门店id',
  `updated_at`       DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member` (`member_id`),
  KEY `idx_last_consume` (`last_consume_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员统计快照(等级评定依据)';

DROP TABLE IF EXISTS `m_member_tag`;
CREATE TABLE `m_member_tag` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tag_name`   VARCHAR(50)     NOT NULL                COMMENT '标签名',
  `tag_color`  VARCHAR(20)     DEFAULT NULL            COMMENT '标签颜色',
  `tag_group`  VARCHAR(50)     DEFAULT NULL            COMMENT '标签分组',
  `auto_rule`  VARCHAR(500)    DEFAULT NULL            COMMENT '自动打标规则(预留)',
  `status`     TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0停用 1启用',
  `sort_no`    INT             NOT NULL DEFAULT 0      COMMENT '排序',
  `created_at` DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at` DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tag_name` (`tag_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员标签';

DROP TABLE IF EXISTS `m_member_tag_rel`;
CREATE TABLE `m_member_tag_rel` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id`  BIGINT UNSIGNED NOT NULL                COMMENT '会员id',
  `tag_id`     BIGINT UNSIGNED NOT NULL                COMMENT '标签id',
  `created_at` DATETIME        NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_tag` (`member_id`,`tag_id`),
  KEY `idx_tag` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员标签关系';

DROP TABLE IF EXISTS `m_member_note`;
CREATE TABLE `m_member_note` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id`  BIGINT UNSIGNED NOT NULL                COMMENT '会员id',
  `note_type`  TINYINT         NOT NULL DEFAULT 1      COMMENT '类型:1跟进记录 2投诉 3偏好',
  `content`    VARCHAR(1000)   NOT NULL                COMMENT '内容',
  `operator_id` BIGINT UNSIGNED DEFAULT NULL           COMMENT '操作人(系统用户id)',
  `created_at` DATETIME        NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_member_time` (`member_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员跟进备注';

DROP TABLE IF EXISTS `m_member_merge_log`;
CREATE TABLE `m_member_merge_log` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `main_member_id`  BIGINT UNSIGNED NOT NULL                COMMENT '保留的主会员id',
  `merged_member_id` BIGINT UNSIGNED NOT NULL               COMMENT '被合并会员id',
  `merge_snapshot`  JSON            NOT NULL                COMMENT '合并前双方快照(资产/券/卡/流水数)',
  `balance_moved`   DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '转移本金',
  `gift_moved`      DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '转移赠送余额',
  `points_moved`    BIGINT          NOT NULL DEFAULT 0      COMMENT '转移积分',
  `operator_id`     BIGINT UNSIGNED DEFAULT NULL            COMMENT '操作人',
  `remark`          VARCHAR(500)    DEFAULT NULL            COMMENT '原因说明',
  `created_at`      DATETIME        NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_main` (`main_member_id`),
  KEY `idx_merged` (`merged_member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员合并日志';

DROP TABLE IF EXISTS `m_member_import_task`;
CREATE TABLE `m_member_import_task` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_no`       VARCHAR(32)     NOT NULL                COMMENT '任务号',
  `file_name`     VARCHAR(255)    NOT NULL                COMMENT '文件名',
  `file_url`      VARCHAR(500)    DEFAULT NULL            COMMENT '文件地址',
  `total_rows`    INT             NOT NULL DEFAULT 0      COMMENT '总行数',
  `success_rows`  INT             NOT NULL DEFAULT 0      COMMENT '成功行数',
  `fail_rows`     INT             NOT NULL DEFAULT 0      COMMENT '失败行数',
  `status`        TINYINT         NOT NULL DEFAULT 1      COMMENT '1待处理 2处理中 3成功 4部分失败 5失败',
  `error_file_url` VARCHAR(500)   DEFAULT NULL            COMMENT '错误明细文件',
  `error_msg`     VARCHAR(1000)   DEFAULT NULL            COMMENT '错误信息',
  `operator_id`   BIGINT UNSIGNED DEFAULT NULL            COMMENT '操作人',
  `created_at`    DATETIME        NOT NULL                COMMENT '创建时间',
  `finished_at`   DATETIME        DEFAULT NULL            COMMENT '完成时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_no` (`task_no`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员导入任务';

-- =====================================================================
-- 3. 资产账务（资金核心）
-- =====================================================================

DROP TABLE IF EXISTS `m_member_account`;
CREATE TABLE `m_member_account` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id`       BIGINT UNSIGNED NOT NULL                COMMENT '会员id',
  `balance`         DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '本金余额(可退)',
  `gift_balance`    DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '赠送余额(不可退不可提现)',
  `frozen_amount`   DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '冻结金额',
  `points`          BIGINT          NOT NULL DEFAULT 0      COMMENT '积分余额(可为负:退单扣回)',
  `growth`          BIGINT          NOT NULL DEFAULT 0      COMMENT '成长值(只增不减,年度清零可配)',
  `total_recharge`  DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '累计充值本金',
  `total_gift`      DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '累计获赠金额',
  `total_consume`   DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '累计实付消费(不含赠送抵扣)',
  `total_consume_count` INT         NOT NULL DEFAULT 0      COMMENT '累计消费笔数',
  `balance_expire_at` DATETIME      DEFAULT NULL            COMMENT '余额有效期(可空=永久)',
  `version`         INT             NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  `created_at`      DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`      DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员资产账户(资金核心)';

DROP TABLE IF EXISTS `m_balance_flow`;
CREATE TABLE `m_balance_flow` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `flow_no`         VARCHAR(32)     NOT NULL                COMMENT '流水号',
  `member_id`       BIGINT UNSIGNED NOT NULL                COMMENT '会员id',
  `account_type`    TINYINT         NOT NULL                COMMENT '账户类型:1本金 2赠送',
  `biz_type`        TINYINT         NOT NULL                COMMENT '业务类型:1充值 2消费 3退款 4调整 5赠送 6过期 7转入 8转出 9合并',
  `change_amount`   DECIMAL(12,2)   NOT NULL                COMMENT '变动金额(正加负减)',
  `before_amount`   DECIMAL(12,2)   NOT NULL                COMMENT '变动前余额',
  `after_amount`    DECIMAL(12,2)   NOT NULL                COMMENT '变动后余额',
  `biz_no`          VARCHAR(32)     DEFAULT NULL            COMMENT '关联业务单号',
  `biz_id`          BIGINT UNSIGNED DEFAULT NULL            COMMENT '关联业务主键',
  `store_id`        BIGINT UNSIGNED DEFAULT NULL            COMMENT '发生门店id',
  `operator_id`     BIGINT UNSIGNED DEFAULT NULL            COMMENT '操作人(系统用户id)',
  `shift_no`        VARCHAR(32)     DEFAULT NULL            COMMENT '班次号(交班对账)',
  `idempotency_key` VARCHAR(64)     DEFAULT NULL            COMMENT '幂等键',
  `remark`          VARCHAR(255)    DEFAULT NULL            COMMENT '备注',
  `occur_time`      DATETIME        NOT NULL                COMMENT '业务发生时间',
  `created_at`      DATETIME        NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_flow_no` (`flow_no`),
  UNIQUE KEY `uk_idem` (`idempotency_key`,`biz_type`),
  KEY `idx_member_time` (`member_id`,`occur_time`),
  KEY `idx_biz` (`biz_no`),
  KEY `idx_store_time` (`store_id`,`occur_time`),
  KEY `idx_type_time` (`biz_type`,`occur_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='余额流水(资金核心,只增不改)';

DROP TABLE IF EXISTS `m_point_flow`;
CREATE TABLE `m_point_flow` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `flow_no`         VARCHAR(32)     NOT NULL                COMMENT '流水号',
  `member_id`       BIGINT UNSIGNED NOT NULL                COMMENT '会员id',
  `biz_type`        TINYINT         NOT NULL                COMMENT '业务类型:1消费获得 2充值赠送 3兑换扣减 4过期 5调整 6退单扣回 7升级赠送',
  `change_points`   BIGINT          NOT NULL                COMMENT '变动积分(正加负减)',
  `before_points`   BIGINT          NOT NULL                COMMENT '变动前积分',
  `after_points`    BIGINT          NOT NULL                COMMENT '变动后积分',
  `biz_no`          VARCHAR(32)     DEFAULT NULL            COMMENT '关联业务单号',
  `biz_id`          BIGINT UNSIGNED DEFAULT NULL            COMMENT '关联业务主键',
  `store_id`        BIGINT UNSIGNED DEFAULT NULL            COMMENT '发生门店id',
  `operator_id`     BIGINT UNSIGNED DEFAULT NULL            COMMENT '操作人',
  `expire_time`     DATETIME        DEFAULT NULL            COMMENT '本次获得的积分到期时间',
  `batch_id`        BIGINT UNSIGNED DEFAULT NULL            COMMENT '关联积分批次id',
  `idempotency_key` VARCHAR(64)     DEFAULT NULL            COMMENT '幂等键',
  `remark`          VARCHAR(255)    DEFAULT NULL            COMMENT '备注',
  `occur_time`      DATETIME        NOT NULL                COMMENT '业务发生时间',
  `created_at`      DATETIME        NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_flow_no` (`flow_no`),
  UNIQUE KEY `uk_idem` (`idempotency_key`,`biz_type`),
  KEY `idx_member_time` (`member_id`,`occur_time`),
  KEY `idx_biz` (`biz_no`),
  KEY `idx_type_time` (`biz_type`,`occur_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分流水';

DROP TABLE IF EXISTS `m_point_batch`;
CREATE TABLE `m_point_batch` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id`     BIGINT UNSIGNED NOT NULL                COMMENT '会员id',
  `source_flow_no` VARCHAR(32)    DEFAULT NULL            COMMENT '来源积分流水号',
  `biz_type`      TINYINT         NOT NULL                COMMENT '来源业务类型',
  `total_points`  BIGINT          NOT NULL                COMMENT '批次总积分',
  `used_points`   BIGINT          NOT NULL DEFAULT 0      COMMENT '已使用积分',
  `remain_points` BIGINT          NOT NULL                COMMENT '剩余积分',
  `expire_time`   DATETIME        DEFAULT NULL            COMMENT '到期时间(NULL=永久)',
  `status`        TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:1有效 2已用完 3已过期',
  `created_at`    DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`    DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_member_status_expire` (`member_id`,`status`,`expire_time`),
  KEY `idx_expire` (`status`,`expire_time`),
  KEY `idx_flow` (`source_flow_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分批次(FIFO过期)';

DROP TABLE IF EXISTS `m_growth_flow`;
CREATE TABLE `m_growth_flow` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `flow_no`       VARCHAR(32)     NOT NULL                COMMENT '流水号',
  `member_id`     BIGINT UNSIGNED NOT NULL                COMMENT '会员id',
  `biz_type`      TINYINT         NOT NULL                COMMENT '1消费获得 2调整 3年度清零 4退单扣回 5活动',
  `change_growth` BIGINT          NOT NULL                COMMENT '变动成长值',
  `before_growth` BIGINT          NOT NULL                COMMENT '变动前',
  `after_growth`  BIGINT          NOT NULL                COMMENT '变动后',
  `biz_no`        VARCHAR(32)     DEFAULT NULL            COMMENT '关联业务单号',
  `operator_id`   BIGINT UNSIGNED DEFAULT NULL            COMMENT '操作人',
  `remark`        VARCHAR(255)    DEFAULT NULL            COMMENT '备注',
  `occur_time`    DATETIME        NOT NULL                COMMENT '业务发生时间',
  `created_at`    DATETIME        NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_flow_no` (`flow_no`),
  KEY `idx_member_time` (`member_id`,`occur_time`),
  KEY `idx_biz` (`biz_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成长值流水';

-- =====================================================================
-- 4. 商品与充值方案
-- =====================================================================

DROP TABLE IF EXISTS `m_product_category`;
CREATE TABLE `m_product_category` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `parent_id`  BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '父级id',
  `cate_name`  VARCHAR(50)     NOT NULL                COMMENT '分类名称',
  `cate_type`  TINYINT         NOT NULL DEFAULT 1      COMMENT '1商品 2服务',
  `sort_no`    INT             NOT NULL DEFAULT 0      COMMENT '排序',
  `status`     TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0停用 1启用',
  `deleted`    TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  `created_at` DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at` DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类';

DROP TABLE IF EXISTS `m_product`;
CREATE TABLE `m_product` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `product_code`   VARCHAR(32)     NOT NULL                COMMENT '商品编码/条码',
  `product_name`   VARCHAR(100)    NOT NULL                COMMENT '名称',
  `category_id`    BIGINT UNSIGNED DEFAULT NULL            COMMENT '分类id',
  `product_type`   TINYINT         NOT NULL DEFAULT 1      COMMENT '1商品 2服务/项目',
  `price`          DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '标准售价',
  `member_price`   DECIMAL(12,2)   DEFAULT NULL            COMMENT '会员价(可空,为空用等级折扣)',
  `cost_price`     DECIMAL(12,2)   DEFAULT NULL            COMMENT '成本价',
  `unit`           VARCHAR(20)     DEFAULT NULL            COMMENT '单位',
  `image`          VARCHAR(500)    DEFAULT NULL            COMMENT '图片',
  `points_rate`    DECIMAL(5,2)    NOT NULL DEFAULT 1.00   COMMENT '积分倍率(品类加成)',
  `join_discount`  TINYINT(1)      NOT NULL DEFAULT 1      COMMENT '是否参与等级折扣',
  `join_points`    TINYINT(1)      NOT NULL DEFAULT 1      COMMENT '是否参与积分累计',
  `stock_enabled`  TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '是否管理库存',
  `stock_qty`      INT             NOT NULL DEFAULT 0      COMMENT '库存数量',
  `status`         TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0下架 1上架',
  `sort_no`        INT             NOT NULL DEFAULT 0      COMMENT '排序',
  `tenant_id`      BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '租户id(预留)',
  `deleted`        TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  `created_at`     DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`     DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_code` (`product_code`),
  KEY `idx_category_status` (`category_id`,`status`),
  KEY `idx_name` (`product_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品/服务';

DROP TABLE IF EXISTS `m_recharge_plan`;
CREATE TABLE `m_recharge_plan` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `plan_name`       VARCHAR(50)     NOT NULL                COMMENT '方案名称,如"充500送50"',
  `recharge_amount` DECIMAL(12,2)   NOT NULL                COMMENT '充值本金',
  `gift_amount`     DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '赠送金额',
  `gift_points`     BIGINT          NOT NULL DEFAULT 0      COMMENT '赠送积分',
  `gift_coupon_tpl_ids` VARCHAR(500) DEFAULT NULL           COMMENT '赠送券模板id列表(逗号分隔及数量)',
  `upgrade_level_id` BIGINT UNSIGNED DEFAULT NULL           COMMENT '直接升级到等级id',
  `store_id`        BIGINT UNSIGNED DEFAULT NULL            COMMENT '限定门店(NULL=全部)',
  `valid_start`     DATETIME        DEFAULT NULL            COMMENT '生效开始',
  `valid_end`       DATETIME        DEFAULT NULL            COMMENT '生效结束',
  `status`          TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0停用 1启用',
  `sort_no`         INT             NOT NULL DEFAULT 0      COMMENT '排序',
  `remark`          VARCHAR(255)    DEFAULT NULL            COMMENT '说明',
  `deleted`         TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  `created_at`      DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`      DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充值方案';

-- =====================================================================
-- 5. 收银订单（资金核心）
-- =====================================================================

DROP TABLE IF EXISTS `m_order`;
CREATE TABLE `m_order` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no`           VARCHAR(32)     NOT NULL                COMMENT '订单号(前缀区分:O消费 R充值 C售卡 X兑换)',
  `order_type`         TINYINT         NOT NULL DEFAULT 1      COMMENT '订单类型:1消费 2充值 3售卡 4积分兑换',
  `member_id`          BIGINT UNSIGNED NOT NULL                COMMENT '会员id',
  `store_id`           BIGINT UNSIGNED NOT NULL                COMMENT '业绩门店id',
  `cashier_id`         BIGINT UNSIGNED DEFAULT NULL            COMMENT '收银员(系统用户id)',
  `staff_id`           BIGINT UNSIGNED DEFAULT NULL            COMMENT '服务员工id(业绩归属)',
  `total_amount`       DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '原价合计',
  `discount_amount`    DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '等级/会员折扣优惠额',
  `coupon_amount`      DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '券抵扣额',
  `point_deduct_amount` DECIMAL(12,2)  NOT NULL DEFAULT 0.00   COMMENT '积分抵扣额',
  `point_deduct_points` BIGINT         NOT NULL DEFAULT 0      COMMENT '本单消耗积分',
  `payable_amount`     DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '应收金额',
  `paid_amount`        DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '实收金额',
  `gift_amount`        DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '充值单赠送额',
  `points_gained`      BIGINT          NOT NULL DEFAULT 0      COMMENT '本单获得积分',
  `growth_gained`      BIGINT          NOT NULL DEFAULT 0      COMMENT '本单获得成长值',
  `status`             TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:1待支付 2支付中(在线支付,预留) 3已支付 4已部分退 5已全额退 6已取消 7挂单 8支付失败',
  `refund_amount`      DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '已退金额',
  `payment_no`         VARCHAR(32)     DEFAULT NULL            COMMENT '在线支付单号(预留)',
  `channel_order_no`   VARCHAR(64)     DEFAULT NULL            COMMENT '渠道交易号(预留:微信transaction_id)',
  `expire_time`        DATETIME        DEFAULT NULL            COMMENT '支付超时时间(在线支付,预留)',
  `shift_no`           VARCHAR(32)     DEFAULT NULL            COMMENT '班次号',
  `idempotency_key`    VARCHAR(64)     DEFAULT NULL            COMMENT '幂等键',
  `remark`             VARCHAR(500)    DEFAULT NULL            COMMENT '备注',
  `tenant_id`          BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '租户id(预留)',
  `paid_at`            DATETIME        DEFAULT NULL            COMMENT '支付完成时间',
  `created_at`         DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`         DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  UNIQUE KEY `uk_idem` (`idempotency_key`),
  KEY `idx_member_time` (`member_id`,`created_at`),
  KEY `idx_store_time_status` (`store_id`,`created_at`,`status`),
  KEY `idx_cashier_time` (`cashier_id`,`created_at`),
  KEY `idx_staff_time` (`staff_id`,`created_at`),
  KEY `idx_status_time` (`status`,`created_at`),
  KEY `idx_payment_no` (`payment_no`),
  KEY `idx_shift` (`shift_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表(资金核心)';

DROP TABLE IF EXISTS `m_order_item`;
CREATE TABLE `m_order_item` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id`      BIGINT UNSIGNED NOT NULL                COMMENT '订单id',
  `order_no`      VARCHAR(32)     NOT NULL                COMMENT '订单号(冗余查询)',
  `item_type`     TINYINT         NOT NULL DEFAULT 1      COMMENT '1商品 2服务 3次卡 4自由金额 5积分兑换品',
  `product_id`    BIGINT UNSIGNED DEFAULT NULL            COMMENT '商品id',
  `product_name`  VARCHAR(100)    NOT NULL                COMMENT '商品名称快照',
  `product_code`  VARCHAR(32)     DEFAULT NULL            COMMENT '商品编码快照',
  `spec`          VARCHAR(100)    DEFAULT NULL            COMMENT '规格',
  `price`         DECIMAL(12,2)   NOT NULL                COMMENT '单价快照',
  `qty`           DECIMAL(12,2)   NOT NULL DEFAULT 1.00   COMMENT '数量',
  `total_amount`  DECIMAL(12,2)   NOT NULL                COMMENT '小计(price*qty)',
  `discount_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00   COMMENT '分摊优惠额',
  `actual_amount` DECIMAL(12,2)   NOT NULL                COMMENT '实付分摊额',
  `join_discount` TINYINT(1)      NOT NULL DEFAULT 1      COMMENT '是否参与折扣(快照)',
  `join_points`   TINYINT(1)      NOT NULL DEFAULT 1      COMMENT '是否参与积分(快照)',
  `points_rate`   DECIMAL(5,2)    NOT NULL DEFAULT 1.00   COMMENT '积分倍率快照',
  `card_id`       BIGINT UNSIGNED DEFAULT NULL            COMMENT '售卡时的会员卡id',
  `created_at`    DATETIME        NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_order_no` (`order_no`),
  KEY `idx_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细';

DROP TABLE IF EXISTS `m_order_payment`;
CREATE TABLE `m_order_payment` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id`       BIGINT UNSIGNED NOT NULL                COMMENT '订单id',
  `order_no`       VARCHAR(32)     NOT NULL                COMMENT '订单号',
  `pay_method`     TINYINT         NOT NULL                COMMENT '支付方式:1储值余额 2现金 3线下扫码 4积分抵扣 5微信在线支付(预留) 9其他',
  `amount`         DECIMAL(12,2)   NOT NULL                COMMENT '支付金额',
  `account_type`   TINYINT         DEFAULT NULL            COMMENT '余额支付时:1本金 2赠送',
  `out_trade_no`   VARCHAR(64)     DEFAULT NULL            COMMENT '线下扫码外部流水号(人工填)',
  `payment_no`     VARCHAR(32)     DEFAULT NULL            COMMENT '在线支付单号(预留)',
  `trade_no`       VARCHAR(64)     DEFAULT NULL            COMMENT '渠道交易号(预留:微信transaction_id)',
  `trade_status`   VARCHAR(32)     DEFAULT NULL            COMMENT '渠道交易状态(预留)',
  `pay_time`       DATETIME        DEFAULT NULL            COMMENT '支付渠道完成时间(预留)',
  `account_flow_no` VARCHAR(32)    DEFAULT NULL            COMMENT '对应资产流水号',
  `idempotency_key` VARCHAR(64)    DEFAULT NULL            COMMENT '幂等键',
  `remark`         VARCHAR(255)    DEFAULT NULL            COMMENT '备注',
  `created_at`     DATETIME        NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_order_no` (`order_no`),
  KEY `idx_method_time` (`pay_method`,`created_at`),
  KEY `idx_flow_no` (`account_flow_no`),
  KEY `idx_payment_no` (`payment_no`),
  KEY `idx_trade_no` (`trade_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单支付明细';

DROP TABLE IF EXISTS `m_order_refund`;
CREATE TABLE `m_order_refund` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `refund_no`      VARCHAR(32)     NOT NULL                COMMENT '退款单号',
  `order_id`       BIGINT UNSIGNED NOT NULL                COMMENT '原订单id',
  `order_no`       VARCHAR(32)     NOT NULL                COMMENT '原订单号',
  `member_id`      BIGINT UNSIGNED NOT NULL                COMMENT '会员id',
  `store_id`       BIGINT UNSIGNED NOT NULL                COMMENT '门店id',
  `refund_amount`  DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '退还本金金额',
  `refund_gift`    DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '退还赠送金额(通常0)',
  `deduct_gift`    DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '扣回赠送金额',
  `deduct_points`  BIGINT          NOT NULL DEFAULT 0      COMMENT '扣回积分',
  `refund_method`  TINYINT         NOT NULL DEFAULT 1      COMMENT '退款方式:1原路退回余额 2现金 3线下转账',
  `coupon_handle`  TINYINT         NOT NULL DEFAULT 1      COMMENT '券处理:1退回可再用 2作废 3不处理',
  `reason`         VARCHAR(500)    NOT NULL                COMMENT '退单原因',
  `status`         TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:1待审 2已通过 3已驳回 4已撤销',
  `operator_id`    BIGINT UNSIGNED DEFAULT NULL            COMMENT '申请人',
  `approver_id`    BIGINT UNSIGNED DEFAULT NULL            COMMENT '审批人',
  `approved_at`    DATETIME        DEFAULT NULL            COMMENT '审批时间',
  `approve_remark` VARCHAR(500)    DEFAULT NULL            COMMENT '审批意见',
  `idempotency_key` VARCHAR(64)    DEFAULT NULL            COMMENT '幂等键',
  `created_at`     DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`     DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_no` (`refund_no`),
  KEY `idx_order` (`order_id`),
  KEY `idx_member_time` (`member_id`,`created_at`),
  KEY `idx_status` (`status`),
  KEY `idx_store_time` (`store_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款/退单记录';

-- =====================================================================
-- 6. 卡券营销
-- =====================================================================

DROP TABLE IF EXISTS `m_coupon_tpl`;
CREATE TABLE `m_coupon_tpl` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tpl_code`        VARCHAR(32)     NOT NULL                COMMENT '模板编码',
  `tpl_name`        VARCHAR(100)    NOT NULL                COMMENT '券名称',
  `coupon_type`     TINYINT         NOT NULL                COMMENT '类型:1满减 2折扣 3代金 4兑换 5次卡',
  `face_value`      DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '面额(满减/代金)或折扣值(如0.88)',
  `min_amount`      DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '最低消费金额',
  `max_discount`    DECIMAL(12,2)   DEFAULT NULL            COMMENT '折扣券最高优惠额',
  `valid_type`      TINYINT         NOT NULL DEFAULT 1      COMMENT '有效期类型:1绝对区间 2领取后N天',
  `valid_start`     DATETIME        DEFAULT NULL            COMMENT '绝对有效期开始',
  `valid_end`       DATETIME        DEFAULT NULL            COMMENT '绝对有效期结束',
  `valid_days`      INT             DEFAULT NULL            COMMENT '领取后有效天数',
  `total_qty`       INT             NOT NULL DEFAULT -1     COMMENT '发行总量,-1不限',
  `issued_qty`      INT             NOT NULL DEFAULT 0      COMMENT '已发放数量',
  `used_qty`        INT             NOT NULL DEFAULT 0      COMMENT '已核销数量',
  `limit_per_member` INT            NOT NULL DEFAULT 1      COMMENT '每人限领数量',
  `scope_store`     VARCHAR(500)    NOT NULL DEFAULT '-1'   COMMENT '适用门店id列表,-1全部',
  `scope_product`   VARCHAR(1000)   NOT NULL DEFAULT '-1'   COMMENT '适用商品id列表,-1全部',
  `exclude_product` VARCHAR(1000)   DEFAULT NULL            COMMENT '排除商品id列表',
  `allow_stack`     TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '是否可与其他券叠加',
  `stack_group`     VARCHAR(32)     DEFAULT NULL            COMMENT '互斥组(同组不可叠加)',
  `claimable`       TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '是否可在小程序自助领取',
  `get_way`         VARCHAR(100)    DEFAULT NULL            COMMENT '领取方式说明',
  `use_rule_desc`   VARCHAR(1000)   DEFAULT NULL            COMMENT '使用规则文案',
  `image`           VARCHAR(500)    DEFAULT NULL            COMMENT '券图',
  `status`          TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0停用 1启用 2已结束',
  `sort_no`         INT             NOT NULL DEFAULT 0      COMMENT '排序',
  `tenant_id`       BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '租户id(预留)',
  `deleted`         TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  `created_at`      DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`      DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tpl_code` (`tpl_code`),
  KEY `idx_status_type` (`status`,`coupon_type`),
  KEY `idx_claimable` (`claimable`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券模板';

DROP TABLE IF EXISTS `m_member_coupon`;
CREATE TABLE `m_member_coupon` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `coupon_no`     VARCHAR(32)     NOT NULL                COMMENT '券码(核销用)',
  `tpl_id`        BIGINT UNSIGNED NOT NULL                COMMENT '模板id',
  `member_id`     BIGINT UNSIGNED NOT NULL                COMMENT '持有会员id',
  `coupon_type`   TINYINT         NOT NULL                COMMENT '券类型(冗余便于查询)',
  `coupon_name`   VARCHAR(100)    NOT NULL                COMMENT '券名称快照',
  `face_value`    DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '面额/折扣快照',
  `min_amount`    DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '最低消费快照',
  `max_discount`  DECIMAL(12,2)   DEFAULT NULL            COMMENT '折扣封顶快照',
  `scope_store`   VARCHAR(500)    NOT NULL DEFAULT '-1'   COMMENT '适用门店快照',
  `scope_product` VARCHAR(1000)   NOT NULL DEFAULT '-1'   COMMENT '适用商品快照',
  `allow_stack`   TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '可叠加快照',
  `stack_group`   VARCHAR(32)     DEFAULT NULL            COMMENT '互斥组',
  `tpl_snapshot`  JSON            DEFAULT NULL            COMMENT '模板完整快照',
  `status`        TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:1未使用 2已锁定 3已使用 4已过期 5已作废',
  `source`        VARCHAR(32)     NOT NULL DEFAULT 'GRANT' COMMENT '来源:ACTIVITY/GRANT/CLAIM/REFERRAL/BIRTHDAY/RECHARGE/EXCHANGE',
  `source_biz_no` VARCHAR(32)     DEFAULT NULL            COMMENT '来源业务单号',
  `valid_start`   DATETIME        NOT NULL                COMMENT '有效期开始',
  `valid_end`     DATETIME        NOT NULL                COMMENT '有效期结束',
  `lock_order_no` VARCHAR(32)     DEFAULT NULL            COMMENT '锁定中的订单号',
  `lock_time`     DATETIME        DEFAULT NULL            COMMENT '锁定时间',
  `order_id`      BIGINT UNSIGNED DEFAULT NULL            COMMENT '使用订单id',
  `used_at`       DATETIME        DEFAULT NULL            COMMENT '使用时间',
  `used_store_id` BIGINT UNSIGNED DEFAULT NULL            COMMENT '使用门店id',
  `gift_to_member_id` BIGINT UNSIGNED DEFAULT NULL        COMMENT '转赠对象(预留)',
  `remark`        VARCHAR(255)    DEFAULT NULL            COMMENT '备注',
  `tenant_id`     BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '租户id(预留)',
  `created_at`    DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`    DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coupon_no` (`coupon_no`),
  KEY `idx_member_status_end` (`member_id`,`status`,`valid_end`),
  KEY `idx_status_end` (`status`,`valid_end`),
  KEY `idx_tpl` (`tpl_id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_lock_order` (`lock_order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员券实例';

DROP TABLE IF EXISTS `m_coupon_use_log`;
CREATE TABLE `m_coupon_use_log` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_coupon_id` BIGINT UNSIGNED NOT NULL             COMMENT '会员券id',
  `coupon_no`     VARCHAR(32)     NOT NULL                COMMENT '券码',
  `tpl_id`        BIGINT UNSIGNED NOT NULL                COMMENT '模板id',
  `member_id`     BIGINT UNSIGNED NOT NULL                COMMENT '会员id',
  `order_id`      BIGINT UNSIGNED DEFAULT NULL            COMMENT '核销订单id',
  `order_no`      VARCHAR(32)     DEFAULT NULL            COMMENT '核销订单号',
  `discount_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00   COMMENT '实际抵扣金额',
  `use_type`      TINYINT         NOT NULL DEFAULT 1      COMMENT '使用方式:1收银台选券 2扫码核销 3输码核销 4会员自助',
  `store_id`      BIGINT UNSIGNED DEFAULT NULL            COMMENT '核销门店',
  `operator_id`   BIGINT UNSIGNED DEFAULT NULL            COMMENT '核销操作人',
  `status`        TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:1正常 2已撤销',
  `revoke_reason` VARCHAR(255)    DEFAULT NULL            COMMENT '撤销原因',
  `used_at`       DATETIME        NOT NULL                COMMENT '核销时间',
  `created_at`    DATETIME        NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_coupon` (`member_coupon_id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_tpl_time` (`tpl_id`,`used_at`),
  KEY `idx_store_time` (`store_id`,`used_at`),
  KEY `idx_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='券核销记录';

DROP TABLE IF EXISTS `m_referral`;
CREATE TABLE `m_referral` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `referrer_id`    BIGINT UNSIGNED NOT NULL                COMMENT '推荐人会员id',
  `invitee_id`     BIGINT UNSIGNED NOT NULL                COMMENT '被推荐人会员id',
  `channel`        VARCHAR(32)     NOT NULL DEFAULT 'POSTER' COMMENT '渠道:POSTER海报/LINK链接/QRCODE门店码',
  `status`         TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:1已绑定 2已结算 3已失效',
  `bind_at`        DATETIME        NOT NULL                COMMENT '绑定时间',
  `settle_at`      DATETIME        DEFAULT NULL            COMMENT '结算时间',
  `trigger_biz_no` VARCHAR(32)     DEFAULT NULL            COMMENT '触发结算的业务单号',
  `created_at`     DATETIME        NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invitee` (`invitee_id`),
  KEY `idx_referrer_status` (`referrer_id`,`status`),
  KEY `idx_bind` (`bind_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐关系(一级)';

DROP TABLE IF EXISTS `m_referral_reward`;
CREATE TABLE `m_referral_reward` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `referral_id`    BIGINT UNSIGNED NOT NULL                COMMENT '推荐关系id',
  `reward_target`  TINYINT         NOT NULL                COMMENT '奖励对象:1推荐人 2被推荐人',
  `member_id`      BIGINT UNSIGNED NOT NULL                COMMENT '获奖会员id',
  `reward_type`    TINYINT         NOT NULL                COMMENT '奖励类型:1积分 2余额 3券 4成长值',
  `reward_value`   DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '奖励值(积分/金额/成长值)',
  `coupon_tpl_id`  BIGINT UNSIGNED DEFAULT NULL            COMMENT '券模板id(券奖励)',
  `coupon_id`      BIGINT UNSIGNED DEFAULT NULL            COMMENT '发放的券实例id',
  `status`         TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:1待发放 2已发放 3发放失败',
  `fail_reason`    VARCHAR(255)    DEFAULT NULL            COMMENT '失败原因',
  `biz_no`         VARCHAR(32)     DEFAULT NULL            COMMENT '关联业务单号',
  `created_at`     DATETIME        NOT NULL                COMMENT '创建时间',
  `issued_at`      DATETIME        DEFAULT NULL            COMMENT '发放时间',
  PRIMARY KEY (`id`),
  KEY `idx_referral` (`referral_id`),
  KEY `idx_member_time` (`member_id`,`created_at`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐奖励结算记录';

-- =====================================================================
-- 7. 次卡 / 服务卡
-- =====================================================================

DROP TABLE IF EXISTS `m_card_product`;
CREATE TABLE `m_card_product` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `card_code`      VARCHAR(32)     NOT NULL                COMMENT '卡产品编码',
  `card_name`      VARCHAR(100)    NOT NULL                COMMENT '卡名称,如"洗车10次卡"',
  `card_type`      TINYINT         NOT NULL DEFAULT 1      COMMENT '类型:1次卡 2期限卡 3储值权益卡',
  `sell_price`     DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '售价',
  `original_price` DECIMAL(12,2)   DEFAULT NULL            COMMENT '原价(划线价)',
  `total_times`    INT             NOT NULL DEFAULT 0      COMMENT '总次数(次卡)',
  `valid_days`     INT             NOT NULL DEFAULT 0      COMMENT '有效天数,0=永久',
  `scope_store`    VARCHAR(500)    NOT NULL DEFAULT '-1'   COMMENT '适用门店,-1全部',
  `description`    VARCHAR(1000)   DEFAULT NULL            COMMENT '权益说明',
  `image`          VARCHAR(500)    DEFAULT NULL            COMMENT '卡图',
  `allow_refund`   TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '是否可退卡',
  `refund_rule`    VARCHAR(500)    DEFAULT NULL            COMMENT '退卡规则说明',
  `status`         TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0下架 1上架',
  `sort_no`        INT             NOT NULL DEFAULT 0      COMMENT '排序',
  `deleted`        TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  `created_at`     DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`     DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_card_code` (`card_code`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='次卡/服务卡产品';

DROP TABLE IF EXISTS `m_member_card`;
CREATE TABLE `m_member_card` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `card_no`         VARCHAR(32)     NOT NULL                COMMENT '会员卡号',
  `member_id`       BIGINT UNSIGNED NOT NULL                COMMENT '会员id',
  `card_product_id` BIGINT UNSIGNED NOT NULL                COMMENT '卡产品id',
  `card_name`       VARCHAR(100)    NOT NULL                COMMENT '卡名称快照',
  `card_type`       TINYINT         NOT NULL DEFAULT 1      COMMENT '卡类型快照',
  `order_id`        BIGINT UNSIGNED DEFAULT NULL            COMMENT '售卡订单id',
  `paid_amount`     DECIMAL(12,2)   NOT NULL DEFAULT 0.00   COMMENT '实付金额',
  `total_times`     INT             NOT NULL DEFAULT 0      COMMENT '总次数',
  `used_times`      INT             NOT NULL DEFAULT 0      COMMENT '已用次数',
  `remain_times`    INT             NOT NULL DEFAULT 0      COMMENT '剩余次数',
  `valid_start`     DATETIME        NOT NULL                COMMENT '生效时间',
  `valid_end`       DATETIME        DEFAULT NULL            COMMENT '失效时间(NULL=永久)',
  `status`          TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:1正常 2已用完 3已过期 4已冻结 5已退卡',
  `buy_store_id`    BIGINT UNSIGNED DEFAULT NULL            COMMENT '购买门店',
  `remark`          VARCHAR(255)    DEFAULT NULL            COMMENT '备注',
  `created_at`      DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`      DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_card_no` (`card_no`),
  KEY `idx_member_status` (`member_id`,`status`),
  KEY `idx_product` (`card_product_id`),
  KEY `idx_status_end` (`status`,`valid_end`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员次卡';

DROP TABLE IF EXISTS `m_member_card_item`;
CREATE TABLE `m_member_card_item` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_card_id` BIGINT UNSIGNED NOT NULL                COMMENT '会员卡id',
  `member_id`      BIGINT UNSIGNED NOT NULL                COMMENT '会员id(冗余)',
  `item_name`      VARCHAR(100)    NOT NULL                COMMENT '权益项名称,如"标准洗车"',
  `product_id`     BIGINT UNSIGNED DEFAULT NULL            COMMENT '关联商品/服务id',
  `total_times`    INT             NOT NULL DEFAULT 0      COMMENT '总次数',
  `used_times`     INT             NOT NULL DEFAULT 0      COMMENT '已用次数',
  `remain_times`   INT             NOT NULL DEFAULT 0      COMMENT '剩余次数',
  `valid_start`    DATETIME        DEFAULT NULL            COMMENT '权益生效时间',
  `valid_end`      DATETIME        DEFAULT NULL            COMMENT '权益失效时间',
  `status`         TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:1正常 2已用完 3已过期 4已冻结',
  `sort_no`        INT             NOT NULL DEFAULT 0      COMMENT '排序',
  `created_at`     DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`     DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_card_status` (`member_card_id`,`status`),
  KEY `idx_member` (`member_id`),
  KEY `idx_product` (`product_id`),
  KEY `idx_end` (`status`,`valid_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='次卡权益项';

DROP TABLE IF EXISTS `m_card_verify_log`;
CREATE TABLE `m_card_verify_log` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `verify_no`           VARCHAR(32)     NOT NULL                COMMENT '核销流水号',
  `member_card_id`      BIGINT UNSIGNED NOT NULL                COMMENT '会员卡id',
  `card_no`             VARCHAR(32)     NOT NULL                COMMENT '卡号',
  `member_card_item_id` BIGINT UNSIGNED NOT NULL                COMMENT '权益项id',
  `member_id`           BIGINT UNSIGNED NOT NULL                COMMENT '会员id',
  `item_name`           VARCHAR(100)    DEFAULT NULL            COMMENT '权益项名称快照',
  `verify_times`        INT             NOT NULL DEFAULT 1      COMMENT '本次核销次数',
  `before_times`        INT             NOT NULL                COMMENT '核销前剩余',
  `after_times`         INT             NOT NULL                COMMENT '核销后剩余',
  `store_id`            BIGINT UNSIGNED NOT NULL                COMMENT '核销门店',
  `operator_id`         BIGINT UNSIGNED DEFAULT NULL            COMMENT '核销操作人',
  `order_id`            BIGINT UNSIGNED DEFAULT NULL            COMMENT '关联订单id(可选)',
  `verify_type`         TINYINT         NOT NULL DEFAULT 1      COMMENT '方式:1扫码 2输码 3收银台选择',
  `remark`              VARCHAR(255)    DEFAULT NULL            COMMENT '备注',
  `idempotency_key`     VARCHAR(64)     DEFAULT NULL            COMMENT '幂等键',
  `verified_at`         DATETIME        NOT NULL                COMMENT '核销时间',
  `created_at`          DATETIME        NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_verify_no` (`verify_no`),
  KEY `idx_card` (`member_card_id`),
  KEY `idx_item` (`member_card_item_id`),
  KEY `idx_member_time` (`member_id`,`verified_at`),
  KEY `idx_store_time` (`store_id`,`verified_at`),
  KEY `idx_idem` (`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='次卡核销记录';

-- =====================================================================
-- 8. 报表统计 / 消息
-- =====================================================================

DROP TABLE IF EXISTS `m_stat_daily`;
CREATE TABLE `m_stat_daily` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `stat_date`         DATE            NOT NULL                COMMENT '统计日期',
  `store_id`          BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '门店id,0=全部门店汇总',
  `new_member_cnt`    INT             NOT NULL DEFAULT 0      COMMENT '新增会员数',
  `active_member_cnt` INT             NOT NULL DEFAULT 0      COMMENT '活跃会员数(当日有消费)',
  `recharge_amount`   DECIMAL(14,2)   NOT NULL DEFAULT 0.00   COMMENT '充值金额(本金)',
  `recharge_cnt`      INT             NOT NULL DEFAULT 0      COMMENT '充值笔数',
  `gift_amount`       DECIMAL(14,2)   NOT NULL DEFAULT 0.00   COMMENT '赠送金额',
  `consume_amount`    DECIMAL(14,2)   NOT NULL DEFAULT 0.00   COMMENT '消费实收金额',
  `consume_cnt`       INT             NOT NULL DEFAULT 0      COMMENT '消费笔数',
  `refund_amount`     DECIMAL(14,2)   NOT NULL DEFAULT 0.00   COMMENT '退款金额',
  `cash_amount`       DECIMAL(14,2)   NOT NULL DEFAULT 0.00   COMMENT '现金收款',
  `offline_amount`    DECIMAL(14,2)   NOT NULL DEFAULT 0.00   COMMENT '线下扫码收款',
  `balance_pay_amount` DECIMAL(14,2)  NOT NULL DEFAULT 0.00   COMMENT '余额扣减金额',
  `online_pay_amount` DECIMAL(14,2)   NOT NULL DEFAULT 0.00   COMMENT '在线支付收款(预留:微信支付)',
  `online_pay_cnt`    INT             NOT NULL DEFAULT 0      COMMENT '在线支付笔数(预留)',
  `points_issued`     BIGINT          NOT NULL DEFAULT 0      COMMENT '发放积分',
  `points_used`       BIGINT          NOT NULL DEFAULT 0      COMMENT '消耗积分',
  `points_expired`    BIGINT          NOT NULL DEFAULT 0      COMMENT '过期积分',
  `balance_end`       DECIMAL(14,2)   NOT NULL DEFAULT 0.00   COMMENT '期末本金余额(储值负债)',
  `gift_balance_end`  DECIMAL(14,2)   NOT NULL DEFAULT 0.00   COMMENT '期末赠送余额',
  `coupon_issued_cnt` INT             NOT NULL DEFAULT 0      COMMENT '发券数量',
  `coupon_used_cnt`   INT             NOT NULL DEFAULT 0      COMMENT '核销券数量',
  `coupon_discount_amount` DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '券抵扣金额',
  `card_sold_cnt`     INT             NOT NULL DEFAULT 0      COMMENT '售卡数量',
  `card_sold_amount`  DECIMAL(14,2)   NOT NULL DEFAULT 0.00   COMMENT '售卡金额',
  `card_verified_cnt` INT             NOT NULL DEFAULT 0      COMMENT '次卡核销次数',
  `created_at`        DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`        DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_date_store` (`stat_date`,`store_id`),
  KEY `idx_date` (`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日统计预聚合(看板数据源)';

DROP TABLE IF EXISTS `m_message_tpl`;
CREATE TABLE `m_message_tpl` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tpl_code`       VARCHAR(50)     NOT NULL                COMMENT '模板编码',
  `tpl_name`       VARCHAR(100)    NOT NULL                COMMENT '模板名称',
  `channel`        VARCHAR(20)     NOT NULL DEFAULT 'WX_SUBSCRIBE' COMMENT '渠道:WX_SUBSCRIBE/INNER/ SMS(预留)',
  `wx_template_id` VARCHAR(64)     DEFAULT NULL            COMMENT '微信订阅消息模板ID',
  `title`          VARCHAR(100)    DEFAULT NULL            COMMENT '标题',
  `content_tpl`    VARCHAR(1000)   NOT NULL                COMMENT '内容模板,支持{memberName}等占位符',
  `page_path`      VARCHAR(200)    DEFAULT NULL            COMMENT '点击跳转小程序页面',
  `status`         TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0停用 1启用',
  `remark`         VARCHAR(255)    DEFAULT NULL            COMMENT '备注',
  `created_at`     DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`     DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tpl_code` (`tpl_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息模板';

DROP TABLE IF EXISTS `m_message_record`;
CREATE TABLE `m_message_record` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tpl_code`     VARCHAR(50)     NOT NULL                COMMENT '模板编码',
  `channel`      VARCHAR(20)     NOT NULL DEFAULT 'WX_SUBSCRIBE' COMMENT '渠道',
  `member_id`    BIGINT UNSIGNED NOT NULL                COMMENT '接收会员id',
  `openid`       VARCHAR(64)     DEFAULT NULL            COMMENT '接收方openid',
  `title`        VARCHAR(100)    DEFAULT NULL            COMMENT '标题',
  `content`      VARCHAR(1000)   NOT NULL                COMMENT '实际内容',
  `biz_no`       VARCHAR(32)     DEFAULT NULL            COMMENT '关联业务单号',
  `status`       TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:1待发送 2已发送 3发送失败 4已读',
  `retry_count`  INT             NOT NULL DEFAULT 0      COMMENT '重试次数',
  `next_retry_at` DATETIME       DEFAULT NULL            COMMENT '下次重试时间',
  `error_msg`    VARCHAR(500)    DEFAULT NULL            COMMENT '错误信息',
  `sent_at`      DATETIME        DEFAULT NULL            COMMENT '发送时间',
  `read_at`      DATETIME        DEFAULT NULL            COMMENT '阅读时间',
  `idempotency_key` VARCHAR(64)  DEFAULT NULL            COMMENT '幂等键',
  `created_at`   DATETIME        NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_member_time` (`member_id`,`created_at`),
  KEY `idx_status_retry` (`status`,`retry_count`,`next_retry_at`),
  KEY `idx_biz` (`biz_no`),
  UNIQUE KEY `uk_idem` (`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息发送记录';

-- =====================================================================
-- 9. 系统配置与日志
-- =====================================================================

DROP TABLE IF EXISTS `m_dict_type`;
CREATE TABLE `m_dict_type` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `dict_type`  VARCHAR(50)     NOT NULL                COMMENT '字典类型编码',
  `dict_name`  VARCHAR(50)     NOT NULL                COMMENT '字典名称',
  `status`     TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0停用 1启用',
  `remark`     VARCHAR(255)    DEFAULT NULL            COMMENT '备注',
  `created_at` DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at` DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dict_type` (`dict_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据字典类型';

DROP TABLE IF EXISTS `m_dict_item`;
CREATE TABLE `m_dict_item` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `dict_type`   VARCHAR(50)     NOT NULL                COMMENT '字典类型编码',
  `item_value`  VARCHAR(50)     NOT NULL                COMMENT '字典值',
  `item_label`  VARCHAR(50)     NOT NULL                COMMENT '字典标签',
  `color`       VARCHAR(20)     DEFAULT NULL            COMMENT '标签颜色',
  `sort_no`     INT             NOT NULL DEFAULT 0      COMMENT '排序',
  `status`      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:0停用 1启用',
  `created_at`  DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`  DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_type_value` (`dict_type`,`item_value`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据字典项';

DROP TABLE IF EXISTS `m_sys_config`;
CREATE TABLE `m_sys_config` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `config_key`   VARCHAR(100)    NOT NULL                COMMENT '参数键',
  `config_value` VARCHAR(500)    NOT NULL                COMMENT '参数值',
  `config_name`  VARCHAR(100)    NOT NULL                COMMENT '参数名称',
  `config_group` VARCHAR(50)     DEFAULT NULL            COMMENT '分组',
  `value_type`   VARCHAR(20)     NOT NULL DEFAULT 'STRING' COMMENT '值类型:STRING/INT/DECIMAL/BOOLEAN/JSON',
  `editable`     TINYINT(1)      NOT NULL DEFAULT 1      COMMENT '是否可编辑',
  `remark`       VARCHAR(500)    DEFAULT NULL            COMMENT '说明',
  `created_at`   DATETIME        NOT NULL                COMMENT '创建时间',
  `updated_at`   DATETIME        NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统参数';

DROP TABLE IF EXISTS `m_op_log`;
CREATE TABLE `m_op_log` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `trace_id`      VARCHAR(64)     DEFAULT NULL            COMMENT '链路id',
  `user_id`       BIGINT UNSIGNED DEFAULT NULL            COMMENT '操作人(系统用户id)',
  `username`      VARCHAR(50)     DEFAULT NULL            COMMENT '操作人账号',
  `store_id`      BIGINT UNSIGNED DEFAULT NULL            COMMENT '操作门店',
  `module`        VARCHAR(50)     NOT NULL                COMMENT '模块:member/account/order/...',
  `op_type`       VARCHAR(32)     NOT NULL                COMMENT '操作类型:CREATE/UPDATE/DELETE/RECHARGE/CONSUME/REFUND/ADJUST/EXPORT/LOGIN',
  `op_desc`       VARCHAR(255)    NOT NULL                COMMENT '操作描述',
  `biz_type`      VARCHAR(32)     DEFAULT NULL            COMMENT '业务类型',
  `biz_id`        BIGINT UNSIGNED DEFAULT NULL            COMMENT '业务主键',
  `biz_no`        VARCHAR(32)     DEFAULT NULL            COMMENT '业务单号',
  `before_value`  JSON            DEFAULT NULL            COMMENT '变更前值',
  `after_value`   JSON            DEFAULT NULL            COMMENT '变更后值',
  `amount`        DECIMAL(14,2)   DEFAULT NULL            COMMENT '涉及金额(便于筛选大额操作)',
  `reason`        VARCHAR(500)    DEFAULT NULL            COMMENT '操作原因(调整类必填)',
  `request_uri`   VARCHAR(255)    DEFAULT NULL            COMMENT '请求地址',
  `request_method` VARCHAR(10)    DEFAULT NULL            COMMENT '请求方法',
  `request_param` VARCHAR(2000)   DEFAULT NULL            COMMENT '请求参数(脱敏后)',
  `ip`            VARCHAR(64)     DEFAULT NULL            COMMENT 'IP',
  `user_agent`    VARCHAR(500)    DEFAULT NULL            COMMENT 'UA',
  `status`        TINYINT         NOT NULL DEFAULT 1      COMMENT '结果:0失败 1成功',
  `error_msg`     VARCHAR(1000)   DEFAULT NULL            COMMENT '错误信息',
  `cost_ms`       INT             DEFAULT NULL            COMMENT '耗时毫秒',
  `created_at`    DATETIME        NOT NULL                COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`,`created_at`),
  KEY `idx_biz` (`biz_type`,`biz_id`),
  KEY `idx_module_time` (`module`,`created_at`),
  KEY `idx_store_time` (`store_id`,`created_at`),
  KEY `idx_op_type_time` (`op_type`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志(审计)';

DROP TABLE IF EXISTS `m_login_log`;
CREATE TABLE `m_login_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `login_type`  TINYINT         NOT NULL DEFAULT 1      COMMENT '登录类型:1管理端 2小程序',
  `user_id`     BIGINT UNSIGNED DEFAULT NULL            COMMENT '系统用户id',
  `member_id`   BIGINT UNSIGNED DEFAULT NULL            COMMENT '会员id',
  `username`    VARCHAR(50)     DEFAULT NULL            COMMENT '账号',
  `ip`          VARCHAR(64)     DEFAULT NULL            COMMENT 'IP',
  `location`    VARCHAR(100)    DEFAULT NULL            COMMENT '归属地',
  `user_agent`  VARCHAR(500)    DEFAULT NULL            COMMENT 'UA',
  `status`      TINYINT         NOT NULL DEFAULT 1      COMMENT '结果:0失败 1成功',
  `msg`         VARCHAR(255)    DEFAULT NULL            COMMENT '提示信息',
  `created_at`  DATETIME        NOT NULL                COMMENT '登录时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`,`created_at`),
  KEY `idx_member_time` (`member_id`,`created_at`),
  KEY `idx_time` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志';

SET FOREIGN_KEY_CHECKS = 1;

-- =====================================================================
-- 10. 初始化数据
-- =====================================================================

-- 10.1 默认门店与员工
INSERT IGNORE INTO `m_store`
(`id`,`store_code`,`store_name`,`city`,`address`,`contact_name`,`contact_phone`,`business_hours`,`status`,`created_at`,`updated_at`)
VALUES (1,'S001','总部旗舰店','上海市','示例路 100 号','店长','13800000000','09:00-22:00',1,NOW(),NOW());

INSERT IGNORE INTO `m_staff`
(`id`,`staff_no`,`staff_name`,`phone`,`store_id`,`position`,`status`,`created_at`,`updated_at`)
VALUES (1,'E001','示例店长','13800000000',1,'店长',1,NOW(),NOW());

-- 10.2 角色
INSERT IGNORE INTO `m_sys_role` (`id`,`role_code`,`role_name`,`data_scope`,`sort_no`,`remark`,`created_at`,`updated_at`) VALUES
(1,'SUPER_ADMIN','超级管理员',1,1,'全部权限',NOW(),NOW()),
(2,'OPERATION','运营主管',1,2,'营销、会员、看板',NOW(),NOW()),
(3,'STORE_MANAGER','店长',2,3,'本店全部业务 + 审批',NOW(),NOW()),
(4,'CASHIER','收银员',3,4,'收银、会员建档、核销',NOW(),NOW()),
(5,'FINANCE','财务',1,5,'流水查询与对账导出',NOW(),NOW());

-- 10.3 默认管理员（密码 Admin@123 的 BCrypt 值；首次登录强制改密）
--      校验方式：BCryptPasswordEncoder().matches("Admin@123", 下列哈希) == true
INSERT IGNORE INTO `m_sys_user`
(`id`,`username`,`password`,`real_name`,`phone`,`staff_id`,`store_id`,`data_scope`,`status`,`created_at`,`updated_at`)
VALUES (1,'admin','$2a$10$bXtmQmfwbMGO6BN5.UOQKOvLnDPCPXo1U6k2t0sS.r3Fw8zXdHnkG','超级管理员','13800000000',1,1,1,1,NOW(),NOW());

INSERT IGNORE INTO `m_sys_user_role` (`user_id`,`role_id`,`created_at`) VALUES (1,1,NOW());

-- 10.4 会员等级（默认 5 级）
INSERT IGNORE INTO `m_member_level`
(`id`,`level_code`,`level_name`,`level_value`,`discount_rate`,`points_rate`,`card_discount_rate`,`upgrade_gift_points`,`is_default`,`valid_months`,`status`,`sort_no`,`remark`,`created_at`,`updated_at`) VALUES
(1,'NORMAL','普通会员',1,1.0000,1.00,1.0000,0,1,0,1,1,'注册即享，消费累计积分',NOW(),NOW()),
(2,'SILVER','银卡会员',2,0.9800,1.20,0.9800,50,0,0,1,2,'累计成长值 1000',NOW(),NOW()),
(3,'GOLD','金卡会员',3,0.9500,1.50,0.9500,100,0,0,1,3,'累计成长值 3000',NOW(),NOW()),
(4,'PLATINUM','铂金会员',4,0.9200,1.80,0.9200,200,0,0,1,4,'累计成长值 8000',NOW(),NOW()),
(5,'DIAMOND','钻石会员',5,0.8800,2.00,0.8800,500,0,0,1,5,'累计成长值 20000，专属客服',NOW(),NOW());

-- 10.5 等级规则（累计成长值门槛，AND 逻辑）
INSERT IGNORE INTO `m_level_rule` (`id`,`level_id`,`rule_type`,`logic_type`,`rule_json`,`priority`,`status`,`created_at`,`updated_at`) VALUES
(1,1,1,'AND','{"items":[{"field":"growth","op":">=","value":0}]}',10,1,NOW(),NOW()),
(2,2,1,'AND','{"items":[{"field":"growth","op":">=","value":1000}]}',20,1,NOW(),NOW()),
(3,3,1,'AND','{"items":[{"field":"growth","op":">=","value":3000}]}',30,1,NOW(),NOW()),
(4,4,1,'AND','{"items":[{"field":"growth","op":">=","value":8000}]}',40,1,NOW(),NOW()),
(5,5,1,'AND','{"items":[{"field":"growth","op":">=","value":20000}]}',50,1,NOW(),NOW());

-- 10.6 充值方案
INSERT IGNORE INTO `m_recharge_plan`
(`id`,`plan_name`,`recharge_amount`,`gift_amount`,`gift_points`,`gift_coupon_tpl_ids`,`status`,`sort_no`,`remark`,`created_at`,`updated_at`) VALUES
(1,'充 200 送 10',200.00,10.00,0,NULL,1,1,'入门档',NOW(),NOW()),
(2,'充 500 送 50',500.00,50.00,200,'2:1',1,2,'热销档',NOW(),NOW()),
(3,'充 1000 送 150',1000.00,150.00,500,'2:1,1:1',1,3,'超值档',NOW(),NOW()),
(4,'充 2000 送 400',2000.00,400.00,1500,NULL,1,4,'尊享档',NOW(),NOW());

-- 10.7 商品分类与示例商品/服务
INSERT IGNORE INTO `m_product_category` (`id`,`parent_id`,`cate_name`,`cate_type`,`sort_no`,`status`,`created_at`,`updated_at`) VALUES
(1,0,'洗车服务',2,1,1,NOW(),NOW()),
(2,0,'汽车美容',2,2,1,NOW(),NOW()),
(3,0,'零售商品',1,3,1,NOW(),NOW());

INSERT IGNORE INTO `m_product`
(`id`,`product_code`,`product_name`,`category_id`,`product_type`,`price`,`unit`,`points_rate`,`join_discount`,`join_points`,`status`,`sort_no`,`created_at`,`updated_at`) VALUES
(1,'SV001','标准洗车',1,2,38.00,'次',1.00,1,1,1,1,NOW(),NOW()),
(2,'SV002','精洗套餐',1,2,88.00,'次',1.00,1,1,1,2,NOW(),NOW()),
(3,'SV003','车身打蜡',2,2,168.00,'次',1.00,1,1,1,3,NOW(),NOW()),
(4,'SV004','内饰深度清洁',2,2,268.00,'次',1.00,1,1,1,4,NOW(),NOW()),
(5,'GD001','玻璃水',3,1,29.90,'瓶',1.00,1,1,1,5,NOW(),NOW()),
(6,'GD002','车载香薰',3,1,69.00,'个',1.00,1,1,1,6,NOW(),NOW());

-- 10.8 次卡产品
INSERT IGNORE INTO `m_card_product`
(`id`,`card_code`,`card_name`,`card_type`,`sell_price`,`original_price`,`total_times`,`valid_days`,`description`,`allow_refund`,`status`,`sort_no`,`created_at`,`updated_at`) VALUES
(1,'CARD_WASH10','洗车 10 次卡',1,299.00,380.00,10,365,'标准洗车 10 次，有效期 12 个月',1,1,1,NOW(),NOW()),
(2,'CARD_WASH30','洗车 30 次卡',1,799.00,1140.00,30,365,'标准洗车 30 次，有效期 12 个月',1,1,2,NOW(),NOW());

-- 说明：次卡权益项（m_member_card_item）在售卡时按卡产品权益配置自动生成，
--       此处不预置数据；卡产品的权益项模板由 tpl_code 约定（如 CARD_WASH10 → 标准洗车 ×10）。

-- 10.9 券模板（示例）
INSERT IGNORE INTO `m_coupon_tpl`
(`id`,`tpl_code`,`tpl_name`,`coupon_type`,`face_value`,`min_amount`,`valid_type`,`valid_days`,`total_qty`,`limit_per_member`,`scope_store`,`scope_product`,`allow_stack`,`claimable`,`use_rule_desc`,`status`,`sort_no`,`created_at`,`updated_at`) VALUES
(1,'NEW_MEMBER_20','新人礼 20 元代金券',3,20.00,0.00,2,30,-1,1,'-1','-1',0,1,'注册后 30 天内有效，无门槛抵扣',1,1,NOW(),NOW()),
(2,'FULL100_20','满 100 减 20',1,20.00,100.00,2,60,-1,1,'-1','-1',0,1,'单笔消费满 100 元可用',1,2,NOW(),NOW()),
(3,'DISCOUNT_88','88 折折扣券',2,0.88,50.00,2,30,-1,1,'-1','-1',1,0,'单笔消费满 50 元可用，最高优惠 50 元',1,3,NOW(),NOW()),
(4,'BIRTHDAY_50','生日礼 50 元券',3,50.00,100.00,2,30,-1,1,'-1','-1',0,0,'生日当月有效，满 100 元可用',1,4,NOW(),NOW()),
(5,'REFERRAL_30','推荐有礼 30 元券',3,30.00,80.00,2,60,-1,3,'-1','-1',0,0,'好友首单后发放',1,5,NOW(),NOW());

UPDATE `m_coupon_tpl` SET `max_discount` = 50.00 WHERE `id` = 3;

-- 10.10 数据字典
INSERT IGNORE INTO `m_dict_type` (`dict_type`,`dict_name`,`created_at`,`updated_at`) VALUES
('member_status','会员状态',NOW(),NOW()),
('member_source','会员来源',NOW(),NOW()),
('gender','性别',NOW(),NOW()),
('balance_biz_type','余额业务类型',NOW(),NOW()),
('point_biz_type','积分业务类型',NOW(),NOW()),
('order_type','订单类型',NOW(),NOW()),
('order_status','订单状态',NOW(),NOW()),
('pay_method','支付方式',NOW(),NOW()),
('coupon_type','券类型',NOW(),NOW()),
('coupon_status','券状态',NOW(),NOW()),
('card_status','次卡状态',NOW(),NOW()),
('refund_status','退款状态',NOW(),NOW()),
('payment_status','支付单状态',NOW(),NOW()),
('payment_channel','支付渠道',NOW(),NOW());

INSERT IGNORE INTO `m_dict_item` (`dict_type`,`item_value`,`item_label`,`color`,`sort_no`,`created_at`,`updated_at`) VALUES
('member_status','1','正常','success',1,NOW(),NOW()),
('member_status','2','冻结','warning',2,NOW(),NOW()),
('member_status','3','注销','info',3,NOW(),NOW()),
('member_source','1','门店建档','',1,NOW(),NOW()),
('member_source','2','小程序注册','',2,NOW(),NOW()),
('member_source','3','批量导入','',3,NOW(),NOW()),
('member_source','4','推荐注册','',4,NOW(),NOW()),
('gender','0','未知','',1,NOW(),NOW()),
('gender','1','男','',2,NOW(),NOW()),
('gender','2','女','',3,NOW(),NOW()),
('balance_biz_type','1','充值','success',1,NOW(),NOW()),
('balance_biz_type','2','消费','primary',2,NOW(),NOW()),
('balance_biz_type','3','退款','warning',3,NOW(),NOW()),
('balance_biz_type','4','调整','info',4,NOW(),NOW()),
('balance_biz_type','5','赠送','success',5,NOW(),NOW()),
('balance_biz_type','6','过期','danger',6,NOW(),NOW()),
('point_biz_type','1','消费获得','success',1,NOW(),NOW()),
('point_biz_type','2','充值赠送','success',2,NOW(),NOW()),
('point_biz_type','3','兑换扣减','primary',3,NOW(),NOW()),
('point_biz_type','4','过期','danger',4,NOW(),NOW()),
('point_biz_type','5','调整','info',5,NOW(),NOW()),
('point_biz_type','6','退单扣回','warning',6,NOW(),NOW()),
('order_type','1','消费','',1,NOW(),NOW()),
('order_type','2','充值','',2,NOW(),NOW()),
('order_type','3','售卡','',3,NOW(),NOW()),
('order_type','4','积分兑换','',4,NOW(),NOW()),
('order_status','1','待支付','info',1,NOW(),NOW()),
('order_status','2','已支付','success',2,NOW(),NOW()),
('order_status','3','已全额退','warning',3,NOW(),NOW()),
('order_status','4','部分退','warning',4,NOW(),NOW()),
('order_status','5','已取消','info',5,NOW(),NOW()),
('order_status','6','挂单','warning',6,NOW(),NOW()),
('pay_method','1','储值余额','primary',1,NOW(),NOW()),
('pay_method','2','现金','success',2,NOW(),NOW()),
('pay_method','3','线下扫码','success',3,NOW(),NOW()),
('pay_method','4','积分抵扣','primary',4,NOW(),NOW()),
('pay_method','9','其他','info',9,NOW(),NOW()),
('coupon_type','1','满减券','',1,NOW(),NOW()),
('coupon_type','2','折扣券','',2,NOW(),NOW()),
('coupon_type','3','代金券','',3,NOW(),NOW()),
('coupon_type','4','兑换券','',4,NOW(),NOW()),
('coupon_type','5','次卡券','',5,NOW(),NOW()),
('coupon_status','1','未使用','success',1,NOW(),NOW()),
('coupon_status','2','已锁定','warning',2,NOW(),NOW()),
('coupon_status','3','已使用','info',3,NOW(),NOW()),
('coupon_status','4','已过期','danger',4,NOW(),NOW()),
('coupon_status','5','已作废','info',5,NOW(),NOW()),
('card_status','1','正常','success',1,NOW(),NOW()),
('card_status','2','已用完','info',2,NOW(),NOW()),
('card_status','3','已过期','danger',3,NOW(),NOW()),
('card_status','4','已冻结','warning',4,NOW(),NOW()),
('card_status','5','已退卡','info',5,NOW(),NOW()),
('refund_status','1','待审核','warning',1,NOW(),NOW()),
('refund_status','2','已通过','success',2,NOW(),NOW()),
('refund_status','3','已驳回','danger',3,NOW(),NOW()),
('payment_status','1','待支付','warning',1,NOW(),NOW()),
('payment_status','2','支付中','warning',2,NOW(),NOW()),
('payment_status','3','已支付','success',3,NOW(),NOW()),
('payment_status','4','已关闭','info',4,NOW(),NOW()),
('payment_status','5','已全额退','info',5,NOW(),NOW()),
('payment_status','6','部分退款','warning',6,NOW(),NOW()),
('payment_status','7','支付失败','danger',7,NOW(),NOW()),
('payment_channel','MOCK','模拟支付(开发)','info',1,NOW(),NOW()),
('payment_channel','WECHAT','微信支付','success',2,NOW(),NOW()),
('payment_channel','ALIPAY','支付宝(预留)','',3,NOW(),NOW());

-- 10.11 系统参数
INSERT IGNORE INTO `m_sys_config` (`config_key`,`config_value`,`config_name`,`config_group`,`value_type`,`remark`,`created_at`,`updated_at`) VALUES
('account.deduct_priority','GIFT_FIRST','余额扣减顺序','account','STRING','GIFT_FIRST 先扣赠送 / PRINCIPAL_FIRST 先扣本金',NOW(),NOW()),
('account.gift_refundable','false','赠送余额是否可退','account','BOOLEAN','恒为 false',NOW(),NOW()),
('account.max_balance','0','单会员余额上限','account','DECIMAL','0 表示不限',NOW(),NOW()),
('account.gift_ratio_limit','1.00','赠送比例上限','account','DECIMAL','赠送额不得超过本金×该比例,防配置错误',NOW(),NOW()),
('points.exchange_rate','100','积分兑换比例','points','INT','多少积分抵 1 元',NOW(),NOW()),
('points.max_deduct_ratio','0.30','积分最高抵扣比例','points','DECIMAL','单笔订单最多用积分抵扣的比例',NOW(),NOW()),
('points.expire_months','12','积分有效期(月)','points','INT','0 表示永不过期',NOW(),NOW()),
('points.min_keep','0','最低保留积分','points','INT','账户需保留的最低积分',NOW(),NOW()),
('growth.per_yuan','1','成长值换算率','level','DECIMAL','每 1 元实付得成长值',NOW(),NOW()),
('points.per_yuan','1','积分换算率','points','DECIMAL','每 1 元实付得积分(再乘等级倍率)',NOW(),NOW()),
('level.downgrade_enabled','false','是否启用降级','level','BOOLEAN','',NOW(),NOW()),
('level.valid_months','0','等级有效期(月)','level','INT','0=永久',NOW(),NOW()),
('order.auto_approve_refund','false','退单是否免审','order','BOOLEAN','',NOW(),NOW()),
('order.large_recharge_need_approve','5000','大额充值审批阈值','order','DECIMAL','单笔充值本金超过该值需店长确认',NOW(),NOW()),
('coupon.notify_days_before_expire','7,3,1','券到期提醒天数','marketing','STRING','逗号分隔',NOW(),NOW()),
('wx.subscribe.enabled','true','订阅消息开关','message','BOOLEAN','',NOW(),NOW()),
('report.dashboard_cache_seconds','600','看板缓存秒数','report','INT','',NOW(),NOW()),
('pay.enabled','false','是否启用在线支付','pay','BOOLEAN','总开关,true 时小程序展示"在线充值"',NOW(),NOW()),
('pay.mock-enabled','true','是否使用模拟支付网关','pay','BOOLEAN','开发期 true;接入微信支付后置 false',NOW(),NOW()),
('pay.channels','WECHAT','启用的支付渠道','pay','STRING','逗号分隔:MOCK/WECHAT/ALIPAY',NOW(),NOW()),
('pay.default-channel','WECHAT','默认支付渠道','pay','STRING','',NOW(),NOW()),
('pay.timeout-minutes','15','支付单超时分钟数','pay','INT','超时自动关单',NOW(),NOW()),
('pay.notify-url','https://your-domain.com/api/open/pay/notify/wechat','支付回调地址','pay','STRING','需公网 HTTPS',NOW(),NOW()),
('pay.auto-sync-seconds','30','支付结果主动查询间隔(秒)','pay','INT','0 表示不主动查询,只依赖回调',NOW(),NOW()),
('pay.max-query-times','5','支付结果最大查询次数','pay','INT','回调丢失时的补偿上限',NOW(),NOW()),
('pay.mock-auto-success-seconds','5','模拟支付自动成功秒数','pay','INT','开发期:下单后 N 秒自动置为已支付',NOW(),NOW());

-- 10.12 消息模板
INSERT IGNORE INTO `m_message_tpl` (`tpl_code`,`tpl_name`,`channel`,`wx_template_id`,`title`,`content_tpl`,`page_path`,`status`,`remark`,`created_at`,`updated_at`) VALUES
('CONSUME_SUCCESS','消费成功通知','WX_SUBSCRIBE',NULL,'消费成功','您于{time}在{storeName}消费{amount}元，获得{points}积分，当前余额{balance}元。','pages/bill/index',1,'需在微信公众平台申请模板后填 wx_template_id',NOW(),NOW()),
('RECHARGE_SUCCESS','充值到账通知','WX_SUBSCRIBE',NULL,'充值成功','您已成功充值{amount}元，赠送{gift}元，当前余额{balance}元。','pages/wallet/index',1,NULL,NOW(),NOW()),
('COUPON_EXPIRE','优惠券到期提醒','WX_SUBSCRIBE',NULL,'优惠券即将过期','您有{couponCount}张优惠券将于{expireDate}过期，请及时使用。','pages/coupon/index',1,NULL,NOW(),NOW()),
('BIRTHDAY_GIFT','生日礼通知','WX_SUBSCRIBE',NULL,'生日祝福','亲爱的{memberName}，祝您生日快乐！专属生日礼已放入卡包，请查收。','pages/coupon/index',1,NULL,NOW(),NOW()),
('CARD_EXPIRE','次卡到期提醒','WX_SUBSCRIBE',NULL,'次卡即将到期','您的{cardName}将于{expireDate}到期，剩余{remainTimes}次。','pages/card/index',1,NULL,NOW(),NOW()),
('REFERRAL_REWARD','推荐奖励到账','WX_SUBSCRIBE',NULL,'推荐奖励已到账','您推荐的好友已完成首单，奖励{reward}已发放。','pages/referral/index',1,NULL,NOW(),NOW()),
('LEVEL_UPGRADE','等级升级通知','WX_SUBSCRIBE',NULL,'恭喜升级','恭喜您升级为{levelName}，享受{discount}折优惠与{pointsRate}倍积分。','pages/member/level',1,NULL,NOW(),NOW()),
('PAY_SUCCESS','在线支付成功通知','WX_SUBSCRIBE',NULL,'支付成功','您已成功支付{amount}元，{bizDesc}已到账，当前余额{balance}元。','pages/wallet/index',1,'预留:小程序在线支付',NOW(),NOW()),
('PAY_FAIL','在线支付失败通知','WX_SUBSCRIBE',NULL,'支付未完成','您的订单{orderNo}未支付成功，可在"我的-待支付"中继续支付。','pages/order/pending',1,'预留',NOW(),NOW());

-- =====================================================================
-- 11. 校验查询（部署后人工执行确认）
-- =====================================================================
-- 表数量应为 47 张（业务与资金 35 张 + 系统/字典/日志/消息 12 张）
-- SELECT COUNT(*) AS table_cnt FROM information_schema.tables WHERE table_schema='member_system';
-- 余额与流水一致（示例：无数据时均为 0）
-- SELECT a.member_id, a.balance, IFNULL(SUM(f.change_amount),0) AS flow_sum
--   FROM m_member_account a LEFT JOIN m_balance_flow f
--     ON f.member_id = a.member_id AND f.account_type = 1
--  GROUP BY a.member_id HAVING a.balance <> flow_sum;
