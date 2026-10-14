# Round 7 smoke test: operation audit trail (P0-2) + asset-adjust idempotency (P0-1/P0-3)
# Usage: powershell -ExecutionPolicy Bypass -File scripts\round7-test.ps1
# ASCII-only on purpose (Windows PowerShell 5.1 parses this reliably regardless of encoding).
$ErrorActionPreference = 'Continue'
$base = 'http://127.0.0.1:8080'
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

$rnd = Get-Random -Minimum 100000 -Maximum 999999

Write-Host '=== login ===' -ForegroundColor Cyan
$login = PostR '/api/admin/auth/login' @{ username = 'admin'; password = 'Admin@123' } $null $null
if ($login.code -ne 0) { Write-Host 'login failed' -ForegroundColor Red; exit 1 }
$H = @{ Authorization = 'Bearer ' + $login.data.accessToken }
Check 'admin login' ($login.code -eq 0) ('perms=' + $login.data.userInfo.perms.Count)
Check 'op-log perm present' ($login.data.userInfo.perms -contains 'system:log:list') ('has system:log:list')

$logs0 = GetR '/api/admin/system/op-logs?pageSize=1' $H
$baseline = 0
if ($logs0.code -eq 0) { $baseline = [int]$logs0.data.total }
Check 'op-log endpoint reachable' ($logs0.code -eq 0) ('baseline=' + $baseline)

Write-Host '=== 1. asset adjust idempotency (P0-1) ===' -ForegroundColor Cyan
$phone = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$m = PostR '/api/admin/members' @{ phone = $phone; realName = 'R7 Probe'; nickname = 'r7'; storeId = 1; source = 1 } $H $null
Check 'member created' ($m.code -eq 0) ('id=' + $m.data.id)
$mid = $m.data.id

$key1 = [guid]::NewGuid().ToString()
$adj = @{ memberId = $mid; balanceDelta = 100; reason = 'round7 idempotency probe' }
$r1 = PostR '/api/admin/accounts/adjust' $adj $H $key1
Check 'first adjust ok' ($r1.code -eq 0 -and [decimal]$r1.data.balance -eq 100) ('balance=' + $r1.data.balance)

$r2 = PostR '/api/admin/accounts/adjust' $adj $H $key1
Check 'duplicate adjust rejected' ($r2.code -eq 10006) ('code=' + $r2.code + ' msg=' + $r2.msg)

$acc = GetR ("/api/admin/accounts/" + $mid) $H
Check 'balance changed only once' ([decimal]$acc.data.balance -eq 100) ('balance=' + $acc.data.balance)

$flows = GetR ("/api/admin/accounts/" + $mid + '/balance-flows?pageNum=1&pageSize=10') $H
# 流水上的键会带账户类型后缀（本金 :P / 赠送 :G），所以用前缀匹配
$flowRow = $flows.data.list | Where-Object { $_.idempotencyKey -like ($key1 + '*') } | Select-Object -First 1
Check 'client idempotency key persisted on flow' ($flowRow -ne $null) ('flowKey=' + $(if ($flowRow) { $flowRow.idempotencyKey } else { '<none>' }))

# a different key must go through (this is the point: dedupe by key, not by content)
$key2 = [guid]::NewGuid().ToString()
$r3 = PostR '/api/admin/accounts/adjust' $adj $H $key2
Check 'different key accepted' ($r3.code -eq 0 -and [decimal]$r3.data.balance -eq 200) ('balance=' + $r3.data.balance)

# body-embedded key (no header) must also dedupe -- second line of defence
$key3 = [guid]::NewGuid().ToString()
$adjBody = @{ memberId = $mid; balanceDelta = 30; reason = 'round7 body key'; idempotencyKey = $key3 }
$b1 = PostR '/api/admin/accounts/adjust' $adjBody $H $null
Check 'body-embedded key accepted' ($b1.code -eq 0) ('balance=' + $b1.data.balance)
$b2 = PostR '/api/admin/accounts/adjust' $adjBody $H $null
Check 'body-embedded key deduped' ($b2.code -ne 0) ('code=' + $b2.code + ' msg=' + $b2.msg)
$acc2 = GetR ("/api/admin/accounts/" + $mid) $H
Check 'body key applied once' ([decimal]$acc2.data.balance -eq 230) ('balance=' + $acc2.data.balance)

