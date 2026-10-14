# Round 4 smoke test: coupon templates + grant wizard + level rules + RBAC + dashboard
# Usage: powershell -ExecutionPolicy Bypass -File scripts\round4-test.ps1
# ASCII-only + BOM + LF for Windows PowerShell 5.1 compatibility.
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
    $a = @{ Uri = "$base$path"; Method = $method; Headers = $h; TimeoutSec = 60 }
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

Write-Host '=== login ===' -ForegroundColor Cyan
$login = PostR '/api/admin/auth/login' @{ username = 'admin'; password = 'Admin@123' } $null $null
if ($login.code -ne 0) { Write-Host 'login failed' -ForegroundColor Red; exit 1 }
$H = @{ Authorization = "Bearer " + $login.data.accessToken }
Check 'login ok' ($login.code -eq 0) ("perms=" + $login.data.userInfo.perms.Count)

Write-Host '=== prepare: 6 members ===' -ForegroundColor Cyan
$seed = (Get-Random -Minimum 10000000 -Maximum 99999999)
$memberIds = @()
for ($i = 0; $i -lt 6; $i++) {
    $phone = '138' + ([string]($seed + $i))
    $m = PostR '/api/admin/members' @{ phone = $phone; realName = "R4-$i"; nickname = "r4m$i"; storeId = 1; source = 1 } $H $null
    if ($m.code -eq 0) { $memberIds += $m.data.id }
}
Check 'members created' ($memberIds.Count -eq 6) ("count=" + $memberIds.Count)

Write-Host '=== 1. coupon template CRUD ===' -ForegroundColor Cyan
$tplBody = @{
    tplName = 'R4 cut-10-over-50'
    couponType = 1
    faceValue = 10
    minAmount = 50
    validType = 2
    validDays = 15
    totalQty = 100
    limitPerMember = 2
    scopeStoreIds = @()
    scopeProductIds = @()
    allowStack = $false
    stackGroup = 'R4'
    claimable = $true
    useRuleDesc = 'min 50 per order'
    status = 1
}
$tpl = PostR '/api/admin/marketing/coupon-tpls' $tplBody $H $null
Check 'template created' ($tpl.code -eq 0) ("id=" + $tpl.data.id + " code=" + $tpl.data.tplCode)
$tplId = $tpl.data.id

$tplList = GetR '/api/admin/marketing/coupon-tpls/list?keyword=R4' $H
Check 'template searchable' ($tplList.code -eq 0 -and $tplList.data.Count -ge 1) ("found=" + $tplList.data.Count)

$tplUpd = PutR ("/api/admin/marketing/coupon-tpls/" + $tplId) @{ tplName = 'R4 cut-12-over-50'; couponType = 1; faceValue = 12; minAmount = 50; validType = 2; validDays = 20; totalQty = 100; limitPerMember = 2; scopeStoreIds = @(); scopeProductIds = @(); allowStack = $false; claimable = $true; status = 1 } $H
Check 'template updated' ($tplUpd.code -eq 0 -and [decimal]$tplUpd.data.faceValue -eq 12) ("faceValue=" + $tplUpd.data.faceValue)

$badTpl = PostR '/api/admin/marketing/coupon-tpls' @{ tplName = 'invalid'; couponType = 1; faceValue = 0; minAmount = 0; validType = 2; validDays = 10 } $H $null
Check 'invalid template rejected' ($badTpl.code -eq 10001) ("code=" + $badTpl.code + " msg=" + $badTpl.msg)

Write-Host '=== 2. grant wizard: estimate then grant ===' -ForegroundColor Cyan
$grantBase = @{
    tplId = $tplId
    targetType = 'MEMBER'
    memberIds = $memberIds[0..2]
    qtyPerMember = 2
    reason = 'round4 targeted grant'
}
$est = PostR '/api/admin/marketing/coupons/estimate' $grantBase $H $null
Check 'estimate target count (3)' ($est.code -eq 0 -and $est.data.count -eq 3) ("count=" + $est.data.count)

