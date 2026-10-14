# Smoke test: health -> login -> menus -> member create -> 360 view -> recharge -> idempotency
# Usage: powershell -ExecutionPolicy Bypass -File scripts/smoke-test.ps1
# NOTE: kept ASCII-only so Windows PowerShell 5.1 parses it correctly regardless of file encoding.
$ErrorActionPreference = 'Continue'
$base = 'http://127.0.0.1:8080'
$script:pass = 0
$script:fail = 0

function Check($name, $ok, $detail) {
    if ($ok) {
        Write-Host ("  [PASS] {0}  {1}" -f $name, $detail) -ForegroundColor Green
        $script:pass++
    } else {
        Write-Host ("  [FAIL] {0}  {1}" -f $name, $detail) -ForegroundColor Red
        $script:fail++
    }
}

function Json($obj) {
    return ($obj | ConvertTo-Json -Depth 6 -Compress)
}

Write-Host '=== 1. health ===' -ForegroundColor Cyan
try {
    $h = Invoke-RestMethod -Uri "$base/actuator/health" -TimeoutSec 10
    Check 'health' ($h.status -eq 'UP') ("status=" + $h.status)
} catch { Check 'health' $false $_.Exception.Message }

Write-Host '=== 2. admin login ===' -ForegroundColor Cyan
$token = $null
try {
    $login = Invoke-RestMethod -Uri "$base/api/admin/auth/login" -Method Post `
        -Body '{"username":"admin","password":"Admin@123"}' -ContentType 'application/json' -TimeoutSec 15
    Check 'login' ($login.code -eq 0) ("code=" + $login.code + " msg=" + $login.msg)
    if ($login.code -eq 0) {
        $token = $login.data.accessToken
        Check 'perms loaded' ($login.data.userInfo.perms.Count -gt 30) ("perms=" + $login.data.userInfo.perms.Count)
    }
} catch { Check 'login' $false $_.Exception.Message }

if (-not $token) { Write-Host 'login failed, abort' -ForegroundColor Red; exit 1 }
$H = @{ Authorization = "Bearer $token"; 'Content-Type' = 'application/json' }

Write-Host '=== 3. menus ===' -ForegroundColor Cyan
$menus = Invoke-RestMethod -Uri "$base/api/admin/auth/menus" -Headers $H -TimeoutSec 10
Check 'menu tree' ($menus.data.Count -ge 8) ("topLevel=" + $menus.data.Count)

Write-Host '=== 4. create member ===' -ForegroundColor Cyan
$phone = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$bodyObj = @{
    phone    = $phone
    realName = [System.Text.Encoding]::UTF8.GetString([System.Text.Encoding]::UTF8.GetBytes('Zhang San'))
    nickname = 'TestUser'
    gender   = 1
    birthday = '1995-06-18'
    storeId  = 1
    source   = 1
}
$bodyText = Json $bodyObj
$member = Invoke-RestMethod -Uri "$base/api/admin/members" -Method Post -Headers $H `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($bodyText)) -TimeoutSec 15
Check 'member create' ($member.code -eq 0) ("code=" + $member.code + " msg=" + $member.msg)
if ($member.code -ne 0) { Write-Host 'create failed, abort' -ForegroundColor Red; exit 1 }
$memberId = $member.data.id
Write-Host ("       memberNo=" + $member.data.memberNo + " id=" + $memberId + " phone=" + $member.data.phoneMasked)

Write-Host '=== 5. duplicate phone rejected ===' -ForegroundColor Cyan
$dup = Invoke-RestMethod -Uri "$base/api/admin/members" -Method Post -Headers $H `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($bodyText)) -TimeoutSec 15
Check 'duplicate phone rejected' ($dup.code -eq 20001) ("code=" + $dup.code + " msg=" + $dup.msg)

Write-Host '=== 6. member 360 overview ===' -ForegroundColor Cyan
$ov = Invoke-RestMethod -Uri "$base/api/admin/members/$memberId/overview" -Headers $H -TimeoutSec 10
$bal = $ov.data.account.balance
Check 'overview' ($ov.code -eq 0 -and $bal -eq 0) ("balance=" + $bal + " level=" + $ov.data.member.levelName)

Write-Host '=== 7. member search ===' -ForegroundColor Cyan
$list = Invoke-RestMethod -Uri "$base/api/admin/members?keyword=$phone`&pageNum=1`&pageSize=10" -Headers $H -TimeoutSec 10
Check 'search by phone' ($list.data.total -ge 1) ("hits=" + $list.data.total)

Write-Host '=== 8. account change (adjust +100 points) ===' -ForegroundColor Cyan
$adjBody = Json @{ memberId = $memberId; pointsDelta = 100; reason = 'smoke test grant' }
$adj = Invoke-RestMethod -Uri "$base/api/admin/accounts/adjust" -Method Post -Headers $H `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($adjBody)) -TimeoutSec 15
Check 'points adjust' ($adj.code -eq 0) ("code=" + $adj.code + " msg=" + $adj.msg + " points=" + $adj.data.points)

Write-Host '=== 9. flow ledger written ===' -ForegroundColor Cyan
$flows = Invoke-RestMethod -Uri "$base/api/admin/accounts/$memberId/point-flows?pageNum=1`&pageSize=10" -Headers $H -TimeoutSec 10
Check 'point flow recorded' ($flows.data.total -ge 1) ("flows=" + $flows.data.total)
if ($flows.data.total -ge 1) {
    $f = $flows.data.list[0]
    Check 'flow before/after snapshot' ($f.beforePoints -eq 0 -and $f.afterPoints -eq 100) `
        ("before=" + $f.beforePoints + " after=" + $f.afterPoints)
}

Write-Host '=== 10. balance-vs-flow reconcile ===' -ForegroundColor Cyan
$rec = Invoke-RestMethod -Uri "$base/api/admin/accounts/reconcile" -Headers $H -TimeoutSec 15
Check 'reconcile no diff' ($rec.code -eq 0) (Json $rec.data)

Write-Host ("RESULT: PASS={0} FAIL={1}" -f $script:pass, $script:fail) -ForegroundColor Yellow
if ($script:fail -gt 0) { exit 1 }
