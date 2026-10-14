# scripts/ —— 运维与自检脚本

> **编码铁律**（Windows PowerShell 5.1 按 ANSI 读取无 BOM 的 UTF-8 文件）：
> - **不需要中文输出的脚本 → 写成纯 ASCII，不加 BOM**（如 `round5-test.ps1`、`run-all-tests.ps1`）。
>   这样无论用什么编码读取都不会坏，是最省心的选择。
> - **需要中文的脚本 → 必须写 UTF-8 + BOM**（如 `reset-db.ps1`、`start-backend.ps1`、`money-chain-test.ps1`）。
> - 改完跑一次 `node scripts\tools\ensure-bom.js`（自动补齐/去掉 BOM 并统计断言数），
>   再跑一次 `[System.Management.Automation.Language.Parser]::ParseFile()` 做语法检查。
> - 需要校验中文文案时，从接口读回原值比对，不要把中文断言写进测试脚本。

### ⚠️ 无 BOM 不是「显示乱码」这么简单——它会**静默吃掉下一行代码**

本次文件整理时发现并修复的真实事故：

`money-chain-test.ps1` 里有 3 行中文注释（说明退单按资金来源原路退回），
文件本身是 UTF-8 但**没有 BOM**。PowerShell 5.1 按 ANSI（GBK）解码时：

```
注释行结尾：  … 退款不会凭空增加）。
                                       └─ '。' 的 UTF-8 字节是 E3 80 82
GBK 解码：    E3 80 → 一个汉字；剩下 82 与紧跟的换行 0x0A 组成一个合法 GBK 双字节
结果：        换行被「吃掉」，下一行代码被并入注释
```

被吃掉的正是资金级断言：

```powershell
Check 'principal restored to 600' ($afterRefund.data.balance -eq 600) ...
```

**危害在于它是静默的**：脚本不报语法错误、不报 FAIL，只是少跑一条断言，
输出 `RESULT: PASS=28 FAIL=0` 看上去全绿，实际退单后本金是否正确从 600 恢复**根本没被验证**。

| | 断言数 | 结果 |
|---|---|---|
| 无 BOM（整理前） | 28 | `PASS=28 FAIL=0` ← 少跑一条，假绿 |
| 有 BOM（整理后） | 29 | `PASS=29 FAIL=0` |

**所以：只要一个 .ps1 里有中文，就必须有 BOM。** 判断方法（交给守卫脚本即可）：

```powershell
node scripts\tools\ensure-bom.js          # 扫描全部 .ps1：BOM 与是否含中文是否匹配
node scripts\tools\ensure-bom.js --check   # 只检查不写入（CI 用），不一致退出码 1
```

### 断言总数哨兵：**「声明的断言数」必须等于「实际跑出来的 PASS+FAIL」**

编码只是让断言消失的一种原因。**任何在 `Check` 参数表达式里抛出的异常都会跳过这次
`Check` 调用**，断言同样会静默消失（第二轮实施的真实事故，见
[docs/14 §5.3](../docs/14-优化实施记录.md)）：`[int](Sql "...")` 拿到 mysql 的错误文本时
抛的是「语句级终止错误」，`$ErrorActionPreference='Continue'` 不会让它停下来，
只是那一次 `Check` 再也没执行 —— 36 条声明跑出 34 条，输出依旧全绿。

`run-all-tests.ps1` 现在会自动做这个比对：

```powershell
node scripts\tools\ensure-bom.js --json    # {"round10-test.ps1":36, ...} 声明数
# run-all-tests.ps1 把它与每套自检的 PASS+FAIL 逐套比对，不等即判 FAIL
```

十二个测试脚本当前均已核对一致（12/29/33/29/27/49/115/102/57/40/27/44），合计 **564** 项
（Caffeine 与 Redis 两种缓存后端各跑一遍都是 564/564）。

