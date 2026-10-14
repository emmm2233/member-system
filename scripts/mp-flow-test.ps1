# Mini-program (MP) end-to-end test: login -> bind phone -> home -> recharge -> mock pay -> ledger
# Usage: powershell -ExecutionPolicy Bypass -File scripts/mp-flow-test.ps1
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
    $args = @{ Uri = "$base$path"; Method = $method; Headers = $h; TimeoutSec = 25 }
    if ($body -ne $null) { $args['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 8 -Compress)) }
    try { return Invoke-RestMethod @args }
    catch {
        $resp = $_.Exception.Response
        if ($resp -ne $null) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $text = $reader.ReadToEnd()
            try { return ($text | ConvertFrom-Json) } catch { return [pscustomobject]@{ code = -1; msg = $text } }
        }
        return [pscustomobject]@{ code = -1; msg = $_.Exception.Message }
    }
}
function GetR($path, $headers) { return Req 'GET' $path $null $headers $null }
function PostR($path, $body, $headers, $idem) { return Req 'POST' $path $body $headers $idem }

Write-Host '=== 1. MP login (mock mode derives stable openid from code) ===' -ForegroundColor Cyan
$code = 'test-code-' + (Get-Random -Minimum 100000 -Maximum 999999)
$login = PostR '/api/mp/auth/login' @{ code = $code } $null $null
Check 'mp login' ($login.code -eq 0) ("code=" + $login.code + " msg=" + $login.msg)
if ($login.code -ne 0) { Write-Host 'login failed, abort' -ForegroundColor Red; exit 1 }
$mpToken = $login.data.accessToken
$MP = @{ Authorization = "Bearer $mpToken" }
Check 'new member auto-created' ($login.data.isNewMember -eq $true) ("memberNo=" + $login.data.member.memberNo)
Check 'needBindPhone flag' ($login.data.needBindPhone -eq $true) ("needBindPhone=" + $login.data.needBindPhone)

Write-Host '=== 2. config status + bind phone ===' -ForegroundColor Cyan
$cfg = GetR '/api/mp/auth/config-status' $MP
Check 'mock login mode active' ($cfg.data.mockLogin -eq $true) ($cfg.data.tip)

$phone = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$bind = PostR '/api/mp/auth/bind-phone' @{ phone = $phone } $MP $null
Check 'bind phone' ($bind.code -eq 0 -and $bind.data.bound -eq $true) ("phone=" + $phone + " merged=" + $bind.data.merged)

$prof = GetR '/api/mp/auth/profile' $MP
Check 'profile has phone' ($prof.data.hasPhone -eq $true) ("masked=" + $prof.data.phoneMasked + " level=" + $prof.data.levelName)

Write-Host '=== 3. home summary BEFORE recharge ===' -ForegroundColor Cyan
$homeData = GetR '/api/mp/home/summary' $MP
Check 'home summary' ($homeData.code -eq 0) ("balance=" + $homeData.data.account.balance + " points=" + $homeData.data.account.points)
Check 'pay enabled for mp' ($homeData.data.payEnabled -eq $true) ("payEnabled=" + $homeData.data.payEnabled)

Write-Host '=== 4. dynamic verify code (60s signed) ===' -ForegroundColor Cyan
$vc = GetR '/api/mp/verify-code' $MP
Check 'verify code generated' ($vc.code -eq 0 -and $vc.data.qrContent.StartsWith('MB:')) ("code=" + $vc.data.code + " expiresIn=" + $vc.data.expiresIn)

$parsed = PostR '/api/mp/verify-code/parse' @{ qrContent = $vc.data.qrContent } $MP $null
Check 'admin-side parse of dynamic code' ($parsed.code -eq 0) ("memberNo=" + $parsed.data.memberNo)

Write-Host '=== 5. tampered code rejected ===' -ForegroundColor Cyan
$tampered = $vc.data.qrContent.Substring(0, $vc.data.qrContent.Length - 4) + 'ffff'
$bad = PostR '/api/mp/verify-code/parse' @{ qrContent = $tampered } $MP $null
Check 'tampered code rejected' ($bad.code -ne 0) ("code=" + $bad.code + " msg=" + $bad.msg)

Write-Host '=== 6. recharge plans ===' -ForegroundColor Cyan
$plans = GetR '/api/mp/recharge/plans' $MP
Check 'plans listed' ($plans.code -eq 0 -and $plans.data.Count -ge 3) ("count=" + $plans.data.Count)
$plan = $plans.data | Where-Object { $_.rechargeAmount -eq 500 } | Select-Object -First 1
Check 'plan 500 exists' ($plan -ne $null) ("gift=" + $plan.giftAmount + " points=" + $plan.giftPoints)

Write-Host '=== 7. create pending recharge order ===' -ForegroundColor Cyan
$order = PostR '/api/mp/recharge/orders' @{ planId = $plan.planId } $MP ([guid]::NewGuid().ToString())
Check 'pending order created' ($order.code -eq 0) ("orderNo=" + $order.data.orderNo + " status=" + $order.data.statusText)
$bizNo = $order.data.orderNo
Check 'order unpaid' ($order.data.status -eq 1) ("status=" + $order.data.status)