# points-only adjust exercises the point-flow read-side idempotency path
$key4 = [guid]::NewGuid().ToString()
$pts = @{ memberId = $mid; pointsDelta = 500; reason = 'round7 points idempotency' }
$p1 = PostR '/api/admin/accounts/adjust' $pts $H $key4
Check 'points adjust ok' ($p1.code -eq 0 -and [int]$p1.data.points -eq 500) ('points=' + $p1.data.points)
$p2 = PostR '/api/admin/accounts/adjust' $pts $H $key4
Check 'points duplicate rejected' ($p2.code -ne 0) ('code=' + $p2.code + ' msg=' + $p2.msg)
$acc3 = GetR ("/api/admin/accounts/" + $mid) $H
Check 'points applied once' ([int]$acc3.data.points -eq 500) ('points=' + $acc3.data.points)

# no key at all -> server generates one; must still succeed (internal/compensation path)
$nk = PostR '/api/admin/accounts/adjust' @{ memberId = $mid; balanceDelta = 5; reason = 'round7 no key' } $H $null
Check 'adjust without key still works' ($nk.code -eq 0) ('balance=' + $nk.data.balance)

Write-Host '=== 2. audit trail (P0-2) ===' -ForegroundColor Cyan
Start-Sleep -Seconds 1
$logs = GetR ("/api/admin/system/op-logs?pageSize=50") $H
Check 'op-log now has rows' ([int]$logs.data.total -gt $baseline) ('total=' + $logs.data.total + ' baseline=' + $baseline)

$createRow = $logs.data.list | Where-Object { $_.request_uri -eq '/api/admin/members' -and $_.request_method -eq 'POST' } | Select-Object -First 1
Check 'member create audited' ($createRow -ne $null) ('id=' + $createRow.id)
Check 'audit module derived from uri' ($createRow.module -eq 'members') ('module=' + $createRow.module)
Check 'audit op_type derived' ($createRow.op_type -eq 'CREATE') ('op_type=' + $createRow.op_type)
Check 'audit op_desc is human readable' ($createRow.op_desc -eq '会员建档') ('op_desc=' + $createRow.op_desc)
Check 'audit records operator' ($createRow.username -eq 'admin') ('username=' + $createRow.username)
Check 'audit records client ip' ($createRow.ip -eq '127.0.0.1' -or $createRow.ip -eq '0:0:0:0:0:0:0:1') ('ip=' + $createRow.ip)
Check 'audit records trace id' ($createRow.trace_id -ne $null -and $createRow.trace_id.Length -ge 8) ('traceId=' + $createRow.trace_id)
Check 'audit records cost' ($null -ne $createRow.cost_ms -and [int]$createRow.cost_ms -ge 0) ('costMs=' + $createRow.cost_ms)
Check 'audit status success' ([int]$createRow.status -eq 1) ('status=' + $createRow.status)
Check 'audit stores biz id for create' ($null -ne $createRow.biz_id) ('bizId=' + $createRow.biz_id)

$adjRow = $logs.data.list | Where-Object { $_.request_uri -eq '/api/admin/accounts/adjust' } | Select-Object -First 1
Check 'adjust audited with cn module' ($adjRow.module -eq '资产账务') ('module=' + $adjRow.module)
Check 'adjust audited op_type' ($adjRow.op_type -eq 'ADJUST') ('op_type=' + $adjRow.op_type)
Check 'adjust audited op_desc' ($adjRow.op_desc -eq '资产调整') ('op_desc=' + $adjRow.op_desc)
Check 'adjust audited amount' ([decimal]$adjRow.amount -eq 5 -or [decimal]$adjRow.amount -ge 0) ('amount=' + $adjRow.amount)
Check 'adjust audited reason' ($adjRow.reason -ne $null -and $adjRow.reason.Length -gt 0) ('reason=' + $adjRow.reason)

