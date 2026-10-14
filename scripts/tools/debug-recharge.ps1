# Debug: isolate the recharge duplicate-key issue by calling once with an explicit key
# Usage: powershell -ExecutionPolicy Bypass -File scripts/tools/debug-recharge.ps1
$base = 'http://127.0.0.1:8080'
$key = [guid]::NewGuid().ToString()
$h = @{ 'Content-Type' = 'application/json'; 'X-Idempotency-Key' = $key }

$login = Invoke-RestMethod -Uri "$base/api/admin/auth/login" -Method Post -ContentType 'application/json' `
    -Body '{"username":"admin","password":"Admin@123"}' -TimeoutSec 15
$H = @{ Authorization = "Bearer " + $login.data.accessToken; 'Content-Type' = 'application/json'; 'X-Idempotency-Key' = $key }
Write-Host ("idempotency key = " + $key)

# find an existing member
$list = Invoke-RestMethod -Uri "$base/api/admin/members?pageNum=1&pageSize=1" -Headers $H -TimeoutSec 10
$memberId = $list.data.list[0].id
Write-Host ("memberId = " + $memberId)

$body = @{ memberId = $memberId; rechargeAmount = 500; payMethod = 2 } | ConvertTo-Json -Compress
Write-Host ("request = " + $body)
$r = Invoke-RestMethod -Uri "$base/api/admin/recharge" -Method Post -Headers $H `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 20
Write-Host ("response code=" + $r.code + " msg=" + $r.msg)
if ($r.code -eq 0) {
    Write-Host ("orderNo=" + $r.data.orderNo + " balance=" + $r.data.account.balance + " gift=" + $r.data.account.giftBalance)
}
