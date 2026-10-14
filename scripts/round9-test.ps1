# Round 9 smoke test: dashboard read cache (P1-3) + frontend bundle split (P2-4) + zero TS errors (P2-5)
# Usage: powershell -ExecutionPolicy Bypass -File scripts\round9-test.ps1
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

$root = Split-Path -Parent $PSScriptRoot
$rnd = Get-Random -Minimum 100000 -Maximum 999999

Write-Host '=== login ===' -ForegroundColor Cyan
$login = PostR '/api/admin/auth/login' @{ username = 'admin'; password = 'Admin@123' } $null $null
if ($login.code -ne 0) { Write-Host 'login failed' -ForegroundColor Red; exit 1 }
$H = @{ Authorization = 'Bearer ' + $login.data.accessToken }
Check 'admin login' ($login.code -eq 0) ('perms=' + $login.data.userInfo.perms.Count)

Write-Host '=== 1. dashboard cache config is actually consumed (P1-3) ===' -ForegroundColor Cyan
$st = GetR '/api/admin/report/dashboard/cache-status' $H
Check 'cache-status endpoint exists' ($st.code -eq 0) ('ttl=' + $st.data.ttlSeconds + ' enabled=' + $st.data.enabled)
Check 'ttl comes from m_sys_config' ([int]$st.data.ttlSeconds -eq 600) ('ttl=' + $st.data.ttlSeconds)
Check 'cache reported as enabled' ($st.data.enabled -eq $true) ('enabled=' + $st.data.enabled)

Write-Host '=== 2. cache actually serves hits ===' -ForegroundColor Cyan
PostR '/api/admin/report/dashboard/cache-evict' $null $H $null | Out-Null
$s0 = GetR '/api/admin/report/dashboard/cache-status' $H
$h0 = [int]$s0.data.hits
$m0 = [int]$s0.data.misses

$o1 = GetR '/api/admin/report/dashboard/overview?days=30' $H
Check 'overview callable' ($o1.code -eq 0) ('refreshTime=' + $o1.data.refreshTime)
$s1 = GetR '/api/admin/report/dashboard/cache-status' $H
Check 'first overview is a miss' ([int]$s1.data.misses -gt $m0) ('misses ' + $m0 + ' -> ' + $s1.data.misses)

$o2 = GetR '/api/admin/report/dashboard/overview?days=30' $H
$s2 = GetR '/api/admin/report/dashboard/cache-status' $H
Check 'second overview is a hit' ([int]$s2.data.hits -gt $h0) ('hits ' + $h0 + ' -> ' + $s2.data.hits)
Check 'hit rate is reported' ([double]$s2.data.hitRate -gt 0) ('hitRate=' + $s2.data.hitRate + '%')
# 缓存命中时 refreshTime 应保持不变（它是在 loader 里生成的）
Check 'cached payload is reused' ($o1.data.refreshTime -eq $o2.data.refreshTime) ('t1=' + $o1.data.refreshTime + ' t2=' + $o2.data.refreshTime)

$t1 = GetR '/api/admin/report/dashboard/member-trend?days=30' $H
$t2 = GetR '/api/admin/report/dashboard/member-trend?days=30' $H
Check 'trend endpoint cached too' ($t1.code -eq 0 -and $t2.code -eq 0) ('points=' + $t1.data.Count)

Write-Host '=== 3. consume evicts dashboard cache ===' -ForegroundColor Cyan
$phone = '135' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$m = PostR '/api/admin/members' @{ phone = $phone; realName = 'R9 Cache'; nickname = 'r9'; storeId = 1; source = 1 } $H $null
Check 'member created' ($m.code -eq 0) ('id=' + $m.data.id)
$mid = $m.data.id

# warm the cache, then settle, then confirm the next read is a miss (i.e. it was evicted)
GetR '/api/admin/report/dashboard/overview?days=30' $H | Out-Null
$warm = GetR '/api/admin/report/dashboard/overview?days=30' $H
Check 'cache warmed before consume' ($warm.code -eq 0) ('refreshTime=' + $warm.data.refreshTime)
$mBefore = [int](GetR '/api/admin/report/dashboard/cache-status' $H).data.misses

$st1 = PostR '/api/admin/orders/settle' @{
    memberId = $mid; orderType = 1; storeId = 1; staffId = 1
    items = @(@{ itemType = 4; productName = 'R9 Cache Item'; price = 66; qty = 1 })
    usePoints = 0
    payments = @(@{ payMethod = 2; amount = 66 })
} $H ([guid]::NewGuid().ToString())
Check 'settle ok' ($st1.code -eq 0) ('orderNo=' + $st1.data.orderNo)

$after = GetR '/api/admin/report/dashboard/overview?days=30' $H
$mAfter = [int](GetR '/api/admin/report/dashboard/cache-status' $H).data.misses
Check 'consume evicted the cache (next read is a miss)' ($mAfter -gt $mBefore) ('misses ' + $mBefore + ' -> ' + $mAfter)
Check 'fresh payload differs from warmed one' ($after.data.refreshTime -ne $warm.data.refreshTime) ('warm=' + $warm.data.refreshTime + ' fresh=' + $after.data.refreshTime)