$grant = PostR '/api/admin/marketing/coupons/grant-batch' $grantBase $H $null
Check 'targeted grant ok' ($grant.code -eq 0 -and $grant.data.successCount -eq 3) ("success=" + $grant.data.successCount + " issued=" + $grant.data.totalIssued + " task=" + $grant.data.taskNo)
Check 'each member got 2 coupons' ($grant.data.totalIssued -eq 6) ("totalIssued=" + $grant.data.totalIssued)

$progress = GetR ("/api/admin/marketing/coupons/grant-task/" + $grant.data.taskNo) $H
Check 'grant progress queryable' ($progress.code -eq 0 -and $progress.data.finished -eq $true) ("percent=" + $progress.data.percent)

$coupons = GetR ("/api/admin/marketing/coupons?memberId=" + $memberIds[0] + "&status=1") $H
Check 'member holds 2 coupons' ($coupons.data.total -eq 2) ("total=" + $coupons.data.total)

Write-Host '=== 3. grant by condition ===' -ForegroundColor Cyan
$condGrant = @{
    tplId = $tplId
    targetType = 'CONDITION'
    memberIds = @()
    qtyPerMember = 1
    reason = 'round4 condition grant'
    condition = @{ levelIds = @(); tagIds = @(); status = 1; limit = 100 }
}
$est2 = PostR '/api/admin/marketing/coupons/estimate' $condGrant $H $null
Check 'condition estimate all members' ($est2.data.count -ge 6) ("count=" + $est2.data.count)

$grant2 = PostR '/api/admin/marketing/coupons/grant-batch' $condGrant $H $null
Check 'condition grant ok' ($grant2.code -eq 0 -and $grant2.data.successCount -eq $est2.data.count) ("success=" + $grant2.data.successCount + " fail=" + $grant2.data.failCount)

Write-Host '=== 4. grant ALL requires confirmation ===' -ForegroundColor Cyan
$allNoConfirm = @{ tplId = $tplId; targetType = 'ALL'; memberIds = @(); qtyPerMember = 1; reason = 'r4 all'; confirmAll = $false }
$all1 = PostR '/api/admin/marketing/coupons/grant-batch' $allNoConfirm $H $null
Check 'ALL without confirm rejected' ($all1.code -eq 10001) ("code=" + $all1.code + " msg=" + $all1.msg)

Write-Host '=== 5. per-member limit enforced ===' -ForegroundColor Cyan
$over = PostR '/api/admin/marketing/coupons/grant' @{} $H $null
$overLimit = PostR ("/api/admin/marketing/coupons/grant?memberId=" + $memberIds[0] + "&tplId=" + $tplId + "&qty=5&reason=r4+limit") $null $H $null
Check 'limit per member enforced' ($overLimit.code -eq 50009) ("code=" + $overLimit.code + " msg=" + $overLimit.msg)

Write-Host '=== 6. template statistics ===' -ForegroundColor Cyan
$stat = GetR ("/api/admin/marketing/coupon-tpls/" + $tplId + "/statistics") $H
Check 'template statistics' ($stat.code -eq 0 -and $stat.data.couponCount -gt 0) ("coupons=" + $stat.data.couponCount + " issued=" + $stat.data.issuedQty + " useRate=" + $stat.data.useRate)

Write-Host '=== 7. delete template guard ===' -ForegroundColor Cyan
$delCode = $null
try {
    $del = Req 'DELETE' ("/api/admin/marketing/coupon-tpls/" + $tplId) $null $H $null
    $delCode = $del.code
} catch { $delCode = -1 }
Check 'issued template cannot be deleted' ($delCode -eq 90012) ("code=" + $delCode)

$stop = PutR ("/api/admin/marketing/coupon-tpls/" + $tplId + "/status?status=0") $null $H
Check 'template can be disabled' ($stop.code -eq 0) ("code=" + $stop.code)
$grantDisabled = PostR '/api/admin/marketing/coupons/grant-batch' $grantBase $H $null
Check 'disabled template cannot grant' ($grantDisabled.code -eq 50001) ("code=" + $grantDisabled.code + " msg=" + $grantDisabled.msg)

Write-Host '=== 8. level rule editor ===' -ForegroundColor Cyan
$fields = GetR '/api/admin/report/levels/rule-fields' $H
Check 'rule fields whitelist' ($fields.code -eq 0 -and $fields.data.Count -ge 8) ("fields=" + $fields.data.Count)

