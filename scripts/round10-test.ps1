# Round 10 smoke test: ShedLock (P2-1) + cache readiness (P2-2) + rate limit (P2-3)
# Usage: powershell -ExecutionPolicy Bypass -File scripts\round10-test.ps1
#
# Why section 2 exists: a shedlock table that nobody uses passes every structural check
# and still leaves the deployment single-instance-only. So we HOLD a lock from outside the
# JVM, prove the running instance could not run that job while the lock was held, then
# release it and prove the job resumes. The negative half is what makes this a real test.
#
# ASCII-only on purpose (Windows PowerShell 5.1 parses this reliably regardless of encoding).
$ErrorActionPreference = 'Continue'
$base = 'http://127.0.0.1:8080'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe'
$script:pass = 0
$script:fail = 0

function Check($name, $ok, $detail) {
    if ($ok) { Write-Host ("  [PASS] {0}  {1}" -f $name, $detail) -ForegroundColor Green; $script:pass++ }
    else { Write-Host ("  [FAIL] {0}  {1}" -f $name, $detail) -ForegroundColor Red; $script:fail++ }
}
function Req($method, $path, $body, $headers, $idem) {
    $h = @{}
    if ($headers) { $headers.GetEnumerator() | ForEach-Object { $h[$_.Key] = $_.Value } }
    $h['Content-Type'] = 'application/json'
    if ($idem) { $h['X-Idempotency-Key'] = $idem }
    $a = @{ Uri = "$base$path"; Method = $method; Headers = $h; TimeoutSec = 40 }
    if ($body -ne $null) { $a['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 8 -Compress)) }
    try { return Invoke-RestMethod @a }
    catch {
        $resp = $_.Exception.Response
        if ($resp -ne $null) {
            $rd = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $t = $rd.ReadToEnd()
            try { return ($t | ConvertFrom-Json) } catch { return [pscustomobject]@{ code = -1; msg = $t } }
        }
        return [pscustomobject]@{ code = -1; msg = $_.Exception.Message }
    }
}
function GetR($p, $h) { return Req 'GET' $p $null $h $null }
function PostR($p, $b, $h, $i) { return Req 'POST' $p $b $h $i }
function PutR($p, $b, $h) { return Req 'PUT' $p $b $h $null }

# The database name MUST be passed to the client. Without it every bare table name fails with
# "No database selected", and DATABASE() is NULL -- which silently turns information_schema
# lookups into wrong answers instead of loud errors.
function Sql($q) {
    $out = & $mysql -uroot --password=123456 --default-character-set=utf8mb4 -N -B member_system -e $q 2>&1 | Where-Object { $_ -notmatch 'Warning' }
    return (($out -join "`n").Trim())
}

# Numeric read of a single value. Deliberately NOT "[int](Sql ...)": casting the mysql error
# text throws a statement-terminating error, and a throw inside an argument expression SKIPS
# the whole Check call -- the assertion disappears and PASS+FAIL silently drops below the
# declared Check count (this happened in the first run of this very suite: 36 declared,
# 34 counted). TryParse keeps a failed query as a visible FAIL instead.
function SqlInt($q) {
    $v = Sql $q
    $n = 0
    if ([int]::TryParse($v, [ref]$n)) { return $n }
    Write-Host ("  [WARN] not a number from SQL: '" + $v + "'") -ForegroundColor DarkYellow
    return -1
}

# Fire $n requests as fast as possible and count outcomes by business code.
# 10005 is the project-wide "too many requests" code (ErrorCode.TOO_MANY_REQUESTS).
function Flood($method, $path, $body, $headers, $n) {
    $blocked = 0; $passed = 0; $other = 0
    for ($i = 0; $i -lt $n; $i++) {
        $r = Req $method $path $body $headers $null
        if ($r.code -eq 10005) { $blocked++ }
        elseif ($r.code -eq 0) { $passed++ }
        else { $other++ }
    }
    return @{ blocked = $blocked; passed = $passed; other = $other }
}

$lockName = 'PaymentJob#paySyncJob'

Write-Host '=== 0. login + V9 applied ===' -ForegroundColor Cyan
$login = PostR '/api/admin/auth/login' @{ username = 'admin'; password = 'Admin@123' } $null $null
if ($login.code -ne 0) { Write-Host 'login failed' -ForegroundColor Red; exit 1 }
$H = @{ Authorization = 'Bearer ' + $login.data.accessToken }
Check 'admin login' ($login.code -eq 0) ('perms=' + $login.data.userInfo.perms.Count)

Check 'V9 migration applied' ((SqlInt "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '9' AND success = 1") -eq 1) 'version 9 in flyway_schema_history'
Check 'shedlock table exists' ((SqlInt "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'shedlock'") -eq 1) 'shedlock'
$cols = Sql "SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'shedlock'"
Check 'shedlock columns match ShedLock contract' ($cols -eq 'name,lock_until,locked_at,locked_by') ($cols)
$pk = Sql "SELECT column_name FROM information_schema.key_column_usage WHERE table_schema = DATABASE() AND table_name = 'shedlock' AND constraint_name = 'PRIMARY'"
Check 'primary key is name' ($pk -eq 'name') ($pk)
# lock_until must NOT auto-update: an implicit bump would silently extend a held lock
$extra = Sql "SELECT extra FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'shedlock' AND column_name = 'lock_until'"
Check 'lock_until has no ON UPDATE auto-bump' ($extra -notmatch 'on update') ('extra=[' + $extra + ']')

Write-Host '=== 1. the running instance actually takes locks ===' -ForegroundColor Cyan
$cnt = SqlInt "SELECT COUNT(*) FROM shedlock"
Check 'lock rows exist after startup' ($cnt -gt 0) ('rows=' + $cnt)
$short = SqlInt "SELECT COUNT(*) FROM shedlock WHERE name IN ('PaymentJob#paySyncJob','PaymentJob#payCloseJob','HoldOrderJob#cleanExpiredHolds','PaymentJob#couponUnlockJob')"
Check 'short-interval jobs took their locks' ($short -ge 3) ('taken=' + $short + '/4')
$badBy = SqlInt "SELECT COUNT(*) FROM shedlock WHERE locked_by IS NULL OR locked_by = '' OR locked_by LIKE '20%'"
Check 'locked_by is a real instance id (not a defaulted timestamp)' ($badBy -eq 0) ('bad=' + $badBy)
$badAt = SqlInt "SELECT COUNT(*) FROM shedlock WHERE locked_at IS NULL OR lock_until < locked_at"
Check 'lock timestamps are coherent' ($badAt -eq 0) ('bad=' + $badAt)

Write-Host '=== 2. the lock is really enforced (hold it from outside) ===' -ForegroundColor Cyan
$orig = ''
for ($i = 0; $i -lt 20; $i++) {
    $orig = Sql "SELECT locked_by FROM shedlock WHERE name = '$lockName'"
    if ($orig -ne '') { break }
    Start-Sleep -Seconds 3
}
Check 'frequent job took its lock at least once' ($orig -ne '') ('locked_by=' + $orig)

Sql "UPDATE shedlock SET lock_until = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 180 SECOND), locked_by = 'round10-holder' WHERE name = '$lockName'" | Out-Null
$held = Sql "SELECT locked_by FROM shedlock WHERE name = '$lockName'"
$until = Sql "SELECT lock_until FROM shedlock WHERE name = '$lockName'"
Check 'lock held from outside the JVM' ($held -eq 'round10-holder') ('holder=' + $held + ' until=' + $until)

# paySyncJob runs every 30s: 40s gives it at least one chance to (wrongly) steal the lock
Start-Sleep -Seconds 40
$still = Sql "SELECT locked_by FROM shedlock WHERE name = '$lockName'"
Check 'instance did NOT run the job while the lock was held' ($still -eq 'round10-holder') ('holder=' + $still)

# Release the way a real expiry looks: push lock_until into the past.
# NOT by deleting the row -- an out-of-band DELETE leaves the task dead forever (see section 2b).
Sql "UPDATE shedlock SET lock_until = DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 10 SECOND) WHERE name = '$lockName'" | Out-Null
$back = ''
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 3
    $back = Sql "SELECT locked_by FROM shedlock WHERE name = '$lockName'"
    if ($back -ne '' -and $back -ne 'round10-holder') { break }
}
Check 'job resumed once the lock expired' ($back -ne '' -and $back -ne 'round10-holder') ('locked_by=' + $back)

Write-Host '=== 2b. a deleted lock row must be recoverable (ShedLock local memory) ===' -ForegroundColor Cyan
# ShedLock's storage provider remembers "this lock record was already inserted" and afterwards
# only runs the conditional UPDATE. If the row is deleted behind its back the UPDATE matches
# nothing, so the task is skipped as "locked" FOREVER while the table looks empty -- silently.
# This section pins that behaviour down and proves the documented remedy (reset-cache).
Sql "DELETE FROM shedlock WHERE name = '$lockName'" | Out-Null
Check 'lock row deleted from outside' ((SqlInt "SELECT COUNT(*) FROM shedlock WHERE name = '$lockName'") -eq 0) 'rows=0'

Start-Sleep -Seconds 40
$afterDelete = SqlInt "SELECT COUNT(*) FROM shedlock WHERE name = '$lockName'"
Check 'task stays skipped after an out-of-band delete (known ShedLock trap)' ($afterDelete -eq 0) ('rows=' + $afterDelete)

$locks = GetR '/api/admin/system/scheduler-locks' $H
Check 'lock list endpoint exists' ($locks.code -eq 0) ('rows=' + @($locks.data).Count)

$reset = PostR '/api/admin/system/scheduler-locks/reset-cache' $null $H $null
Check 'lock-memory reset accepted' ($reset.code -eq 0 -and $reset.data.cleared -eq $true) ('provider=' + $reset.data.provider + ' remark=' + $reset.data.remark)

$revived = ''
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 3
    $revived = Sql "SELECT locked_by FROM shedlock WHERE name = '$lockName'"
    if ($revived -ne '') { break }
}
Check 'task revived after reset (no restart needed)' ($revived -ne '' -and $revived -ne 'round10-holder') ('locked_by=' + $revived)
$locks2 = GetR '/api/admin/system/scheduler-locks' $H
$row = @($locks2.data) | Where-Object { $_.name -eq $lockName } | Select-Object -First 1
Check 'revived lock is visible in the lock list with its holder' ($row -ne $null -and $row.lockedBy -ne '' -and $row.lockUntil -ne '') $(if ($row) { 'by=' + $row.lockedBy + ' until=' + $row.lockUntil + ' expired=' + $row.expired } else { 'row missing' })

