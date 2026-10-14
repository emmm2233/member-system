# Run the full self-check suite against a freshly reset database.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\run-all-tests.ps1
# ASCII-only (Windows PowerShell 5.1 safe).
#
# What it does:
#   1. stop any running backend
#   2. drop + recreate member_system (so every suite starts from known seed data)
#   3. start the backend jar and wait for /actuator/health
#   4. run every round suite in order and aggregate PASS/FAIL
#
# NOTE: the suites are order-sensitive by design (later rounds reuse members
# created by the cashier flows), so always run them through this script when
# you want a full green/red verdict.
#
# -CacheType redis runs the whole suite against Redis instead of the local Caffeine
# cache. Why it matters: Redis is not a performance tweak here, it is the correctness
# prerequisite for multi-instance (idempotency keys, login lockout, business sequence
# numbers and the WeChat access token all live in the cache). The switch itself is
# otherwise unexercised, i.e. "works on my machine" until the day you scale out.
#   powershell -ExecutionPolicy Bypass -File scripts\run-all-tests.ps1 -CacheType redis
# (needs a Redis on 127.0.0.1:6379 -- start it with scripts\start-redis.ps1)
param(
    [string]$CacheType = ''
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$mysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe'
$mvn = Join-Path $root 'tools\apache-maven-3.9.9\bin\mvn.cmd'
$backend = Join-Path $root 'backend'
$logDir = Join-Path $root 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

Write-Host '=== 0. stop backend ===' -ForegroundColor Cyan
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*member-system.jar*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-Sleep -Seconds 2

Write-Host '=== 1. reset database ===' -ForegroundColor Cyan
& $mysqlExe -uroot --password=123456 -e "DROP DATABASE IF EXISTS member_system; CREATE DATABASE member_system DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>&1 |
    Where-Object { $_ -notmatch 'Warning' } | Out-Null
Write-Host '  database recreated' -ForegroundColor Green

Write-Host '=== 2. start backend ===' -ForegroundColor Cyan
# Start the JVM through WMI so it is NOT a child of this PowerShell process.
# Reason: this script is often launched as a background job, and killing that job
# would otherwise tear down the whole process tree - including the backend we just
# started, leaving port 8080 dead for every later step.
$stdout = Join-Path $logDir 'backend-stdout.log'
$stderr = Join-Path $logDir 'backend-stderr.log'
# Cache backend is chosen by a command-line argument: profile yml pins member.cache.type=local,
# and command-line args outrank profile config, so an env var would silently be ignored here.
$cacheArg = ''
if ($CacheType -ne '') {
    if ($CacheType -eq 'redis') {
        $listening = $false
        try { $listening = ((Get-NetTCPConnection -LocalPort 6379 -State Listen -ErrorAction Stop) | Measure-Object).Count -gt 0 } catch { }
        if (-not $listening) {
            Write-Host '  -CacheType redis requested but nothing is listening on 127.0.0.1:6379' -ForegroundColor Red
            Write-Host '  start it first: powershell -ExecutionPolicy Bypass -File scripts\start-redis.ps1' -ForegroundColor Yellow
            exit 1
        }
    }
    $cacheArg = ' --member.cache.type=' + $CacheType
    Write-Host ("  cache backend requested: " + $CacheType) -ForegroundColor Green
}
$cmdLine = 'cmd /c cd /d "' + $backend + '" && java -jar target\member-system.jar --spring.profiles.active=local' + $cacheArg + ' > "' + $stdout + '" 2> "' + $stderr + '"'
$created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmdLine }
if ($created.ReturnValue -ne 0) {
    Write-Host ("failed to spawn backend, Win32_Process.Create returned " + $created.ReturnValue) -ForegroundColor Red
    exit 1
}
Write-Host ("  backend pid=" + $created.ProcessId) -ForegroundColor Green
$ok = $false
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Seconds 3
    try {
        $h = Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 5
        if ($h.status -eq 'UP') { $ok = $true; break }
    } catch { }
}
if (-not $ok) { Write-Host 'backend failed to become healthy' -ForegroundColor Red; exit 1 }
Write-Host '  backend UP' -ForegroundColor Green

# Print which cache implementation actually got built. A wrong or ignored -CacheType would
# otherwise make the whole run meaningless while still reporting all-green.
$cacheLine = (Select-String -Path $stdout -Pattern '\[缓存\] 使用' -Encoding UTF8 -ErrorAction SilentlyContinue |
    Select-Object -First 1).Line
