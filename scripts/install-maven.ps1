# 下载便携版 Maven 到 tools/ 目录（本机未安装 Maven，避免污染系统环境）
# 用法: powershell -ExecutionPolicy Bypass -File scripts/install-maven.ps1
$ErrorActionPreference = 'Stop'

$root   = Split-Path -Parent $PSScriptRoot
$tools  = Join-Path $root 'tools'
$target = Join-Path $tools 'apache-maven-3.9.9'
$mvnCmd = Join-Path $target 'bin\mvn.cmd'

if (Test-Path $mvnCmd) {
    Write-Host "[OK] Maven 已存在: $mvnCmd" -ForegroundColor Green
    & $mvnCmd -v
    exit 0
}

New-Item -ItemType Directory -Force -Path $tools | Out-Null

$url = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip'
$zip = Join-Path $tools 'apache-maven-3.9.9-bin.zip'

Write-Host "[1/2] 下载 Maven 3.9.9 ..." -ForegroundColor Cyan
Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing

Write-Host "[2/2] 解压到 $target ..." -ForegroundColor Cyan
Expand-Archive -Path $zip -DestinationPath $tools -Force
Remove-Item $zip -Force

if (Test-Path $mvnCmd) {
    Write-Host "[OK] Maven 安装完成" -ForegroundColor Green
    & $mvnCmd -v
} else {
    throw "Maven 安装失败：未找到 $mvnCmd"
}