Write-Host '=== 3. cache readiness is reported (P2-2) ===' -ForegroundColor Cyan
# Cache backend is switchable (local Caffeine / redis), so these assertions state the INVARIANT
# per backend instead of hardcoding one of them. Every Check still runs in both modes -- the
# declared-vs-executed sentinel in run-all-tests.ps1 forbids branching around a Check.
$cs = GetR '/api/admin/system/cache-status' $H
Check 'cache-status endpoint exists' ($cs.code -eq 0) ('code=' + $cs.code)
$ctype = $cs.data.cacheType
$configured = $cs.data.configuredType
$isRedis = ($ctype -eq 'redis')
$consistent = (($configured -eq 'redis') -eq $isRedis)
Check 'reported implementation matches the configured one' $consistent ('configured=' + $configured + ' effective=' + $ctype)
Check 'declared single instance is safe' ($cs.data.multiInstance -eq $false -and $cs.data.multiInstanceSafe -eq $true) ('multi=' + $cs.data.multiInstance + ' safe=' + $cs.data.multiInstanceSafe)
Check 'risk list matches the backend (caffeine lists what it cannot do)' $(if ($isRedis) { @($cs.data.risks).Count -eq 0 } else { @($cs.data.risks).Count -ge 4 }) ('cacheType=' + $ctype + ' risks=' + @($cs.data.risks).Count)
Check 'a suggestion is always given' (-not [string]::IsNullOrWhiteSpace($cs.data.suggestion)) ('suggestion=' + $cs.data.suggestion)

