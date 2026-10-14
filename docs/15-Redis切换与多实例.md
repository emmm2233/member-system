# 会员管理系统 · Redis 切换与多实例部署

> 对应 [13-优化方案](./13-优化方案.md) 的 **P2-2**，是 [14-优化实施记录](./14-优化实施记录.md) §4.2 的落地手册。
>
> 一句话结论：**Redis 在本项目里不是性能优化，而是多实例部署的正确性前提。**
> 本文件记录「怎么切、切完长什么样、实测暴露了哪些只在切换后才出现的问题」。

---

## 1. 为什么必须切（不是速度问题）

缓存里放的不只是查询结果，还有**正确性数据**。多实例 + Caffeine 会让它们**静默失效**：

| 缓存内容 | 键 | 多实例下的后果 |
|---|---|---|
| 幂等键 | `idem:*` | 幂等只在本实例生效 → **跨实例重复提交挡不住** |
| 登录失败计数 | `login:fail:*` | 5 次锁定只在本实例 → 请求打到另一实例即**绕过锁定** |
| 业务序列号 | `seq:*` | 两个实例可能算出相同单号（有唯一索引兜底，但退化为重试） |
| 登录态/权限 | `loginuser:*` | 改权限后另一实例仍是旧权限（最长 5 分钟窗口） |
| 微信 access_token / 平台证书 | `wx:*` / `wxpay:*` | 各实例各取一次，**互相顶掉** |

这份表的机器可读版本就是 `GET /api/admin/system/cache-status` 的 `risks` 数组 ——
单实例用 Caffeine 时它会把后果一条条列出来，切到 Redis 后 `risks` 变为空。

---

## 2. 怎么切（五步）

```powershell
# 1) 起 Redis（本机验证用便携版，装进 tools/，该目录已被 .gitignore 忽略）
powershell -ExecutionPolicy Bypass -File scripts\start-redis.ps1
#    → redis UP port=6379 redis_version:8.10.1 dbsize=0
#    停掉：powershell -ExecutionPolicy Bypass -File scripts\start-redis.ps1 -Stop
```

2. **切换缓存实现**。⚠️ 有一个坑必须知道：

   `application.yml` 的基础段是 `member.cache.type: ${MEMBER_CACHE_TYPE:local}`，
   但 **local profile 里写死了 `type: local`**，而 profile 配置优先于基础段 ——
   所以**设环境变量 `MEMBER_CACHE_TYPE=redis` 不会生效**（这正是 `run-all-tests.ps1 -CacheType`
   用命令行参数而不是环境变量的原因：命令行参数优先级最高）。

   ```powershell
   # 生产：改 application-prod.yml 或传启动参数（推荐参数，便于回滚）
   java -jar target\member-system.jar --spring.profiles.active=prod --member.cache.type=redis
   ```

3. **确认真的生效**（不要只看启动没报错）：

   ```
   日志：  [缓存] 使用 Redis 实现
           [缓存] 实现=redis, 多实例=false —— 幂等键/登录锁定/序列号跨实例一致
   接口：  GET /api/admin/system/cache-status
           → cacheType=redis, configuredType=redis, multiInstanceSafe=true, risks=[]
   ```

4. **声明多实例**：多实例部署时把 `m_sys_config.cache.multi-instance` 置 `true`
   （管理端可改）。这个开关只用于**告警**：Caffeine + 多实例 = 启动与就绪检测都会喊出来。