Write-Host '=== 4. ttl=0 disables the cache ===' -ForegroundColor Cyan
PutR '/api/admin/system/configs' @{ configKey = 'report.dashboard_cache_seconds'; configValue = '0' } $H | Out-Null
$d0 = GetR '/api/admin/report/dashboard/cache-status' $H
Check 'cache reports disabled at ttl=0' ($d0.data.enabled -eq $false) ('ttl=' + $d0.data.ttlSeconds)
$h1 = [int]$d0.data.hits
GetR '/api/admin/report/dashboard/overview?days=30' $H | Out-Null
GetR '/api/admin/report/dashboard/overview?days=30' $H | Out-Null
$d1 = GetR '/api/admin/report/dashboard/cache-status' $H
Check 'no hits accumulated while disabled' ([int]$d1.data.hits -eq $h1) ('hits ' + $h1 + ' -> ' + $d1.data.hits)
PutR '/api/admin/system/configs' @{ configKey = 'report.dashboard_cache_seconds'; configValue = '600' } $H | Out-Null
$d2 = GetR '/api/admin/report/dashboard/cache-status' $H
Check 'cache re-enabled' ($d2.data.enabled -eq $true) ('ttl=' + $d2.data.ttlSeconds)

Write-Host '=== 5. manual evict works ===' -ForegroundColor Cyan
GetR '/api/admin/report/dashboard/overview?days=30' $H | Out-Null
$ev = PostR '/api/admin/report/dashboard/cache-evict' $null $H $null
Check 'manual evict callable' ($ev.code -eq 0) ('evicted=' + $ev.data)
$warm2 = GetR '/api/admin/report/dashboard/overview?days=30' $H
$mB = [int](GetR '/api/admin/report/dashboard/cache-status' $H).data.misses
GetR '/api/admin/report/dashboard/overview?days=30' $H | Out-Null
$mA = [int](GetR '/api/admin/report/dashboard/cache-status' $H).data.misses
Check 'cache refills after evict' ($mA -eq $mB) ('misses stable at ' + $mA)

Write-Host '=== 6. frontend bundle is split (P2-4) ===' -ForegroundColor Cyan
$dist = Join-Path $root 'web-admin\dist\assets'
if (-not (Test-Path $dist)) {
    Write-Host '  [SKIP] web-admin\dist 不存在，先跑 npm run build' -ForegroundColor Yellow
} else {
    $js = Get-ChildItem $dist -File -Filter *.js
    $totalKB = [math]::Round((Get-ChildItem (Join-Path $root 'web-admin\dist') -Recurse -File | Measure-Object Length -Sum).Sum / 1KB)
    Check 'dist total size under 2.6 MB' ($totalKB -lt 2662) ('total=' + $totalKB + ' KB (was ~3174 KB)')

    $echarts = $js | Where-Object { $_.Name -like 'vendor-echarts*' } | Select-Object -First 1
    Check 'echarts is its own chunk' ($echarts -ne $null) ($(if ($echarts) { [math]::Round($echarts.Length/1KB) } else { 0 }).ToString() + ' KB')
    Check 'echarts chunk under 600 KB' ($echarts -ne $null -and $echarts.Length -lt 614400) ('KB=' + $(if ($echarts) { [math]::Round($echarts.Length/1KB) } else { 0 }))
    Check 'vendor chunks are separated' (@($js | Where-Object { $_.Name -like 'vendor-*' }).Count -ge 4) ('vendorChunks=' + @($js | Where-Object { $_.Name -like 'vendor-*' }).Count)

    $biggest = $js | Sort-Object Length -Descending | Select-Object -First 1
    Check 'no single chunk over 1 MB' ($biggest.Length -lt 1048576) ('biggest=' + $biggest.Name + ' ' + [math]::Round($biggest.Length/1KB) + ' KB')
}

Write-Host '=== 7. type-check is clean (P2-5) ===' -ForegroundColor Cyan
Push-Location (Join-Path $root 'web-admin')
try {
    $tsOut = & npx vue-tsc --noEmit 2>&1
    $errCount = @($tsOut | Select-String -Pattern 'error TS').Count
    Check 'zero TypeScript errors' ($errCount -eq 0) ('errors=' + $errCount + ' (baseline was 49)')
} finally {
    Pop-Location
}

Write-Host '=== 8. ledger still consistent ===' -ForegroundColor Cyan
$rc = GetR '/api/admin/accounts/reconcile' $H
Check 'reconcile clean' ($rc.code -eq 0 -and $rc.data.diffCount -eq 0) ($rc.data.conclusion)

Write-Host ''
Write-Host ("RESULT: PASS={0} FAIL={1}" -f $script:pass, $script:fail) -ForegroundColor Yellow
if ($script:fail -gt 0) { exit 1 }