Write-Host '=== 4. multi-instance declaration is judged by the backend ===' -ForegroundColor Cyan
PutR '/api/admin/system/configs' @{ configKey = 'cache.multi-instance'; configValue = 'true' } $H | Out-Null
$cs2 = GetR '/api/admin/system/cache-status' $H
$risks2 = @($cs2.data.risks).Count
$joined = (@($cs2.data.risks) -join ' | ')
# 这就是 P2-2 的核心不变量：多实例 + Caffeine = 不安全（幂等/锁定/序列号静默失效）；
# 多实例 + Redis = 安全。断言两种后端下都必须成立。
Check 'multi-instance safety follows the backend' ($cs2.data.multiInstanceSafe -eq $isRedis) ('cacheType=' + $ctype + ' multiSafe=' + $cs2.data.multiInstanceSafe + ' (redis->True, caffeine->False)')
Check 'risk list explains the unsafe combination' ($risks2 -eq $(if ($isRedis) { 0 } else { 5 })) ('risks=' + $risks2 + ' [' + $joined + ']')
PutR '/api/admin/system/configs' @{ configKey = 'cache.multi-instance'; configValue = 'false' } $H | Out-Null
$cs3 = GetR '/api/admin/system/cache-status' $H
Check 'restoring single-instance clears the warning' ($cs3.data.multiInstanceSafe -eq $true) ('safe=' + $cs3.data.multiInstanceSafe)

