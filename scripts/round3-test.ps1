# Round 3 smoke test: hold orders + member import/export
# Usage: powershell -ExecutionPolicy Bypass -File scripts\round3-test.ps1
# ASCII-only + BOM for Windows PowerShell 5.1 compatibility.
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
    $a = @{ Uri = "$base$path"; Method = $method; Headers = $h; TimeoutSec = 40 }
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
Check 'login ok' ($login.code -eq 0) ("perms=" + $login.data.userInfo.perms.Count)
Check 'member:import perm present' ($login.data.userInfo.perms -contains 'member:import') ('has member:import')
Check 'member:export perm present' ($login.data.userInfo.perms -contains 'member:export') ('has member:export')

Write-Host '=== 1. hold order lifecycle ===' -ForegroundColor Cyan
$holdBody = @{
    storeId = 1
    shiftNo = 'SH-TEST-01'
    remark = 'round3 hold test'
    items = @(
        @{ itemType = 4; productName = 'Hold Item A'; price = 38.00; qty = 2 },
        @{ itemType = 1; productId = 5; productName = 'Glass Water'; price = 29.90; qty = 1 }
    )
}
$hold = PostR '/api/admin/orders/hold' $holdBody $H ([guid]::NewGuid().ToString())
Check 'hold created' ($hold.code -eq 0) ("holdNo=" + $hold.data.holdNo + " items=" + $hold.data.itemCount + " total=" + $hold.data.totalAmount)
Check 'hold total correct' ($hold.data.totalAmount -eq 105.90) ("total=" + $hold.data.totalAmount)
$holdNo = $hold.data.holdNo

$holds = GetR '/api/admin/orders/hold/list?storeId=1' $H
Check 'hold appears in list' ($holds.code -eq 0 -and $holds.data.Count -ge 1) ("count=" + $holds.data.Count)
$found = $holds.data | Where-Object { $_.holdNo -eq $holdNo } | Select-Object -First 1
Check 'hold items restored in list' ($found -ne $null -and $found.items.Count -eq 2) ("items=" + $found.items.Count + " first=" + $found.items[0].productName)

Write-Host '=== 2. resume (take back) ===' -ForegroundColor Cyan
$resume = PostR "/api/admin/orders/hold/$holdNo/resume" $null $H $null
Check 'resume ok' ($resume.code -eq 0) ("holdNo=" + $resume.data.holdNo + " items=" + $resume.data.itemCount)
Check 'resume returns cart snapshot' ($resume.data.items.Count -eq 2 -and $resume.data.totalAmount -eq 105.90) ("items=" + $resume.data.items.Count + " total=" + $resume.data.totalAmount)

$resumeAgain = PostR "/api/admin/orders/hold/$holdNo/resume" $null $H $null
Check 'resume twice rejected' ($resumeAgain.code -eq 40005) ("code=" + $resumeAgain.code + " msg=" + $resumeAgain.msg)

$holdsAfter = GetR '/api/admin/orders/hold/list?storeId=1' $H
$stillThere = $holdsAfter.data | Where-Object { $_.holdNo -eq $holdNo }
Check 'resumed hold removed from list' ($stillThere -eq $null) ("remaining=" + $holdsAfter.data.Count)

Write-Host '=== 3. cancel hold ===' -ForegroundColor Cyan
$hold2 = PostR '/api/admin/orders/hold' @{ storeId = 1; items = @(@{ itemType = 4; productName = 'To Cancel'; price = 10; qty = 1 }) } $H ([guid]::NewGuid().ToString())
Check 'second hold created' ($hold2.code -eq 0) ("holdNo=" + $hold2.data.holdNo)
$cancel = Req 'DELETE' ("/api/admin/orders/hold/" + $hold2.data.holdNo + "?reason=test+cancel") $null $H $null
Check 'hold cancelled' ($cancel.code -eq 0) ("code=" + $cancel.code)
$holdsFinal = GetR '/api/admin/orders/hold/list?storeId=1' $H
Check 'cancelled hold removed' (($holdsFinal.data | Where-Object { $_.holdNo -eq $hold2.data.holdNo }) -eq $null) ("remaining=" + $holdsFinal.data.Count)

Write-Host '=== 4. hold does NOT touch assets ===' -ForegroundColor Cyan
$recBefore = GetR '/api/admin/accounts/reconcile' $H
Check 'reconcile clean after holds' ($recBefore.data.diffCount -eq 0) ($recBefore.data.conclusion)

Write-Host '=== 5. import template download ===' -ForegroundColor Cyan
$tplPath = Join-Path $env:TEMP 'member-template.xlsx'
try {
    Invoke-WebRequest -Uri "$base/api/admin/members/import/template" -Headers $H -OutFile $tplPath -TimeoutSec 30
    $tplSize = (Get-Item $tplPath).Length
    Check 'template downloaded' ($tplSize -gt 2000) ("bytes=" + $tplSize)
} catch { Check 'template downloaded' $false $_.Exception.Message }

Write-Host '=== 6. import valid file (3 rows) ===' -ForegroundColor Cyan
$validFile = 'logs\excel\member-import-valid.xlsx'
if (Test-Path $validFile) {
    $resp = & curl.exe -s -X POST "$base/api/admin/members/import" -H "Authorization: Bearer $($login.data.accessToken)" -F "file=@$validFile" 2>$null
    $imp = $resp | ConvertFrom-Json
    Check 'valid import success' ($imp.code -eq 0 -and $imp.data.successRows -eq 3) ("success=" + $imp.data.successRows + " fail=" + $imp.data.failRows + " status=" + $imp.data.statusText)
    $importedPhones = $imp.data.successRows
} else { Check 'valid import success' $false 'file missing' }