$levels = GetR '/api/admin/report/levels' $H
Check 'level list with rules' ($levels.code -eq 0 -and $levels.data.Count -eq 5) ("levels=" + $levels.data.Count)
$goldLevel = $levels.data | Where-Object { $_.levelValue -eq 3 } | Select-Object -First 1
Check 'level has parsed rule' ($goldLevel.rule.configured -eq $true) ("ruleItems=" + $goldLevel.rule.items.Count)

$newRule = @{
    logicType = 'AND'
    items = @(@{ field = 'growth'; op = '>='; value = 2500 })
    validMonths = 12
}
$rule = PutR ("/api/admin/report/levels/" + $goldLevel.id + "/rule") $newRule $H
Check 'rule saved' ($rule.code -eq 0 -and $rule.data.items.Count -eq 1) ("items=" + $rule.data.items.Count + " value=" + $rule.data.items[0].value)

$badRule = PutR ("/api/admin/report/levels/" + $goldLevel.id + "/rule") @{ logicType = 'AND'; items = @(@{ field = 'notExists'; op = '>='; value = 1 }) } $H
Check 'invalid rule field rejected' ($badRule.code -eq 10001) ("code=" + $badRule.code + " msg=" + $badRule.msg)

$badOp = PutR ("/api/admin/report/levels/" + $goldLevel.id + "/rule") @{ logicType = 'AND'; items = @(@{ field = 'growth'; op = 'LIKE'; value = 1 }) } $H
Check 'invalid operator rejected' ($badOp.code -eq 10001) ("code=" + $badOp.code + " msg=" + $badOp.msg)

Write-Host '=== 9. level CRUD ===' -ForegroundColor Cyan
$newLevel = PostR '/api/admin/report/levels' @{ levelName = 'R4 Diamond Plus'; discountRate = 0.85; pointsRate = 2.5; upgradeGiftPoints = 800; isDefault = 0; validMonths = 0; status = 1; sortNo = 9 } $H $null
Check 'level created' ($newLevel.code -eq 0) ("id=" + $newLevel.data.id + " value=" + $newLevel.data.levelValue)
$newLevelId = $newLevel.data.id

$delLevel = Req 'DELETE' ("/api/admin/report/levels/" + $newLevelId) $null $H $null
Check 'unused level deleted' ($delLevel.code -eq 0) ("code=" + $delLevel.code)

$delDefault = $null
try { $d = Req 'DELETE' '/api/admin/report/levels/1' $null $H $null; $delDefault = $d.code } catch { $delDefault = -1 }
Check 'default level protected' ($delDefault -eq 90012 -or $delDefault -eq 10007) ("code=" + $delDefault)

Write-Host '=== 10. RBAC: user / role / menu ===' -ForegroundColor Cyan
$sysUsers = GetR "/api/admin/system/users?pageNum=1&pageSize=20" $H
Check 'user list' ($sysUsers.code -eq 0 -and $sysUsers.data.total -ge 1) ("total=" + $sysUsers.data.total)

$newUser = PostR '/api/admin/system/users' @{ username = ("r4cashier" + (Get-Random -Minimum 100 -Maximum 999)); password = 'Cashier@123'; realName = 'R4 Cashier'; storeId = 1; dataScope = 2; status = 1; roleIds = @(4) } $H $null
Check 'user created with cashier role' ($newUser.code -eq 0) ("id=" + $newUser.data)
$newUserId = $newUser.data

$dupUser = PostR '/api/admin/system/users' @{ username = 'admin'; password = 'Whatever@123'; realName = 'dup'; roleIds = @() } $H $null
Check 'duplicate username rejected' ($dupUser.code -eq 90011) ("code=" + $dupUser.code)

$resetPwd = PostR ("/api/admin/system/users/" + $newUserId + "/reset-password") $null $H $null
Check 'password reset returns new pwd' ($resetPwd.code -eq 0 -and $resetPwd.data.Length -ge 8) ("pwd len=" + $resetPwd.data.Length)