Write-Host '=== 5. rate limit is enforced (P2-3) ===' -ForegroundColor Cyan
$code = 'r10-code-' + (Get-Random -Minimum 100000 -Maximum 999999)
$mpLogin = PostR '/api/mp/auth/login' @{ code = $code } $null $null
Check 'mp login (mock mode)' ($mpLogin.code -eq 0) ('code=' + $mpLogin.code)
$MP = @{ Authorization = 'Bearer ' + $mpLogin.data.accessToken }

$base1 = GetR '/api/mp/auth/profile' $MP
Check 'mp endpoint works under the limit' ($base1.code -eq 0) ('code=' + $base1.code)

# member-dimension flood: 120 requests, mp.qps = 20 per second -> some must be rejected
$f1 = Flood 'GET' '/api/mp/auth/profile' $null $MP 120
Check 'flood on /api/mp/auth/profile is throttled' ($f1.blocked -ge 1) ('blocked=' + $f1.blocked + ' passed=' + $f1.passed + ' other=' + $f1.other)
Check 'throttling is a rate limit, not a total block' ($f1.passed -ge 1) ('passed=' + $f1.passed + '/' + 120)

# the window must roll: after a pause the same member is served again
Start-Sleep -Seconds 2
$after = GetR '/api/mp/auth/profile' $MP
Check 'quota resets on the next window' ($after.code -eq 0) ('code=' + $after.code)

# a different endpoint has its own bucket: an anonymous open callback must still be served
# (it answers with the channel's own payload, not with the platform envelope)
$notify = PostR '/api/open/pay/notify/mock' '' $null $null
Check 'open callback bucket is unaffected by the mp flood' ($notify.code -eq 'FAIL' -or $notify.code -eq 'SUCCESS') ('code=' + $notify.code + ' message=' + $notify.message)

