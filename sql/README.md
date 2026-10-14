# sql/ —— Flyway 迁移脚本的**只读镜像**

> ⚠️ **不要修改本目录下的任何 SQL 文件。**

## 权威副本在哪里

```
权威（唯一可编辑）：backend/src/main/resources/db/migration/
只读镜像（本目录）  ：sql/
```

应用启动时由 Flyway 执行的是 `backend/src/main/resources/db/migration/` 下的脚本。
本目录只是同一批脚本的**副本**，用于：

1. 让设计文档（[02-数据库设计](../docs/02-数据库设计.md)、[06-工程结构与实施计划](../docs/06-工程结构与实施计划.md)）
   里的 `sql/V1__init_schema.sql` 链接继续有效；
2. 给 DBA 一份可脱离工程目录直接执行的 DDL 集合。

## 为什么不能改这里

Flyway 会在 `flyway_schema_history` 表里记录每个已执行脚本的 **checksum**。
改了已发布的迁移脚本（无论改哪一份），下次启动都会直接失败：

```
Migration checksum mismatch for migration version 1
```

本项目已经因为「改 V1」踩过一次，只能靠删库重建解决。所以：

| 想要做的事 | 正确做法 |
|---|---|
| 加字段 / 加表 | 新增 `V{n+1}__xxx.sql`，放到**权威目录** |
| 改已发布脚本 | **不要改**。若确实必须，本地删库重建（`scripts/reset-db.ps1 -Yes`），且仅在尚未上生产时可行 |
| 只想知道某张表的 DDL | 读本目录（只读，随便看） |

## 同步命令

改完权威目录后，用下面这条把镜像刷新一致（**单向：迁移目录 → sql/**）：

```powershell
cd backend\src\main\resources\db\migration
Copy-Item V*.sql ..\..\..\..\..\..\sql\ -Force
```

或在校验两边是否一致（CI 里可用）：

```powershell
Get-ChildItem sql -Filter 'V*.sql' | ForEach-Object {
    $a = (Get-FileHash $_.FullName -Algorithm SHA256).Hash
    $b = (Get-FileHash ("backend\src\main\resources\db\migration\" + $_.Name) -Algorithm SHA256).Hash
    "{0}  identical={1}" -f $_.Name, ($a -eq $b)
}
```

## 脚本清单

| 版本 | 内容 |
|---|---|
| `V1__init_schema.sql` | 47 张业务表 + 索引 + 初始化数据（默认门店/管理员/5 级等级/等级规则/充值方案/商品/次卡/券模板/字典/系统参数/消息模板） |
| `V2__init_menu.sql` | 菜单与权限种子（86 权限码 / 13 顶级菜单）+ 角色授权 |
| `V3__member_import_menu.sql` | 会员导入/导出菜单与按钮权限（404/405） |
| `V4__referral_birthday_message.sql` | `m_message_tpl` 加 `wx_data_map`；新建 `m_birthday_gift_log`、`m_message_subscribe`；`referral.*` / `birthday.*` / `wx.*` 27 项参数；菜单 606/607/610~616 |
| `V5__wechat_pay_config.sql` | `pay.wechat.*` 8 项微信支付参数；支付中心「渠道诊断」菜单 1301 |
| `V6__staff_store_permission.sql` | 员工与账号分离（`m_staff.user_id` 可空）+ 门店数据权限 + 员工管理菜单与按钮权限 |
| `V7__op_log_index.sql` | `m_op_log` 四个查询索引（`idx_created` / `idx_user_time` / `idx_module_time` / `idx_biz_no`）+ 审计参数 |
| `V8__perf_index.sql` | 性能索引（`m_order` 业绩口径 `staff_id+paid_at`、门店日报 `store_id+paid_at`、支付单补偿 `status+created_at`）+ `m_growth_flow.idempotency_key` 列与唯一索引 + `m_member.birthday_md` 生成列与索引 |
| `V9__shedlock_and_ratelimit.sql` | `shedlock` 表（定时任务分布式锁，ShedLock 官方结构）+ `cache.multi-instance` 与 `ratelimit.*` 参数 |

## 直接执行（不通过 Flyway）

仅供全新环境**手工建库**使用；已有 Flyway 记录的环境不要执行，会冲突。

```bash
mysql -uroot -p -e "CREATE DATABASE member_system DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
for f in sql/V*.sql; do mysql -uroot -p member_system < "$f"; done
```

> 更推荐直接启动应用让 Flyway 自动迁移，或用 `scripts/reset-db.ps1 -Yes` 一键重置。