Write-Host '=== 7. import mixed file (2 ok + 3 fail) ===' -ForegroundColor Cyan
$mixedFile = 'logs\excel\member-import-mixed.xlsx'
if (Test-Path $mixedFile) {
    $resp2 = & curl.exe -s -X POST "$base/api/admin/members/import" -H "Authorization: Bearer $($login.data.accessToken)" -F "file=@$mixedFile" 2>$null
    $imp2 = $resp2 | ConvertFrom-Json
    Check 'mixed import partial' ($imp2.code -eq 0 -and $imp2.data.successRows -eq 2 -and $imp2.data.failRows -eq 3) ("success=" + $imp2.data.successRows + " fail=" + $imp2.data.failRows + " status=" + $imp2.data.statusText)
    Check 'error rows detailed' ($imp2.data.errors.Count -eq 3) ("errors=" + $imp2.data.errors.Count)
    if ($imp2.data.errors.Count -ge 1) {
        Check 'error has reason' ($imp2.data.errors[0].errorMsg.Length -gt 0) ("row=" + $imp2.data.errors[0].rowNum + " msg=" + $imp2.data.errors[0].errorMsg)
    }
    Check 'error file generated' ($imp2.data.errorFileUrl -ne $null) ("url=" + $imp2.data.errorFileUrl)

    $task = GetR ("/api/admin/members/import/" + $imp2.data.taskNo) $H
    Check 'import task queryable' ($task.code -eq 0) ("taskNo=" + $task.data.taskNo + " status=" + $task.data.status)

    if ($imp2.data.errorFileUrl) {
        try {
            Invoke-WebRequest -Uri ($base + $imp2.data.errorFileUrl) -OutFile (Join-Path $env:TEMP 'err.xlsx') -TimeoutSec 30
            Check 'error file downloadable' ((Get-Item (Join-Path $env:TEMP 'err.xlsx')).Length -gt 2000) ("bytes=" + (Get-Item (Join-Path $env:TEMP 'err.xlsx')).Length)
        } catch { Check 'error file downloadable' $false $_.Exception.Message }
    }
} else { Check 'mixed import partial' $false 'file missing' }

Write-Host '=== 8. imported members queryable ===' -ForegroundColor Cyan
# 统计所有导入来源（门店建档=1 与 批量导入=3），验证合法行确实入库
$list1 = GetR '/api/admin/members?pageNum=1&pageSize=50&source=1' $H
$list3 = GetR '/api/admin/members?pageNum=1&pageSize=50&source=3' $H
$importedTotal = $list1.data.total + $list3.data.total
Check 'imported members queryable' ($importedTotal -ge 5) ("source1=" + $list1.data.total + " source3=" + $list3.data.total)

Write-Host '=== 9. export members to xlsx ===' -ForegroundColor Cyan
# 统一使用脚本内相对路径，避免 $env:TEMP 在某些父进程下为空导致路径非法。
# 注意：curl 调用写成单行，避免反引号续行在 CRLF 文件里被当成转义字符而断裂。
$tmpDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'logs\tmp'
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$exportPath = Join-Path $tmpDir 'member-export.xlsx'
$bodyFile = Join-Path $tmpDir 'export-body.json'
[System.IO.File]::WriteAllText($bodyFile, '{"status":1}', [System.Text.Encoding]::UTF8)
$expArgs = @('-s', '-o', $exportPath, '-w', '%{http_code}', '-X', 'POST', "$base/api/admin/members/export", '-H', "Authorization: Bearer $($login.data.accessToken)", '-H', 'Content-Type: application/json', '-d', "@$bodyFile")
$expCode = & curl.exe @expArgs
$expSize = 0
if (Test-Path $exportPath) { $expSize = (Get-Item $exportPath).Length }
$isZip = $false
if ($expSize -gt 2) {
    $head = [System.IO.File]::ReadAllBytes($exportPath)[0..1]
    $isZip = ($head[0] -eq 80 -and $head[1] -eq 75)
}
Check 'export downloaded as xlsx' ($expCode -eq '200' -and $expSize -gt 3000 -and $isZip) ("http=" + $expCode + " bytes=" + $expSize + " isXlsx=" + $isZip)

Write-Host '=== 10. export endpoint stability (empty filter) ===' -ForegroundColor Cyan
$tmpOut = Join-Path $tmpDir 'member-export-2.xlsx'
$bodyFile2 = Join-Path $tmpDir 'export-body2.json'
[System.IO.File]::WriteAllText($bodyFile2, '{}', [System.Text.Encoding]::UTF8)
$limArgs = @('-s', '-o', $tmpOut, '-w', '%{http_code}', '-X', 'POST', "$base/api/admin/members/export", '-H', "Authorization: Bearer $($login.data.accessToken)", '-H', 'Content-Type: application/json', '-d', "@$bodyFile2")
$limCode = & curl.exe @limArgs
$limSize = 0
if (Test-Path $tmpOut) { $limSize = (Get-Item $tmpOut).Length }
Check 'export with empty filter returns 200' ($limCode -eq '200' -and $limSize -gt 3000) ("http=" + $limCode + " bytes=" + $limSize)

Write-Host '=== 11. ledger still consistent ===' -ForegroundColor Cyan
$recAfter = GetR '/api/admin/accounts/reconcile' $H
Check 'reconcile clean after import' ($recAfter.data.diffCount -eq 0) ($recAfter.data.conclusion)

Write-Host ("RESULT: PASS={0} FAIL={1}" -f $script:pass, $script:fail) -ForegroundColor Yellow
if ($script:fail -gt 0) { exit 1 }