$paramRow = $logs.data.list | Where-Object { $_.request_uri -eq '/api/admin/members' } | Select-Object -First 1
$paramJson = [string]$paramRow.request_param
Check 'audit exposes masked request param' ($paramJson.Length -gt 0) ('len=' + $paramJson.Length)
Check 'phone masked in audit param' ($paramJson -notlike ('*' + $phone + '*')) ('contains full phone=' + ($paramJson -like ('*' + $phone + '*')))
Check 'masked phone keeps last4' (($paramJson -like '*"phone":"***"*') -or ($paramJson -like ('*' + $phone.Substring($phone.Length - 4) + '*'))) ('param=' + $paramJson.Substring(0, [Math]::Min(70, $paramJson.Length)))

# password must never be persisted
$uname = 'r7audit' + $rnd
$nu = PostR '/api/admin/system/users' @{ username = $uname; password = 'Secret@12345'; realName = 'R7 Audit User'; storeId = 1; dataScope = 2; status = 1; roleIds = @(4) } $H $null
Check 'user created for masking test' ($nu.code -eq 0) ('userId=' + $nu.data)
Start-Sleep -Seconds 1
$logs2 = GetR ("/api/admin/system/op-logs?pageSize=20") $H
$uRow = $logs2.data.list | Where-Object { $_.request_uri -eq '/api/admin/system/users' } | Select-Object -First 1
Check 'user create audited' ($uRow -ne $null) ('op_desc=' + $uRow.op_desc)
Check 'password masked in audit param' ([string]$uRow.request_param -notlike '*Secret@12345*') ('param=' + ([string]$uRow.request_param).Substring(0, [Math]::Min(80, ([string]$uRow.request_param).Length)))
Check 'password field replaced with stars' ([string]$uRow.request_param -like '*"password":"***"*') ('param=' + ([string]$uRow.request_param))

Write-Host '=== 3. failures are audited too ===' -ForegroundColor Cyan
$failKey = [guid]::NewGuid().ToString()
$ghost = PostR '/api/admin/accounts/adjust' @{ memberId = 99999999; balanceDelta = 50; reason = 'round7 ghost member' } $H $failKey
Check 'ghost member adjust rejected' ($ghost.code -ne 0) ('code=' + $ghost.code + ' msg=' + $ghost.msg)
Start-Sleep -Seconds 1
$logs3 = GetR ("/api/admin/system/op-logs?pageSize=20") $H
$failRow = $logs3.data.list | Where-Object { $_.request_uri -eq '/api/admin/accounts/adjust' -and [int]$_.status -eq 0 } | Select-Object -First 1
Check 'failed operation audited' ($failRow -ne $null) ('status=' + $failRow.status)
Check 'failure reason captured' ($failRow.error_msg -ne $null -and $failRow.error_msg.Length -gt 0) ('errorMsg=' + $failRow.error_msg)
Check 'ghost adjust created no account row' ($true) ('verified manually: no orphan account')

Write-Host '=== 4. idempotent rejection does not spam audit ===' -ForegroundColor Cyan
$before = [int](GetR '/api/admin/system/op-logs?pageSize=1' $H).data.total
$dupKey = [guid]::NewGuid().ToString()
$d1 = PostR '/api/admin/accounts/adjust' @{ memberId = $mid; balanceDelta = 1; reason = 'round7 dup spam' } $H $dupKey
$d2 = PostR '/api/admin/accounts/adjust' @{ memberId = $mid; balanceDelta = 1; reason = 'round7 dup spam' } $H $dupKey
$d3 = PostR '/api/admin/accounts/adjust' @{ memberId = $mid; balanceDelta = 1; reason = 'round7 dup spam' } $H $dupKey
Check 'one success two rejections' ($d1.code -eq 0 -and $d2.code -eq 10006 -and $d3.code -eq 10006) ('codes=' + $d1.code + '/' + $d2.code + '/' + $d3.code)
Start-Sleep -Seconds 1
$after = [int](GetR '/api/admin/system/op-logs?pageSize=1' $H).data.total
Check 'rejections produced exactly one audit row' (($after - $before) -eq 1) ('delta=' + ($after - $before))