5. **跑一次全量回归**（这条最关键，见 §3）：

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\run-all-tests.ps1 -CacheType redis
   ```

---

## 3. 实测：切过去暴露了 5 个问题（都只在 Redis 模式下出现）

第一次切换跑全套自检的结果是 **549 / 13 FAIL**，13 条失败全部指向 5 个根因：

| # | 现象（自检失败项） | 根因 | 修复 |
|---|---|---|---|
| 1 | 看板 9 项失败：`overview todayOrders=`、`liability=`、`level distribution levels=0`、`cache hits 1->1` | `RedisCacheServiceImpl` 用裸 `ObjectMapper`，**没有 `JavaTimeModule`** → 看板结果里的 `LocalDateTime` 无法序列化 | 注册 `JavaTimeModule`，时间写 ISO 字符串（Redis 里可读） |
| 2 | 微信支付 4 项失败：`pay.wechat.private-key 无法解析：缓存值无法序列化为 JSON：sun.security.rsa.RSAPrivateCrtKeyImpl` | `PrivateKey` 是**接口**，JSON 无法还原类型；而 `set` 当时**直接抛异常** | 缓存里改存 `算法:Base64(PKCS#8)` 字符串，读时还原 |
| 3 | （未直接失败，属潜在事故）平台证书缓存读回来是 `LinkedHashMap` | `Map<String, X509Certificate>` 里的 `X509Certificate` 是抽象类 | 改存 `序列号 → Base64(DER)`；否则回调验签时抛 `ClassCastException`，表现为「回调一直验签失败」 |
| 4 | 每个请求一条 WARN（一次全量自检刷出 **360 条**）：`反序列化失败 key 类型=LoginUser ... Cannot construct instance of GrantedAuthority` | `LoginUser.getAuthorities()` 返回抽象类型；`enabled` 这类只有 getter 的派生字段也没有 setter | `@JsonIgnore` 掉派生视图（`authorities` / `enabled` / `accountNonLocked` / `memberSide`）；mapper 关闭 `FAIL_ON_UNKNOWN_PROPERTIES` |
| 5 | 上面第 1、2 条的共同放大器 | **缓存写失败被冒泡成业务失败**（原设计「宁可抛错暴露」） | `set` 改为 WARN + 跳过：缓存是可选加速层，写不进去最多慢一点，绝不该让业务失败 |

修复后：

```
Redis 模式   ：executed assertions = 564  |  TOTAL: PASS=564 FAIL=0
Caffeine 模式：executed assertions = 564  |  TOTAL: PASS=564 FAIL=0
```

> 这 5 个问题里有 4 个在 Caffeine 下**永远不会出现**（Caffeine 存的是对象引用，不走 JSON）。
> 换句话说：**不切一次后端，「补齐商户参数即可真实收款」这句话是没有依据的** ——
> 第 2、3 条正好落在支付这条资金链路上。

---

## 4. 守卫：怎么知道当前配置安不安全

| 手段 | 覆盖 |
|---|---|
| 启动日志 WARN（`CacheReadinessChecker`） | 「声明多实例 + 用 Caffeine」时启动即喊，并列出会静默失效的能力 |
| `GET /api/admin/system/cache-status` | `cacheType` / `configuredType` / `multiInstance` / `multiInstanceSafe` / `risks[]` / `suggestion` |
| `m_sys_config.cache.multi-instance` | 声明维度（只告警，不切换实现 —— 实现由 Bean 创建时确定，放数据库会造成「改了没生效」的误解） |
| round10 §3/§4 断言 | 两种后端下的不变量：`multiSafe == isRedis`；Caffeine 列 ≥4 条风险、Redis 列 0 条 |
| round10 §9 断言 | **后端日志零缓存告警**（`值无法序列化为 JSON` / `反序列化失败`）—— §3 的 5 个问题里有 4 个会被它拦下 |

---

## 5. 两种后端的行为对齐点

| 语义 | Caffeine | Redis |
|---|---|---|
| 复杂值 | 存对象引用 | JSON 往返（`FAIL_ON_UNKNOWN_PROPERTIES=false`，容忍字段增删 —— 滚动发布期间新旧实例共用缓存） |
| 计数器 `increment` | 有界缓存 + **过期时刻记在值里**（每个键 TTL 不同：序列号 48h / 秒级序列 1h / 限流 5s） | `INCR` + 首次 `EXPIRE`（每次 INCR 都 EXPIRE 会让窗口永不滚动） |
| 按前缀失效 | 遍历本地键集 | `SCAN` 游标 + 去重（**绝不用 `KEYS`**：会阻塞 Redis 单线程） |
| 简单值 | 原样存 | 原样存（不给 String/Long/Boolean 套 JSON 引号） |
| 不可序列化的值 | 能存对象，但**多实例下没有意义** | WARN + 跳过，并由 §9 断言盯着 |

---

## 6. 还没做 / 上生产前要补

| 项 | 说明 |
|---|---|
| **多实例真实并发回归** | 目前是「单实例 + Redis」，验证了实现可切换；**没有**起两个实例互抢幂等键/锁的端到端验证 |
| 高可用 | 未验证哨兵/集群；Lettuce 默认配置，未调连接池与超时 |
| 认证与传输 | 本机验证实例**无密码**；生产必须设 `spring.data.redis.password`，跨机房走 TLS |
| 持久化 | 本机验证用 `--save "" --appendonly no`（临时实例）。生产建议开 AOF：缓存里放着幂等键/锁定/序列号，**Redis 丢数据 = 这些窗口重置**（序列号有唯一索引兜底，幂等窗口重置意味着「理论上的重复提交窗口」） |
| Redis 不可用时的降级 | 当前策略是「限流故障放行、缓存读失败回源」，但没有做过 Redis 宕机的整体演练 |