```
scripts/
├─ install-maven.ps1        环境：下载便携 Maven 到 tools/
├─ start-backend.ps1        环境：打包 + 启动后端 + 等待就绪
├─ start-redis.ps1          环境：起/停便携 Redis（验证 member.cache.type=redis）
├─ reset-db.ps1             环境：重置开发数据库
├─ run-all-tests.ps1        ★ 一键回归（推荐入口，含断言数哨兵；-CacheType redis 整套跑 Redis）
├─ smoke-test-ascii.ps1     自检 1/12：基础功能            12 项
├─ money-chain-test.ps1     自检 2/12：资金链路            29 项
├─ mp-flow-test.ps1         自检 3/12：小程序端全链路      33 项
├─ round2-test.ps1          自检 4/12：次卡/等级/看板      29 项
├─ round3-test.ps1          自检 5/12：挂单/导入导出       27 项
├─ round4-test.ps1          自检 6/12：WEB 后端端点        49 项
├─ round5-test.ps1          自检 7/12：推荐/生日/消息/微信支付 115 项
├─ round6-test.ps1          自检 8/12：员工/门店/业绩归属        102 项
├─ round7-test.ps1          自检 9/12：操作审计/资产调整幂等      57 项
├─ round8-test.ps1          自检 10/12：性能索引/生成列/N+1      40 项
├─ round9-test.ps1          自检 11/12：看板缓存/前端分包/类型   27 项
├─ round10-test.ps1         自检 12/12：ShedLock/缓存就绪/限流   44 项
└─ tools/                   开发期一次性工具（不参与日常运维）
   ├─ ensure-bom.js         ★ 编码守卫 + 断言数统计（--check / --json）
   ├─ audit-coverage.js     ★ 写接口留痕覆盖核查（--strict，比对豁免基准）
   ├─ add-scheduler-lock.js 给所有 *Job.java 批量补 @SchedulerLock
   ├─ apply-ratelimit.js    给小程序端/回调控制器批量补 @RateLimit
   ├─ apply-annotations.js  批量补审计注解（一次性）
   ├─ apply-frontend-idem.js 前端幂等键补齐（一次性）
   ├─ fix-slot-typing.js    Element Plus 插槽类型修复（一次性）
   ├─ fix-ts-errors{,2,3}.js 前端类型错误清零（一次性）
   ├─ GenBcrypt.java        生成/校验 BCrypt 密码哈希
   ├─ GenImportExcel.java   生成会员导入测试用 xlsx
   ├─ check-schema.js       DDL 结构检查（表数/主键/引擎/重复表）
   ├─ inspect-sql.js        DDL 完整性检查（BOM/乱码/换行/种子数据）
   ├─ normalize-sql.js      DDL 规范化：迁移目录 → sql/ 镜像（含乱码检测）
   ├─ ascii-fix.js          把 ps1 转为纯 ASCII + BOM
   ├─ make-test-ascii.js    同上，针对 round2-test.ps1
   ├─ fix-test-script.js    一次性语法修复（历史遗留）
   └─ debug-recharge.ps1    充值链路排查（历史遗留）
```

> `*（一次性）` 标记的工具是「改完即弃」的批量改写脚本，保留下来是为了让改动**可重放、可审计**
> （例如以后新增一个小程序控制器，改一行 `TARGETS` 再跑 `apply-ratelimit.js` 即可）。

---

## 一键回归（推荐）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run-all-tests.ps1
```

它做六件事：

1. 停掉正在运行的后端；
2. `DROP DATABASE` + `CREATE DATABASE`（保证十二套自检从已知种子数据出发）；
3. 启动后端并轮询 `/actuator/health` 直到 `UP`（Flyway 会在此把 `V1`~`V9` 全部迁移到位）；
4. **打印实际生效的缓存实现**（`[缓存] 使用 Redis 实现` / `使用本地 Caffeine 实现`）——
   参数写错或没生效时，不然会出现「跑了一整套却验的不是目标配置」；
5. 依次跑完十二套自检，汇总 `PASS/FAIL`；
6. **比对「声明断言数」与「实际 PASS+FAIL」**，任一不等或任一 FAIL 则退出码为 1。

### 整套跑在 Redis 上（验证多实例前提）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\start-redis.ps1                  # 起便携 Redis（tools/，已 gitignore）
powershell -ExecutionPolicy Bypass -File scripts\run-all-tests.ps1 -CacheType redis
powershell -ExecutionPolicy Bypass -File scripts\start-redis.ps1 -Stop            # 用完停掉
```

**为什么用命令行参数而不是环境变量 `MEMBER_CACHE_TYPE`**：local profile 里写死了
`member.cache.type: local`，profile 配置优先于 `application.yml` 基础段里的 `${MEMBER_CACHE_TYPE:local}`，
环境变量会被静默忽略；命令行参数优先级最高。切换手册与实测坑位见
[docs/15-Redis切换与多实例](../docs/15-Redis切换与多实例.md)。

