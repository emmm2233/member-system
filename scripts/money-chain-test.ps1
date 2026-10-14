# Recharge -> consume -> refund money-chain test (ASCII only for PS 5.1 compatibility)
# Usage: powershell -ExecutionPolicy Bypass -File scripts/money-chain-test.ps1
$ErrorActionPreference = 'Continue'
$base = 'http://127.0.0.1:8080'
$script:pass = 0
$script:fail = 0

function Check($name, $ok, $detail) {
    if ($ok) { Write-Host ("  [PASS] {0}  {1}" -f $name, $detail) -ForegroundColor Green; $script:pass++ }
    else { Write-Host ("  [FAIL] {0}  {1}" -f $name, $detail) -ForegroundColor Red; $script:fail++ }
}
function Post($path, $obj, $headers, $idem) {
    $h = @{}
    if ($headers) { $headers.GetEnumerator() | ForEach-Object { $h[$_.Key] = $_.Value } }
    $h['Content-Type'] = 'application/json'
    if ($idem) { $h['X-Idempotency-Key'] = $idem }
    $json = ($obj | ConvertTo-Json -Depth 8 -Compress)
    try {
        return Invoke-RestMethod -Uri "$base$path" -Method Post -Headers $h -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 20
    } catch {
        # HTTP 4xx/5xx 时解析错误响应体，保持与业务码一致的处理逻辑
        $resp = $_.Exception.Response
        if ($resp -ne $null) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $body = $reader.ReadToEnd()
            try { return ($body | ConvertFrom-Json) } catch { return [pscustomobject]@{ code = -1; msg = $body } }
        }
        return [pscustomobject]@{ code = -1; msg = $_.Exception.Message }
    }
}
function Get2($path, $headers) {
    try {
        return Invoke-RestMethod -Uri "$base$path" -Headers $headers -TimeoutSec 20
    } catch {
        $resp = $_.Exception.Response
        if ($resp -ne $null) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $body = $reader.ReadToEnd()
            try { return ($body | ConvertFrom-Json) } catch { return [pscustomobject]@{ code = -1; msg = $body } }
        }
        return [pscustomobject]@{ code = -1; msg = $_.Exception.Message }
    }
}

Write-Host '=== login ===' -ForegroundColor Cyan
$login = Post '/api/admin/auth/login' @{ username = 'admin'; password = 'Admin@123' } $null $null
if ($login.code -ne 0) { Write-Host 'login failed' -ForegroundColor Red; exit 1 }
$H = @{ Authorization = "Bearer " + $login.data.accessToken }

Write-Host '=== create member ===' -ForegroundColor Cyan
$phone = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$m = Post '/api/admin/members' @{ phone = $phone; realName = 'Chain Test'; nickname = 'chain'; storeId = 1; source = 1 } $H $null
Check 'member create' ($m.code -eq 0) ("id=" + $m.data.id)
$memberId = $m.data.id

Write-Host '=== recharge 500 + gift 50 + 200 points + coupon (plan 2) ===' -ForegroundColor Cyan
$rc = Post '/api/admin/recharge' @{ memberId = $memberId; planId = 2; rechargeAmount = 500; payMethod = 2 } $H ([guid]::NewGuid().ToString())
Check 'recharge ok' ($rc.code -eq 0) ("code=" + $rc.code + " msg=" + $rc.msg)
if ($rc.code -eq 0) {
    $acc = $rc.data.account
    Check 'balance=500' ($acc.balance -eq 500) ("balance=" + $acc.balance)
    Check 'gift=50' ($acc.giftBalance -eq 50) ("gift=" + $acc.giftBalance)
    Check 'gift points 200' ($rc.data.pointsAfter -eq 200) ("pointsAfter=" + $rc.data.pointsAfter + " giftPoints=" + $rc.data.giftPoints)
    Check 'gift coupon issued' ($rc.data.grantedCoupons.Count -ge 1) ("coupons=" + $rc.data.grantedCoupons.Count)
}
$rechargeOrderNo = $rc.data.orderNo

Write-Host '=== recharge idempotency (same key) ===' -ForegroundColor Cyan
$idem = [guid]::NewGuid().ToString()
$r1 = Post '/api/admin/recharge' @{ memberId = $memberId; rechargeAmount = 100; payMethod = 2 } $H $idem
$r2 = Post '/api/admin/recharge' @{ memberId = $memberId; rechargeAmount = 100; payMethod = 2 } $H $idem
Check 'first recharge ok' ($r1.code -eq 0) ("orderNo=" + $r1.data.orderNo)
Check 'duplicate rejected' ($r2.code -ne 0) ("code=" + $r2.code + " msg=" + $r2.msg)
$accAfter = (Get2 "/api/admin/accounts/$memberId" $H).data
Check 'balance 600 after one 100 recharge' ($accAfter.balance -eq 600) ("balance=" + $accAfter.balance)

Write-Host '=== consume: 100 yuan paid by balance (gift first) ===' -ForegroundColor Cyan
$order = @{
    memberId = $memberId; orderType = 1; storeId = 1; staffId = 1
    items = @(@{ itemType = 4; productName = 'Chain Test Item'; price = 100; qty = 1 })
    usePoints = 0
    payments = @(@{ payMethod = 1; amount = 100 })
}
$pv = Post '/api/admin/orders/preview' $order $H $null
Check 'preview ok' ($pv.code -eq 0) ("payable=" + $pv.data.payableAmount + " discount=" + $pv.data.discountAmount)
Check 'preview payable=100' ($pv.data.payableAmount -eq 100) ("payable=" + $pv.data.payableAmount)