if ($cacheLine) { Write-Host ('  ' + ($cacheLine -replace '.*\[缓存\]', '[缓存]')) -ForegroundColor Green }

Write-Host '=== 3. run suites ===' -ForegroundColor Cyan
$suites = @(
    'smoke-test-ascii.ps1',
    'money-chain-test.ps1',
    'mp-flow-test.ps1',
    'round2-test.ps1',
    'round3-test.ps1',
    'round4-test.ps1',
    'round5-test.ps1',
    'round6-test.ps1',
    'round7-test.ps1',
    'round8-test.ps1',
    'round9-test.ps1',
    'round10-test.ps1'
)
$totalPass = 0
$totalFail = 0
$rows = @()

# Declared assertion counts, straight from the source of each suite.
# Why: an exception thrown inside a Check argument (e.g. "[int]" on the mysql error text)
# terminates that statement BEFORE Check runs, so the assertion silently disappears and the
# suite still reports all-green. Comparing declared vs executed is the only way to see it.
$declared = @{}
$bomTool = Join-Path $root 'scripts\tools\ensure-bom.js'
if (Test-Path $bomTool) {
    $json = & node $bomTool --json 2>$null
    if ($json) {
        ($json | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $declared[$_.Name] = [int]$_.Value }
        Write-Host ("  declared assertions loaded for " + $declared.Count + " scripts") -ForegroundColor Green
    }
}

foreach ($s in $suites) {
    $path = Join-Path $root ('scripts\' + $s)
    if (-not (Test-Path $path)) { continue }
    Write-Host ''
    Write-Host ("--- " + $s + " ---") -ForegroundColor Yellow
    $out = & powershell -ExecutionPolicy Bypass -File $path 2>&1
    $line = ($out | Select-String -Pattern 'RESULT: PASS=' | Select-Object -Last 1)
    if ($line -eq $null) {
        $rows += [pscustomobject]@{ suite = $s; pass = 0; fail = -1; note = 'no RESULT line' }
        $totalFail++
        $out | Select-Object -Last 12 | ForEach-Object { Write-Host $_ }
        continue
    }
    $m = [regex]::Match($line.Line, 'PASS=(\d+) FAIL=(\d+)')
    $p = [int]$m.Groups[1].Value
    $f = [int]$m.Groups[2].Value
    $totalPass += $p
    $totalFail += $f
    $note = ''
    $decl = $declared[$s]
    if ($decl -ne $null -and $decl -gt 0 -and ($p + $f) -ne $decl) {
        $note = 'ASSERTION COUNT MISMATCH declared=' + $decl + ' executed=' + ($p + $f)
        $totalFail++
        Write-Host ('  [FAIL] ' + $note) -ForegroundColor Red
    }
    $rows += [pscustomobject]@{ suite = $s; pass = $p; fail = $f; note = $note }
    $out | Select-String -Pattern '\[FAIL\]' | ForEach-Object { Write-Host $_.Line -ForegroundColor Red }
}

Write-Host ''
Write-Host '=== summary ===' -ForegroundColor Cyan
$rows | ForEach-Object {
    $color = if ($_.fail -eq 0 -and $_.note -eq '') { 'Green' } else { 'Red' }
    Write-Host ("  {0,-24} PASS={1,-4} FAIL={2}  {3}" -f $_.suite, $_.pass, $_.fail, $_.note) -ForegroundColor $color
}
Write-Host ''
$executed = [int](($rows | Measure-Object pass -Sum).Sum + ($rows | Measure-Object fail -Sum).Sum)
$declaredTotal = [int](($declared.Values | Measure-Object -Sum).Sum)
Write-Host ("executed assertions (PASS+FAIL) = {0}  |  declared in sources = {1}" -f $executed, $declaredTotal) -ForegroundColor Gray
if ($declaredTotal -gt 0 -and $executed -ne $declaredTotal) {
    Write-Host 'WARNING: executed != declared -- at least one assertion never ran' -ForegroundColor Red
    $totalFail++
}
Write-Host ("TOTAL: PASS={0} FAIL={1}" -f $totalPass, $totalFail) -ForegroundColor Yellow
if ($totalFail -gt 0) { exit 1 }
