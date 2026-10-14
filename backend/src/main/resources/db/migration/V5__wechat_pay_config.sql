-- =====================================================================
--  V5: 第三轮 —— 微信支付渠道（APIv3）参数
--
--  说明:
--    本迁移只新增「系统参数」，不新建业务表：
--    支付单、回调日志、渠道抽象在第一轮（V1 + PayChannel）就已预留，
--    接入真实微信支付只需补齐商户参数，无需改表。
--
--  就绪判定（管理端「支付中心 → 渠道状态」可见）:
--    商户号 + 商户证书序列号 + AppID + 32位APIv3密钥
--    + 可解析的商户私钥 + HTTPS 回调地址
--    平台证书可不配：留空时运行期通过 /v3/certificates 自动下载并缓存。
--
--  安全提示:
--    私钥与 APIv3 密钥属于最高级别机密。生产环境建议不要填在数据库里，
--    而是把密钥文件放到服务器上（权限 600），此处只填文件路径
--    （支持 "file:/opt/member/cert/apiclient_key.pem" 写法）。
-- =====================================================================

USE `member_system`;

INSERT IGNORE INTO `m_sys_config`
(`config_key`,`config_value`,`config_name`,`config_group`,`value_type`,`editable`,`remark`,`created_at`,`updated_at`)
VALUES
('pay.wechat.mch-id','','微信支付-商户号','pay-wechat','STRING',1,'微信支付商户平台「账户中心-商户信息」中的商户号',NOW(),NOW()),
('pay.wechat.mch-serial-no','','微信支付-商户证书序列号','pay-wechat','STRING',1,'商户API证书的序列号（openssl x509 -in apiclient_cert.pem -noout -serial 可查）',NOW(),NOW()),
('pay.wechat.private-key','','微信支付-商户私钥','pay-wechat','STRING',1,'PKCS#8 PEM 内容（换行写 \\n）或文件路径（file:/path/apiclient_key.pem）。PKCS#1 格式需先转换',NOW(),NOW()),
('pay.wechat.apiv3-key','','微信支付-APIv3密钥','pay-wechat','STRING',1,'必须为 32 位字符串；用于解密回调报文与平台证书',NOW(),NOW()),
('pay.wechat.appid','','微信支付-AppID','pay-wechat','STRING',1,'JSAPI 下单必填；留空则复用 wx.miniapp.appid',NOW(),NOW()),
('pay.wechat.notify-url','','微信支付-回调地址','pay-wechat','STRING',1,'必须是公网可访问的 HTTPS 地址；留空则复用 pay.notify-url',NOW(),NOW()),
('pay.wechat.api-base','https://api.mch.weixin.qq.com','微信支付-接口基址','pay-wechat','STRING',1,'一般无需修改；联调沙箱时可改',NOW(),NOW()),
('pay.wechat.platform-cert','','微信支付-平台证书','pay-wechat','STRING',1,'用于回调验签；留空则自动调用 /v3/certificates 下载并缓存 6 小时',NOW(),NOW());

-- 支付中心菜单下补一个「渠道诊断」按钮权限（复用既有支付页面）
INSERT IGNORE INTO `m_sys_menu`
(`id`,`parent_id`,`menu_type`,`menu_name`,`perm_code`,`route_path`,`component`,`icon`,`sort_no`,`visible`,`status`,`created_at`,`updated_at`)
VALUES
(1301,1300,2,'渠道诊断','payment:channel:list','/payment/channels','payment/index',NULL,1,1,1,NOW(),NOW());

INSERT IGNORE INTO `m_sys_role_menu` (`role_id`,`menu_id`,`created_at`)
SELECT 1, id, NOW() FROM `m_sys_menu`;

INSERT IGNORE INTO `m_sys_role_menu` (`role_id`,`menu_id`,`created_at`)
SELECT r.id, 1301, NOW() FROM `m_sys_role` r WHERE r.role_code IN ('OPERATION','FINANCE');