$sysRoles = GetR '/api/admin/system/roles' $H
Check 'role list with counts' ($sysRoles.code -eq 0 -and $sysRoles.data.Count -ge 5) ("roles=" + $sysRoles.data.Count)
$cashierRole = $sysRoles.data | Where-Object { $_.roleCode -eq 'CASHIER' } | Select-Object -First 1
Check 'cashier role has menus' ($cashierRole.menuCount -gt 0) ("menus=" + $cashierRole.menuCount + " users=" + $cashierRole.userCount)

$roleMenus = GetR ("/api/admin/system/roles/" + $cashierRole.id + "/menus") $H
Check 'role menu ids readable' ($roleMenus.code -eq 0 -and $roleMenus.data.Count -gt 0) ("ids=" + $roleMenus.data.Count)

$menuTree = GetR '/api/admin/system/menus/tree' $H
Check 'menu tree built' ($menuTree.code -eq 0 -and $menuTree.data.Count -ge 8) ("topNodes=" + $menuTree.data.Count)

$newRole = PostR '/api/admin/system/roles' @{ roleCode = ('R4_ROLE_' + (Get-Random -Minimum 100 -Maximum 999)); roleName = 'R4 Test Role'; dataScope = 2; sortNo = 99; status = 1; remark = 'round4' } $H $null
Check 'role created' ($newRole.code -eq 0) ("id=" + $newRole.data)
$newRoleId = $newRole.data

$saveMenus = PutR ("/api/admin/system/roles/" + $newRoleId + "/menus") @(100, 200, 401) $H
Check 'role menus saved' ($saveMenus.code -eq 0) ("code=" + $saveMenus.code)

$readBack = GetR ("/api/admin/system/roles/" + $newRoleId + "/menus") $H
Check 'role menus read back' ($readBack.data.Count -eq 3) ("count=" + $readBack.data.Count)

$delRole = Req 'DELETE' ("/api/admin/system/roles/" + $newRoleId) $null $H $null
Check 'unused role deleted' ($delRole.code -eq 0) ("code=" + $delRole.code)

$delInUse = $null
try { $x = Req 'DELETE' ("/api/admin/system/roles/" + $cashierRole.id) $null $H $null; $delInUse = $x.code } catch { $delInUse = -1 }
Check 'in-use role protected' ($delInUse -eq 90009) ("code=" + $delInUse)

Write-Host '=== 11. logs ===' -ForegroundColor Cyan
$opLogs = GetR "/api/admin/system/op-logs?pageNum=1&pageSize=10" $H
Check 'op logs queryable' ($opLogs.code -eq 0) ("total=" + $opLogs.data.total)

$loginLogs = GetR "/api/admin/system/login-logs?pageNum=1&pageSize=10" $H
Check 'login logs queryable' ($loginLogs.code -eq 0 -and $loginLogs.data.total -ge 1) ("total=" + $loginLogs.data.total)

Write-Host '=== 12. dashboard endpoints ===' -ForegroundColor Cyan
$ov = GetR '/api/admin/report/dashboard/overview?days=30' $H
Check 'overview has all blocks' ($ov.code -eq 0 -and $ov.data.today -ne $null -and $ov.data.liability -ne $null -and $ov.data.levelDistribution -ne $null) ("blocks ok")
$trend = GetR '/api/admin/report/dashboard/member-trend?days=7' $H
Check 'trend 7 points' ($trend.data.Count -eq 7) ("points=" + $trend.data.Count)
$ret = GetR '/api/admin/report/dashboard/retention?months=6' $H
Check 'retention cohorts' ($ret.code -eq 0) ("cohorts=" + $ret.data.Count)
$rfm = GetR '/api/admin/report/dashboard/rfm' $H
Check 'rfm callable' ($rfm.code -eq 0) ("total=" + $rfm.data.total)
$opts = GetR '/api/admin/report/report/store-options' $H
Check 'store options' ($opts.code -eq 0 -and $opts.data.Count -ge 1) ("stores=" + $opts.data.Count)

Write-Host '=== 13. ledger still consistent ===' -ForegroundColor Cyan
$rec = GetR '/api/admin/accounts/reconcile' $H
Check 'reconcile clean' ($rec.data.diffCount -eq 0) ($rec.data.conclusion)

Write-Host ("RESULT: PASS={0} FAIL={1}" -f $script:pass, $script:fail) -ForegroundColor Yellow
if ($script:fail -gt 0) { exit 1 }