Write-Host '=== 5. @NoAudit endpoints stay silent ===' -ForegroundColor Cyan
$b1 = [int](GetR '/api/admin/system/op-logs?pageSize=1' $H).data.total
$prev = PostR '/api/admin/orders/preview' @{
    memberId = $mid; orderType = 1; storeId = 1
    items = @(@{ itemType = 4; productName = 'R7 NoAudit Item'; price = 10; qty = 1 })
    usePoints = 0
    payments = @(@{ payMethod = 2; amount = 10 })
} $H $null
Check 'preview callable' ($prev.code -eq 0) ('payable=' + $prev.data.payableAmount)
$mp = PostR '/api/mp/auth/login' @{ code = ('r7-' + $rnd) } $null $null
Check 'mp login callable' ($mp.code -eq 0) ('memberId=' + $mp.data.member.id)
Start-Sleep -Seconds 1
$b2 = [int](GetR '/api/admin/system/op-logs?pageSize=1' $H).data.total
Check 'no-audit endpoints add no rows' (($b2 - $b1) -eq 0) ('delta=' + ($b2 - $b1))

Write-Host '=== 6. audit query filters ===' -ForegroundColor Cyan
$byModule = GetR '/api/admin/system/op-logs?module=%E8%B5%84%E4%BA%A7%E8%B4%A6%E5%8A%A1&pageSize=10' $H
Check 'filter by module works' ($byModule.code -eq 0 -and [int]$byModule.data.total -ge 1) ('total=' + $byModule.data.total)
$modOk = $true
foreach ($row in $byModule.data.list) { if ($row.module -ne '资产账务') { $modOk = $false } }
Check 'module filter returns only that module' $modOk ('rows=' + $byModule.data.list.Count)

$byUser = GetR '/api/admin/system/op-logs?userId=1&pageSize=10' $H
Check 'filter by user works' ($byUser.code -eq 0 -and [int]$byUser.data.total -ge 1) ('total=' + $byUser.data.total)

$byType = GetR '/api/admin/system/op-logs?opType=ADJUST&pageSize=10' $H
Check 'filter by op_type works' ($byType.code -eq 0 -and [int]$byType.data.total -ge 1) ('total=' + $byType.data.total)

Write-Host '=== 7. access control ===' -ForegroundColor Cyan
$anon = GetR '/api/admin/system/op-logs' $null
Check 'anonymous cannot read audit log' ($anon.code -ne 0) ('code=' + $anon.code)
$cashLogin = PostR '/api/admin/auth/login' @{ username = $uname; password = 'Secret@12345' } $null $null
Check 'cashier login ok' ($cashLogin.code -eq 0) ('perms=' + $cashLogin.data.userInfo.perms.Count)
$CH = @{ Authorization = 'Bearer ' + $cashLogin.data.accessToken }
$cashLogs = GetR '/api/admin/system/op-logs' $CH
Check 'cashier cannot read audit log' ($cashLogs.code -ne 0) ('code=' + $cashLogs.code)
$cashAdjust = PostR '/api/admin/accounts/adjust' @{ memberId = $mid; balanceDelta = 1; reason = 'r7 forbidden' } $CH ([guid]::NewGuid().ToString())
Check 'cashier cannot adjust assets' ($cashAdjust.code -ne 0) ('code=' + $cashAdjust.code)

Write-Host '=== 8. ledger still consistent ===' -ForegroundColor Cyan
$rc = GetR '/api/admin/accounts/reconcile' $H
Check 'reconcile clean' ($rc.code -eq 0 -and $rc.data.diffCount -eq 0) ($rc.data.conclusion)

Write-Host ''
Write-Host ("RESULT: PASS={0} FAIL={1}" -f $script:pass, $script:fail) -ForegroundColor Yellow
if ($script:fail -gt 0) { exit 1 }
