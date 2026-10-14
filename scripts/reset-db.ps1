# 重置开发数据库（删除并重建 member_system，随后由 Flyway 执行 V1/V2 全量迁移）
# 用途：开发期修改了已应用的迁移脚本导致 Flyway 校验和不匹配，或需要干净环境
# 警告：会删除全部数据，仅限开发环境使用
# 用法: powershell -ExecutionPolicy Bypass -File scripts/reset-db.ps1
param(
    [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe',
    [string]$User = 'root',
    [string]$Password = '123456',
    [string]$Database = 'member_system',
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'

if (-not $Yes) {
    $answer = Read-Host "将删除数据库 [$Database] 的全部数据，确认继续？输入 YES"
    if ($answer -ne 'YES') { Write-Host '已取消'; exit 0 }
}

if (-not (Test-Path $MysqlExe)) { throw "未找到 mysql 客户端：$MysqlExe" }

Write-Host "删除并重建数据库 $Database ..." -ForegroundColor Cyan
& $MysqlExe -u$User "--password=$Password" -e "DROP DATABASE IF EXISTS ``$Database``; CREATE DATABASE ``$Database`` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>&1 |
    Where-Object { $_ -notmatch 'Warning' }

Write-Host "[OK] 数据库已重置为空库；重新启动后端时 Flyway 会自动执行 db/migration 下的全部迁移脚本。" -ForegroundColor Green
Write-Host "     启动命令: cd backend; java -jar target\member-system.jar --spring.profiles.active=local" -ForegroundColor Green