> **为什么用 `Win32_Process.Create` 起 JVM**：这个脚本常以「后台作业」方式运行，
> 而作业结束时整棵进程树会被销毁。若用 `Start-Process`，脚本自己启动的后端会被一起杀掉，
> 端口 8080 直接失联。改用 WMI 创建进程后，父进程是 WMI provider，不在作业进程树内。

**十二套自检是有顺序、有依赖的**（后面的轮次会复用前面创建的会员与订单），
所以要么用 `run-all-tests.ps1` 一把跑完，要么在同一套已初始化的库上按顺序跑。

---

## 单跑某一套

后端需已启动（`scripts\start-backend.ps1` 或手动 `java -jar`）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\smoke-test-ascii.ps1
powershell -ExecutionPolicy Bypass -File scripts\money-chain-test.ps1
powershell -ExecutionPolicy Bypass -File scripts\mp-flow-test.ps1
powershell -ExecutionPolicy Bypass -File scripts\round2-test.ps1
powershell -ExecutionPolicy Bypass -File scripts\round3-test.ps1
powershell -ExecutionPolicy Bypass -File scripts\round4-test.ps1
powershell -ExecutionPolicy Bypass -File scripts\round5-test.ps1
powershell -ExecutionPolicy Bypass -File scripts\round6-test.ps1
powershell -ExecutionPolicy Bypass -File scripts\round7-test.ps1
powershell -ExecutionPolicy Bypass -File scripts\round8-test.ps1
powershell -ExecutionPolicy Bypass -File scripts\round9-test.ps1
powershell -ExecutionPolicy Bypass -File scripts\round10-test.ps1
```

| 脚本 | 覆盖内容 |
|---|---|
| `smoke-test-ascii.ps1` | 登录/权限/菜单/建档/手机号唯一/360° 视图/流水/对账 |
| `money-chain-test.ps1` | 充值（赠送+送分+送券）、幂等、消费（赠送优先）、余额不足、退单原路退回（本金恢复）、四项账实核对、支付渠道与支付对账 |
| `mp-flow-test.ps1` | Mock 登录、绑手机、动态核销码签名与篡改拒绝、充值方案、待支付单、统一下单、回调入账、赠券、防重复入账、角色隔离 |
| `round2-test.ps1` | 等级规则解析与升级、批量重算、次卡售卡/核销/冻结/延期、看板 9 类报表、负债 |
| `round3-test.ps1` | 挂单/取单/弃单/挂单不动资产、导入模板、合法与混合导入（部分成功+错误明细）、导出 xlsx |
| `round4-test.ps1` | 券模板 CRUD、发券向导预估与发放、等级规则读写、RBAC、日志查询、看板端点 |
| `round5-test.ps1` | 消息模板与渠道降级、推荐绑定防刷与首单结算与月度上限、生日礼幂等与闰日、消息中心与订阅额度、微信支付就绪检测与本地自检 |
| `round6-test.ps1` | 门店 CRUD 与删除守卫、员工 CRUD 与工号生成、账号关联约束、**离职不动账号 / 停用账号不动员工**、业绩按 staff_id 归属与结算自动回落、权限隔离 |
| `round7-test.ps1` | 操作留痕覆盖面（改一次记一条、参数脱敏、失败也留痕）、资产调整同幂等键二次提交被挡且余额只变一次、四处新增 `@Idempotent` 端点 |
| `round8-test.ps1` | 五组索引的**可索引性对照**（`FORCE INDEX` + 新旧谓词）、生日生成列、`m_growth_flow` 幂等键唯一索引、N+1 批量化后的查询次数 |
| `round9-test.ps1` | 看板读缓存的命中/失效/关闭三态、`cache-evict`、`dist` 体积与分包上限、`vue-tsc` 零错误、对账仍干净 |
| `round10-test.ps1` | `shedlock` 表结构契约、**从 JVM 外部持锁证明任务被跳过、释放后恢复**、缓存就绪报告、多实例声明告警、限流触发/窗口滚动/不同端点独立额度/开关、`10005` |

---

## 环境脚本

```powershell
# 首次：下载便携 Maven 3.9.9 到 tools/（不污染系统）
powershell -ExecutionPolicy Bypass -File scripts\install-maven.ps1

# 打包并启动后端（前台，Ctrl+C 停止）
powershell -ExecutionPolicy Bypass -File scripts\start-backend.ps1

# 重置开发库（会 DROP DATABASE，需 -Yes 确认）
powershell -ExecutionPolicy Bypass -File scripts\reset-db.ps1 -Yes

