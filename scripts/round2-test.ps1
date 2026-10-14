# Second round smoke test: cards / level engine / dashboard reports
# Usage: powershell -ExecutionPolicy Bypass -File scripts/round2-test.ps1
# ASCII-only for Windows PowerShell 5.1 compatibility.
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
    $a = @{ Uri = "$base$path"; Method = $method; Headers = $h; TimeoutSec = 30 }
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

Write-Host '=== login ===' -ForegroundColor Cyan
$login = PostR '/api/admin/auth/login' @{ username = 'admin'; password = 'Admin@123' } $null $null
if ($login.code -ne 0) { Write-Host 'login failed' -ForegroundColor Red; exit 1 }
$H = @{ Authorization = "Bearer " + $login.data.accessToken }
Check 'login with 86 perms' ($login.data.userInfo.perms.Count -ge 80) ("perms=" + $login.data.userInfo.perms.Count)

Write-Host '=== prepare member with growth ===' -ForegroundColor Cyan
$phone = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$m = PostR '/api/admin/members' @{ phone = $phone; realName = 'R2 Test'; nickname = 'r2'; storeId = 1; source = 1 } $H $null
Check 'member created' ($m.code -eq 0) ("id=" + $m.data.id)
$memberId = $m.data.id
# give growth via adjustment to trigger level evaluation
$adj = PostR '/api/admin/accounts/adjust' @{ memberId = $memberId; growthDelta = 3500; reason = 'round2 growth seed' } $H ([guid]::NewGuid().ToString())
Check 'growth adjusted to 3500' ($adj.code -eq 0 -and $adj.data.growth -eq 3500) ("growth=" + $adj.data.growth)

Write-Host '=== 1. level evaluation engine ===' -ForegroundColor Cyan
$rule = GetR '/api/admin/report/levels/3/rule-inspect' $H
Check 'rule parsed' ($rule.code -eq 0 -and $rule.data.Count -ge 1) ("conditions=" + $rule.data.Count + " first=" + ($rule.data[0].field) + ($rule.data[0].op) + ($rule.data[0].value))

$sim = GetR "/api/admin/report/levels/simulate/$memberId" $H
Check 'simulate predicts target' ($sim.code -eq 0) ("current=" + $sim.data.currentLevel + " target=" + $sim.data.targetLevel + " willChange=" + $sim.data.willChange)

$eval = PostR "/api/admin/report/levels/evaluate/$memberId" $null $H $null
Check 'evaluate upgraded to Gold(3000)' ($eval.code -eq 0 -and $eval.data.changed -eq $true) ("action=" + $eval.data.action + " level=" + $eval.data.levelName)

$prof = GetR "/api/admin/members/$memberId" $H
Check 'member level persisted' ($prof.data.levelId -eq 3) ("levelId=" + $prof.data.levelId)

$recalc = PostR "/api/admin/report/levels/recalculate?batchSize=100" $null $H $null
Check 'batch recalculate' ($recalc.code -eq 0) ("scanned=" + $recalc.data.scanned + " upgraded=" + $recalc.data.upgraded)

Write-Host '=== 2. card product + sell + verify ===' -ForegroundColor Cyan
$prods = GetR '/api/admin/cards/products' $H
Check 'card products listed' ($prods.code -eq 0 -and $prods.data.Count -ge 2) ("count=" + $prods.data.Count)
$prod = $prods.data | Where-Object { $_.totalTimes -eq 10 } | Select-Object -First 1

$sell = PostR '/api/admin/cards/sell' @{ memberId = $memberId; cardProductId = $prod.id; payMethod = 2; storeId = 1 } $H ([guid]::NewGuid().ToString())
Check 'card sold' ($sell.code -eq 0) ("cardNo=" + $sell.data.cardNo + " times=" + $sell.data.totalTimes + " paid=" + $sell.data.paidAmount)
$cardNo = $sell.data.cardNo

$cards = GetR "/api/admin/cards/members?memberId=$memberId" $H
Check 'member holds card' ($cards.code -eq 0 -and $cards.data.Count -ge 1) ("cards=" + $cards.data.Count + " remain=" + $cards.data[0].remainTimes)