Write-Host '=== 8. prepay (mock channel) ===' -ForegroundColor Cyan
$prepay = PostR '/api/mp/pay/prepay' @{ bizType = 1; bizNo = $bizNo; channel = 'MOCK' } $MP ([guid]::NewGuid().ToString())
Check 'prepay ok' ($prepay.code -eq 0) ("paymentNo=" + $prepay.data.paymentNo + " channel=" + $prepay.data.channel)
Check 'mock payParams returned' ($prepay.data.payParams.mock -eq 'true') ("tip=" + $prepay.data.payParams.tip)
$paymentNo = $prepay.data.paymentNo

Write-Host '=== 9. pay query before payment ===' -ForegroundColor Cyan
$q1 = GetR "/api/mp/pay/orders/$paymentNo" $MP
Check 'status unpaid' ($q1.data.status -eq 1) ("status=" + $q1.data.status + " " + $q1.data.statusText)

Write-Host '=== 10. mock pay success (full callback chain + signature verify) ===' -ForegroundColor Cyan
$mock = PostR "/api/mp/pay/orders/$paymentNo/mock-success" $null $MP $null
Check 'mock pay accepted' ($mock.code -eq 0) ("result=" + $mock.data)

$q2 = GetR "/api/mp/pay/orders/$paymentNo" $MP
Check 'payment now paid' ($q2.data.status -eq 3) ("status=" + $q2.data.status + " " + $q2.data.statusText)
Check 'account credited flag' ($q2.data.accountCredited -eq $true) ("accountCredited=" + $q2.data.accountCredited)

Write-Host '=== 11. assets after online recharge ===' -ForegroundColor Cyan
$acc = GetR '/api/mp/account' $MP
Check 'balance = 500' ($acc.data.balance -eq 500) ("balance=" + $acc.data.balance)
Check 'gift = 50' ($acc.data.giftBalance -eq 50) ("gift=" + $acc.data.giftBalance)
Check 'points = 200' ($acc.data.points -eq 200) ("points=" + $acc.data.points)

Write-Host '=== 12. coupon granted by plan ===' -ForegroundColor Cyan
$coupons = GetR '/api/mp/coupons?status=1' $MP
Check 'coupon issued' ($coupons.data.list.Count -ge 1) ("unused=" + $coupons.data.summary.unused)
if ($coupons.data.list.Count -ge 1) {
    $c = $coupons.data.list[0]
    Check 'coupon has expiry' ($c.validEnd -ne $null) ("tpl=" + $c.tplName + " validEnd=" + $c.validEnd + " daysLeft=" + $c.daysLeft)
}

Write-Host '=== 13. bill shows the recharge order ===' -ForegroundColor Cyan
$bills = GetR '/api/mp/orders?orderType=2' $MP
Check 'recharge bill present' ($bills.data.total -ge 1) ("total=" + $bills.data.total)
$detail = GetR "/api/mp/orders/$bizNo" $MP
Check 'bill detail' ($detail.code -eq 0) ("paid=" + $detail.data.paidAmount + " payments=" + $detail.data.payments.Count)

Write-Host '=== 14. idempotent replay: duplicate prepay must not double-credit ===' -ForegroundColor Cyan
$again = PostR '/api/mp/pay/prepay' @{ bizType = 1; bizNo = $bizNo; channel = 'MOCK' } $MP ([guid]::NewGuid().ToString())
Check 're-prepay rejected or reused' ($again.code -eq 0 -or $again.code -eq 70006) ("code=" + $again.code + " msg=" + $again.msg)
$acc2 = GetR '/api/mp/account' $MP
Check 'balance unchanged (no double credit)' ($acc2.data.balance -eq 500) ("balance=" + $acc2.data.balance)

Write-Host '=== 15. levels and stores ===' -ForegroundColor Cyan
$levels = GetR '/api/mp/levels' $MP
Check 'levels listed' ($levels.data.Count -ge 5) ("count=" + $levels.data.Count + " current=" + (($levels.data | Where-Object { $_.current }).levelName))
$stores = GetR '/api/mp/stores' $MP
Check 'stores listed' ($stores.data.Count -ge 1) ("count=" + $stores.data.Count)

Write-Host '=== 16. role isolation: MP token cannot call admin API ===' -ForegroundColor Cyan
$forbidden = GetR '/api/admin/members?pageNum=1&pageSize=1' $MP
Check 'MP token blocked on admin API' ($forbidden.code -ne 0) ("code=" + $forbidden.code + " msg=" + $forbidden.msg)

Write-Host ("RESULT: PASS={0} FAIL={1}" -f $script:pass, $script:fail) -ForegroundColor Yellow
if ($script:fail -gt 0) { exit 1 }