# 起/停便携 Redis（可选：验证 member.cache.type=redis，见 docs/15）
powershell -ExecutionPolicy Bypass -File scripts\start-redis.ps1
powershell -ExecutionPolicy Bypass -File scripts\start-redis.ps1 -Stop
```

> `mvn` 与 `java` 都不在 PATH 上也能跑：脚本用 `tools\apache-maven-3.9.9`，
> 并用 `JAVA_HOME=C:\Program Files\Java\jdk-21.0.11`。
>
> `start-redis.ps1` 需要 `tools\redis-<ver>\redis-server.exe`（`tools/` 已被 .gitignore 忽略，
> 所以便携 Redis 不会进仓库）。获取方式、版本与验证记录见
> [docs/15-Redis切换与多实例](../docs/15-Redis切换与多实例.md)。

---

## `tools/` 里的开发期工具

### 依赖与使用前提

`GenImportExcel.java` 与 `GenBcrypt.java` 需要后端依赖的 classpath，先生成一次：

```powershell
cd backend
..\tools\apache-maven-3.9.9\bin\mvn.cmd dependency:build-classpath -Dmdep.outputFile=cp.txt
```

### 重新生成会员导入测试数据

`round3-test.ps1` 依赖 `logs\excel\member-import-valid.xlsx` 与 `member-import-mixed.xlsx`。
若被删除，用下面两条重建：

```powershell
$cp = (Get-Content backend\cp.txt -Raw)
javac -encoding UTF-8 -cp ("scripts\tools;" + $cp) -d logs\excel-classes scripts\tools\GenImportExcel.java
java -cp ("logs\excel-classes;" + $cp) GenImportExcel "logs/excel"
```

### 生成 BCrypt 密码哈希

```powershell
$cp = (Get-Content backend\cp.txt -Raw)
java -cp $cp scripts\tools\GenBcrypt.java 'Admin@123'
```

> 项目里管理员密码哈希写在 Flyway `V1` 里，改密码策略时用它重新生成。

### 写接口留痕覆盖核查

```powershell
node scripts\tools\audit-coverage.js           # 列出默认留痕与豁免清单
node scripts\tools\audit-coverage.js --strict  # 豁免清单与基准不一致即退出码 1
```

判定口径：**控制器上的 `POST/PUT/DELETE` 映射默认全部留痕**，只有显式 `@NoAudit`
（需写理由）才豁免；`--strict` 会核对豁免清单是否被人偷偷加过。

### DDL 校验与规范化

```powershell
node scripts\tools\check-schema.js         # 表数/主键/引擎/重复表
node scripts\tools\inspect-sql.js          # BOM/乱码/换行/种子数据
node scripts\tools\normalize-sql.js --check # 只检查不写入
node scripts\tools\normalize-sql.js         # 迁移目录 → sql/ 镜像（含乱码检测）
```

> **只用 Node 处理 UTF-8 文件**。PowerShell 5.1 的 `-replace` / `Set-Content` 会把中文写成 GBK，
> 本项目早期因此把 SQL 里的「主键」变成 `涓婚敭`，测试脚本也被破坏过语法。

### 把 ps1 脚本转为纯 ASCII + BOM

```powershell
node scripts\tools\ascii-fix.js scripts\round5-test.ps1
```

---

## 运行产物（`logs/`，已被 .gitignore 忽略）

| 路径 | 说明 | 可删？ |
|---|---|---|
| `logs\backend-stdout.log` | 后端运行日志（ShedLock/缓存/限流的告警都在这里） | 可删（重启会重建） |
| `logs\all-tests.log` | `run-all-tests.ps1` 的汇总输出 | 可删 |
| `logs\excel\*.xlsx` | **round3 自检依赖的导入测试数据** | ⚠️ 删了要按上文重新生成 |
| `logs\wxpay\*.pem` | round5 自检临时生成的 RSA 私钥（用 openssl 现生成现用） | 可删（round5 每次重建） |

---

## 历史实施记录里的路径说明

早期实施记录（[09](../docs/09-实施记录.md)、[10](../docs/10-实施记录-第二轮.md)）中列出的
`scripts/normalize-sql.js`、`scripts/inspect-sql.js`、`scripts/check-schema.js`、
`scripts/make-test-ascii.js`、`scripts/GenBcrypt.java`、`scripts/GenImportExcel.java`
**现已移至 `scripts/tools/`**（这些是开发期一次性工具，不应与日常运维脚本混在一起）。
文档保持原样作为历史记录，实际路径以本文件为准。