$verify = PostR "/api/admin/cards/$cardNo/verify" @{ times = 1; storeId = 1; remark = 'round2 verify' } $H ([guid]::NewGuid().ToString())
Check 'card verified' ($verify.code -eq 0) ("before=" + $verify.data.beforeTimes + " after=" + $verify.data.afterTimes + " cardRemain=" + $verify.data.cardRemainTimes)
Check 'remain decremented to 9' ($verify.data.cardRemainTimes -eq 9) ("cardRemain=" + $verify.data.cardRemainTimes)

$logs = GetR "/api/admin/cards/verify-logs?cardNo=$cardNo" $H
Check 'verify log recorded' ($logs.code -eq 0 -and $logs.data.total -ge 1) ("logs=" + $logs.data.total)

$detail = GetR "/api/admin/cards/members/$cardNo" $H
Check 'card detail with items' ($detail.code -eq 0 -and $detail.data.items.Count -ge 1) ("items=" + $detail.data.items.Count)

Write-Host '=== 3. card freeze/extend/refund ===' -ForegroundColor Cyan
$freeze = PostR "/api/admin/cards/$cardNo/freeze?freeze=true&remark=r2" $null $H $null
Check 'card frozen' ($freeze.code -eq 0) ("code=" + $freeze.code)
$verifyFrozen = PostR "/api/admin/cards/$cardNo/verify" @{ times = 1 } $H ([guid]::NewGuid().ToString())
Check 'frozen card rejected' ($verifyFrozen.code -eq 50007) ("code=" + $verifyFrozen.code + " msg=" + $verifyFrozen.msg)
$unfreeze = PostR "/api/admin/cards/$cardNo/freeze?freeze=false" $null $H $null
Check 'card unfrozen' ($unfreeze.code -eq 0) ("code=" + $unfreeze.code)

$extend = PostR "/api/admin/cards/$cardNo/extend?days=30&reason=r2" $null $H $null
Check 'card extended' ($extend.code -eq 0) ("code=" + $extend.code)

Write-Host "=== 4. dashboard and reports ===" -ForegroundColor Cyan
$ov = GetR '/api/admin/report/dashboard/overview?days=30' $H
Check 'overview' ($ov.code -eq 0) ("todayOrders=" + $ov.data.today.orderCount + " liability=" + $ov.data.liability.balanceLiability)
Check 'member overview' ($ov.data.memberOverview.memberCount -ge 1) ("members=" + $ov.data.memberOverview.memberCount + " active30d=" + $ov.data.memberOverview.activeCount30d)
Check 'level distribution' ($ov.data.levelDistribution.Count -ge 5) ("levels=" + $ov.data.levelDistribution.Count)

$trend = GetR '/api/admin/report/dashboard/member-trend?days=14' $H
Check 'member trend 14 days' ($trend.code -eq 0 -and $trend.data.Count -eq 14) ("points=" + $trend.data.Count)

$ret = GetR '/api/admin/report/dashboard/retention?months=3' $H
Check 'retention report' ($ret.code -eq 0) ("cohorts=" + $ret.data.Count)

$rfm = GetR '/api/admin/report/dashboard/rfm' $H
# RFM only covers members with consumption history; empty result is expected in this script
Check 'rfm report callable' ($rfm.code -eq 0) ("total=" + $rfm.data.total + " segmentCount=" + $rfm.data.segments.Count)

$sleep = GetR "/api/admin/report/dashboard/sleeping-members?days=0&limit=10" $H
Check 'sleeping members' ($sleep.code -eq 0) ("count=" + $sleep.data.Count)

$rank = GetR '/api/admin/report/report/product-ranking?limit=5' $H
Check 'product ranking' ($rank.code -eq 0) ("rows=" + $rank.data.Count)

$lia = GetR '/api/admin/report/report/liability' $H
Check 'liability report' ($lia.code -eq 0) ("balance=" + $lia.data.balanceLiability + " points=" + $lia.data.pointsLiability)

Write-Host '=== 5. verify ledger still consistent ===' -ForegroundColor Cyan
$rec = GetR '/api/admin/accounts/reconcile' $H
Check 'reconcile after card and level ops' ($rec.data.diffCount -eq 0) ($rec.data.conclusion)

Write-Host ("RESULT: PASS={0} FAIL={1}" -f $script:pass, $script:fail) -ForegroundColor Yellow
if ($script:fail -gt 0) { exit 1 }