$st = Post '/api/admin/orders/settle' $order $H ([guid]::NewGuid().ToString())
Check 'settle ok' ($st.code -eq 0) ("orderNo=" + $st.data.orderNo + " msg=" + $st.data.msg)
if ($st.code -eq 0) {
    $orderNo = $st.data.orderNo
    $a = $st.data.account
    Check 'gift consumed first (gift 0)' ($a.giftBalance -eq 0) ("gift=" + $a.giftBalance)
    Check 'principal 550' ($a.balance -eq 550) ("balance=" + $a.balance)
    Check 'points gained > 0' ($st.data.pointsGained -gt 0) ("pointsGained=" + $st.data.pointsGained)
}

Write-Host '=== insufficient balance rejected, no ledger change ===' -ForegroundColor Cyan
$big = @{
    memberId = $memberId; orderType = 1; storeId = 1
    items = @(@{ itemType = 4; productName = 'Too Expensive'; price = 99999; qty = 1 })
    payments = @(@{ payMethod = 1; amount = 99999 })
}
$bad = Post '/api/admin/orders/settle' $big $H ([guid]::NewGuid().ToString())
Check 'insufficient balance rejected' ($bad.code -eq 30001) ("code=" + $bad.code + " msg=" + $bad.msg)
$accNow = (Get2 "/api/admin/accounts/$memberId" $H).data
Check 'balance unchanged after failure' ($accNow.balance -eq 550) ("balance=" + $accNow.balance)

Write-Host '=== refund the 100 order (auto approve off -> pending) ===' -ForegroundColor Cyan
$rf = Post ("/api/admin/orders/$orderNo/refund?refundAmount=100&reason=chain+test+refund") @{} $H ([guid]::NewGuid().ToString())
Check 'refund applied' ($rf.code -eq 0) ("code=" + $rf.code + " status=" + $rf.data.status + " msg=" + $rf.data.msg)
if ($rf.code -eq 0) {
    $refundNo = $rf.data.refundNo
    $ap = Post ("/api/admin/orders/refunds/$refundNo/approve?pass=true&remark=ok") @{} $H $null
    Check 'refund approved' ($ap.code -eq 0) ("code=" + $ap.code + " msg=" + $ap.msg)
    $accR = (Get2 "/api/admin/accounts/$memberId" $H).data
    # 该单 100 元 = 赠送 50（赠送优先扣尽）+ 本金 50。退款按资金来源原路退回：
    #   赠送 50 -> 退回赠送；本金 50 -> 退回本金。
    # 因此本金 550+50=600、赠送 0+50=50（赠送余额本就只有 50，退款不会凭空增加）。
    Check 'principal restored to 600' ($accR.balance -eq 600) ("balance=" + $accR.balance)
    Check 'gift restored to 50' ($accR.giftBalance -eq 50) ("gift=" + $accR.giftBalance)
}

Write-Host '=== ledger reconciliation (account vs flows vs batches) ===' -ForegroundColor Cyan
$rec = Get2 '/api/admin/accounts/reconcile' $H
Check 'reconcile clean' ($rec.data.diffCount -eq 0) ($rec.data.conclusion + " balanceDiff=" + $rec.data.balanceDiffCount + " pointDiff=" + $rec.data.pointDiffCount)
$one = Get2 "/api/admin/accounts/$memberId/reconcile" $H
Check 'member balance matches flows' ($one.data.balanceMatch -eq $true) ("balance=" + $one.data.balance + " flowSum=" + $one.data.balanceFlowSum)
Check 'member gift matches flows' ($one.data.giftMatch -eq $true) ("gift=" + $one.data.giftBalance + " flowSum=" + $one.data.giftFlowSum)
Check 'member points match batches' ($one.data.pointsBatchMatch -eq $true) ("points=" + $one.data.points + " batchSum=" + $one.data.pointsBatchSum)
Check 'member growth matches flows' ($one.data.growthMatch -eq $true) ("growth=" + $one.data.growth + " flowSum=" + $one.data.growthFlowSum)

Write-Host '=== coupon lifecycle ===' -ForegroundColor Cyan
$myCoupons = Get2 "/api/admin/accounts/$memberId/point-flows?pageNum=1&pageSize=5" $H
Check 'point flows recorded' ($myCoupons.data.total -ge 2) ("flows=" + $myCoupons.data.total)

Write-Host '=== payment reserved interfaces ===' -ForegroundColor Cyan
$pc = Get2 '/api/admin/payments/channels' $H
Check 'pay channels listed' ($pc.code -eq 0 -and $pc.data.channels.Count -ge 1) ("channels=" + ($pc.data.channels | ForEach-Object { $_.channel + ':' + $_.ready }) -join ',')
$pr = Get2 '/api/admin/payments/reconcile' $H
Check 'payment reconcile' ($pr.code -eq 0) ($pr.data.conclusion)

Write-Host ("RESULT: PASS={0} FAIL={1}" -f $script:pass, $script:fail) -ForegroundColor Yellow
if ($script:fail -gt 0) { exit 1 }