Write-Host '=== 6. rate limiting can be switched off ===' -ForegroundColor Cyan
PutR '/api/admin/system/configs' @{ configKey = 'ratelimit.enabled'; configValue = 'false' } $H | Out-Null
$f2 = Flood 'GET' '/api/mp/auth/profile' $null $MP 120
Check 'no throttling while disabled' ($f2.blocked -eq 0) ('blocked=' + $f2.blocked + ' passed=' + $f2.passed + ' other=' + $f2.other)
PutR '/api/admin/system/configs' @{ configKey = 'ratelimit.enabled'; configValue = 'true' } $H | Out-Null
$f3 = Flood 'GET' '/api/mp/auth/profile' $null $MP 120
Check 'throttling is back after re-enabling' ($f3.blocked -ge 1) ('blocked=' + $f3.blocked + ' passed=' + $f3.passed)
$enabled = Sql "SELECT config_value FROM m_sys_config WHERE config_key = 'ratelimit.enabled'"
Check 'enabled flag is still true in config' ($enabled -eq 'true') ('ratelimit.enabled=' + $enabled)

Write-Host '=== 7. rate limit parameters are configurable ===' -ForegroundColor Cyan
$mpQps = Sql "SELECT config_value FROM m_sys_config WHERE config_key = 'ratelimit.mp.qps'"
Check 'mp quota parameter exists' ($mpQps -ne '') ('ratelimit.mp.qps=' + $mpQps)
$openQps = Sql "SELECT config_value FROM m_sys_config WHERE config_key = 'ratelimit.open.qps'"
Check 'open quota parameter exists' ($openQps -ne '') ('ratelimit.open.qps=' + $openQps)
$multiCfg = Sql "SELECT config_value FROM m_sys_config WHERE config_key = 'cache.multi-instance'"
Check 'cache multi-instance parameter restored' ($multiCfg -eq 'false') ('cache.multi-instance=' + $multiCfg)

Write-Host '=== 8. nothing was broken on the way ===' -ForegroundColor Cyan
$rc = GetR '/api/admin/accounts/reconcile' $H
Check 'ledger reconcile still clean' ($rc.code -eq 0 -and $rc.data.diffCount -eq 0) ($rc.data.conclusion)
Check 'shedlock rows are still coherent after the test' ((SqlInt "SELECT COUNT(*) FROM shedlock WHERE lock_until < locked_at") -eq 0) 'timestamps'

Write-Host '=== 9. cache round-trip is silent ===' -ForegroundColor Cyan
# Redis 模式下每个缓存值都要走一次 JSON 往返，任何「值无法序列化为 JSON」或「反序列化失败」
# 都意味着某些缓存写不进去或读不出来。实测踩过两次，且都只在切 Redis 后暴露：
#   · PrivateKey / X509Certificate 不可 JSON 化 → 被上层报成「私钥无法解析」→ 在线支付开不了
#   · 看板结果里的 LocalDateTime 没注册 JavaTimeModule → 报表接口直接 500
# 这条断言把这一类问题从「上线才发现」变成「自检就红」，两种缓存实现下都必须零告警。
$logPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'logs\backend-stdout.log'
$cacheWarns = @()
if (Test-Path $logPath) {
    $cacheWarns = @(Select-String -Path $logPath -Pattern '值无法序列化为 JSON|\[缓存\] 反序列化失败' -Encoding UTF8)
}
$firstWarn = ''
if ($cacheWarns.Count -gt 0) { $firstWarn = ' first: ' + (($cacheWarns[0].Line -replace '\x1b\[[0-9;]*m', '').Trim()) }
Check 'backend log is readable (the guard below needs it)' (Test-Path $logPath) $logPath
Check 'no cache serialization/deserialization warnings' ($cacheWarns.Count -eq 0) ('warnings=' + $cacheWarns.Count + $firstWarn)

Write-Host ''
Write-Host ("RESULT: PASS={0} FAIL={1}" -f $script:pass, $script:fail) -ForegroundColor Yellow
if ($script:fail -gt 0) { exit 1 }
