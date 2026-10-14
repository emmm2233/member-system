# 启动后端服务（开发用）
# 用法: powershell -ExecutionPolicy Bypass -File scripts/start-backend.ps1
# 行为: 打包 → 停掉旧实例 → 后台启动 JVM → 轮询健康检查直到就绪 → 脚本退出（JVM 继续运行）
# 停止: 再次运行本脚本会自动停掉旧实例；或 Get-CimInstance Win32_Process -Filter "Name='java.exe'" 后 Stop-Process
$ErrorActionPreference = 'Stop'
$root  = Split-Path -Parent $PSScriptRoot
$mvn   = Join-Path $root 'tools\apache-maven-3.9.9\bin\mvn.cmd'
$bdir  = Join-Path $root 'backend'

if (-not (Test-Path $mvn)) {
    throw "未找到 Maven：$mvn，请先执行 scripts/install-maven.ps1"
}

# 停止已在运行的实例
# 注意：判定条件必须与实际启动命令行一致（-jar target\member-system.jar）。
# 早期版本写成 '*MemberApplication*'，那是主类名，而这里用的是可执行 jar，
# 结果旧实例从没被停掉：新进程起不来（端口被占），日志里却只看到 "未就绪"。
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*member-system.jar*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host "已停止旧实例 PID $($_.ProcessId)" }
Start-Sleep -Seconds 2

Write-Host "正在打包（跳过测试）..." -ForegroundColor Cyan
Push-Location $bdir
try {
    & $mvn -q -B -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "Maven 打包失败" }
} finally {
    Pop-Location
}

$jar = Join-Path $bdir 'target\member-system.jar'
if (-not (Test-Path $jar)) { throw "未找到构建产物：$jar" }

$logDir = Join-Path $root 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$stdout = Join-Path $logDir 'backend-stdout.log'
$stderr = Join-Path $logDir 'backend-stderr.log'

Write-Host "启动服务（profile=local）..." -ForegroundColor Cyan
# 通过 WMI 创建进程，而不是 Start-Process：
#   1) Start-Process -RedirectStandardOutput 会让调用方（尤其是把本脚本输出再管道给别的命令时）
#      一直等待重定向句柄关闭，表现为脚本「卡死」；被强杀时 JVM 会一起被带走。
#   2) WMI 创建的进程父级是 WMI provider，不在当前 shell 的进程树里，
#      脚本退出（甚至被结束）后后端仍在运行。
$cmdLine = 'cmd /c cd /d "' + $bdir + '" && java -jar target\member-system.jar --spring.profiles.active=local > "' + $stdout + '" 2> "' + $stderr + '"'
$created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmdLine }
if ($created.ReturnValue -ne 0) { throw "启动失败，Win32_Process.Create 返回 $($created.ReturnValue)" }

Write-Host "PID=$($created.ProcessId)  日志: $stdout" -ForegroundColor Green

# 等待就绪
# 用 Invoke-RestMethod 而不是 Invoke-WebRequest -UseBasicParsing：
# 后者的 .Content 在响应体被识别为 application/*+json 时返回的是 **byte[]**，
# `$r.Content -match '"status":"UP"'` 永远匹配不上 —— 服务其实已经起来了，
# 脚本却报「未就绪」并打印日志尾巴，非常误导。
for ($i = 1; $i -le 40; $i++) {
    Start-Sleep -Seconds 2
    try {
        $h = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 3
        if ($h.status -eq 'UP') {
            Write-Host "[OK] 服务已就绪: http://127.0.0.1:8080" -ForegroundColor Green
            Write-Host "     Swagger: http://127.0.0.1:8080/swagger-ui/index.html" -ForegroundColor Green
            exit 0
        }
    } catch { }
}
Write-Host "[WARN] 80 秒内未就绪，请查看日志：$stdout" -ForegroundColor Yellow
Get-Content $stdout -Tail 30
exit 1
