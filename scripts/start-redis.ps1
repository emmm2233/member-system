# Start (or stop) the portable Redis used to verify member.cache.type=redis.
#
# Why a script instead of "just run redis-server":
#   * the project keeps everything portable under tools/ (same as portable Maven),
#     and tools/ is gitignored, so nothing leaks into the repo;
#   * the JVM must outlive this shell -- the test runner is often a background job
#     whose process tree gets torn down (same reason start-backend.ps1 uses WMI);
#   * the local dev instance must NOT persist to disk (no RDB/AOF), otherwise a
#     stale dump gets loaded and "empty cache" assertions behave unpredictably.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\start-redis.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\start-redis.ps1 -Stop
#
# ASCII-only on purpose (Windows PowerShell 5.1 parses this reliably regardless of encoding).
param(
    [int]$Port = 6379,
    [switch]$Stop
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$server = Get-ChildItem (Join-Path $root 'tools') -Recurse -Filter 'redis-server.exe' -ErrorAction SilentlyContinue |
    Select-Object -First 1
$cli = Get-ChildItem (Join-Path $root 'tools') -Recurse -Filter 'redis-cli.exe' -ErrorAction SilentlyContinue |
    Select-Object -First 1

if (-not $server) {
    Write-Host 'redis-server.exe not found under tools/.' -ForegroundColor Red
    Write-Host 'Download the portable build, see the Redis runbook (docs/15) and extract to tools\redis-<ver>\' -ForegroundColor Yellow
    exit 1
}
$redisHome = $server.Directory.FullName

function Test-RedisUp {
    param([int]$p)
    try {
        $c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction Stop
        return ($c | Measure-Object).Count -gt 0
    } catch { return $false }
}

if ($Stop) {
    Get-CimInstance Win32_Process -Filter "Name='redis-server.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*--port $Port*" -or $_.CommandLine -like "*:$Port*" -or $_.CommandLine -eq $null } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 2
    if (Test-RedisUp $Port) { Write-Host ("port " + $Port + " still listening") -ForegroundColor Yellow; exit 1 }
    Write-Host 'redis stopped' -ForegroundColor Green
    exit 0
}

if (Test-RedisUp $Port) {
    Write-Host ("redis already listening on " + $Port) -ForegroundColor Green
} else {
    $dataDir = Join-Path $root 'logs\redis'
    if (-not (Test-Path $dataDir)) { New-Item -ItemType Directory -Path $dataDir -Force | Out-Null }
    $logFile = Join-Path $root 'logs\redis-stdout.log'
    $args = '--port ' + $Port + ' --bind 127.0.0.1 --save "" --appendonly no --maxmemory 256mb' +
            ' --dir "' + $dataDir + '" --logfile "' + $logFile + '"'
    $cmdLine = 'cmd /c cd /d "' + $redisHome + '" && redis-server.exe ' + $args
    $created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmdLine }
    if ($created.ReturnValue -ne 0) {
        Write-Host ("failed to spawn redis, Win32_Process.Create returned " + $created.ReturnValue) -ForegroundColor Red
        exit 1
    }
    Write-Host ("redis pid=" + $created.ProcessId + " home=" + $redisHome) -ForegroundColor Green
}

# Wait for PING (the port is not enough: the server opens it slightly before it is ready)
$pong = $null
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 2
    if ($cli) {
        $pong = & $cli.FullName -h 127.0.0.1 -p $Port ping 2>&1 | Select-Object -First 1
    } else {
        $pong = if (Test-RedisUp $Port) { 'PONG(port-only)' } else { $null }
    }
    if ($pong -match 'PONG') { break }
}
if ($pong -match 'PONG') {
    if ($cli) {
        $ver = & $cli.FullName -h 127.0.0.1 -p $Port info server 2>&1 |
            Select-String -Pattern 'redis_version' | Select-Object -First 1
        $dbsize = & $cli.FullName -h 127.0.0.1 -p $Port dbsize 2>&1 | Select-Object -First 1
        Write-Host ("redis UP port=" + $Port + " " + ($ver -replace '\s+', '') + " dbsize=" + $dbsize) -ForegroundColor Green
    } else {
        Write-Host ("redis UP port=" + $Port + " (redis-cli not found)") -ForegroundColor Green
    }
} else {
    Write-Host 'redis did not answer PING' -ForegroundColor Red
    $lf = Join-Path $root 'logs\redis-stdout.log'
    if (Test-Path $lf) { Get-Content $lf -Tail 15 }
    exit 1
}
