# Round 6 smoke test: staff as a first-class entity (staff vs login account split) + store management
# Usage: powershell -ExecutionPolicy Bypass -File scripts\round6-test.ps1
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
function PutR($p, $b, $h) { return Req 'PUT' $p $b $h $null }
function DelR($p, $h) { return Req 'DELETE' $p $null $h $null }

$rnd = Get-Random -Minimum 100000 -Maximum 999999
$today = (Get-Date).ToString('yyyy-MM-dd')

Write-Host '=== login + permissions ===' -ForegroundColor Cyan
$login = PostR '/api/admin/auth/login' @{ username = 'admin'; password = 'Admin@123' } $null $null
if ($login.code -ne 0) { Write-Host 'login failed' -ForegroundColor Red; exit 1 }
$H = @{ Authorization = 'Bearer ' + $login.data.accessToken }
Check 'admin login' ($login.code -eq 0) ('perms=' + $login.data.userInfo.perms.Count)
$need = @('staff:list','staff:add','staff:edit','staff:leave','staff:account','staff:perf','store:list','store:edit')
$missing = @($need | Where-Object { $login.data.userInfo.perms -notcontains $_ })
Check 'staff/store perms present' ($missing.Count -eq 0) ('missing=' + ($missing -join ','))

Write-Host '=== 1. store CRUD ===' -ForegroundColor Cyan
$stores0 = GetR '/api/admin/stores' $H
Check 'store list ok' ($stores0.code -eq 0 -and $stores0.data.Count -ge 1) ('count=' + $stores0.data.Count)
$seedStore = $stores0.data | Select-Object -First 1
Check 'seed store has code/name' ($seedStore.storeCode -ne $null -and $seedStore.storeName -ne $null) ($seedStore.storeCode + ' ' + $seedStore.storeName)

$newStore = PostR '/api/admin/stores' @{
    storeName = ('R6 Test Store ' + $rnd); city = 'Testville'; address = 'No.1 Test Road'
    contactName = 'Store Contact'; contactPhone = '13800001111'; businessHours = '09:00-22:00'; status = 1; sortNo = 99
} $H $null
Check 'store created' ($newStore.code -eq 0) ('id=' + $newStore.data.id + ' code=' + $newStore.data.storeCode)
$storeId = $newStore.data.id
Check 'store code auto-generated' ($newStore.data.storeCode -match '^S\d+$') ('code=' + $newStore.data.storeCode)
Check 'new store statusText' ($newStore.data.statusText -eq '营业中') ('status=' + $newStore.data.status)
Check 'new store staffCount 0' ($newStore.data.staffCount -eq 0) ('staffCount=' + $newStore.data.staffCount)

$dupCode = PostR '/api/admin/stores' @{ storeName = 'Dup Code Store'; storeCode = $newStore.data.storeCode } $H $null
Check 'duplicate store code rejected' ($dupCode.code -ne 0) ('msg=' + $dupCode.msg)

$updStore = PutR ("/api/admin/stores/" + $storeId) @{ storeName = ('R6 Renamed ' + $rnd); city = 'Testville'; status = 1; sortNo = 98 } $H
Check 'store updated' ($updStore.code -eq 0 -and $updStore.data.storeName -like 'R6 Renamed*') ('name=' + $updStore.data.storeName)

Write-Host '=== 2. staff CRUD + auto staff no ===' -ForegroundColor Cyan
$staff1 = PostR '/api/admin/staff' @{
    staffName = ('R6 Cashier ' + $rnd); phone = ('137' + (Get-Random -Minimum 10000000 -Maximum 99999999))
    storeId = $storeId; position = 'Cashier'; entryDate = $today
} $H $null
Check 'staff created' ($staff1.code -eq 0) ('id=' + $staff1.data.id + ' no=' + $staff1.data.staffNo)
$staffId = $staff1.data.id
Check 'staff no auto-generated E-style' ($staff1.data.staffNo -match '^E\d+$') ('no=' + $staff1.data.staffNo)
Check 'staff status active' ($staff1.data.status -eq 1 -and $staff1.data.statusText -eq '在职') ('status=' + $staff1.data.statusText)
Check 'staff store name resolved' ($staff1.data.storeName -like 'R6 Renamed*') ('storeName=' + $staff1.data.storeName)
Check 'staff entry date kept' ($staff1.data.entryDate -eq $today) ('entryDate=' + $staff1.data.entryDate)

$staff2 = PostR '/api/admin/staff' @{
    staffName = ('R6 Clerk ' + $rnd); storeId = $storeId; position = 'Sales'
} $H $null
Check 'second staff created' ($staff2.code -eq 0) ('no=' + $staff2.data.staffNo)
Check 'staff no increments' ($staff2.data.staffNo -ne $staff1.data.staffNo) ($staff1.data.staffNo + ' -> ' + $staff2.data.staffNo)

$dupStaffNo = PostR '/api/admin/staff' @{ staffName = 'Dup'; staffNo = $staff1.data.staffNo } $H $null
Check 'duplicate staff no rejected' ($dupStaffNo.code -ne 0) ('msg=' + $dupStaffNo.msg)

$badPhone = PostR '/api/admin/staff' @{ staffName = 'Bad Phone'; phone = '12345' } $H $null
Check 'invalid phone rejected' ($badPhone.code -ne 0) ('msg=' + $badPhone.msg)

$noName = PostR '/api/admin/staff' @{ staffName = '' } $H $null
Check 'empty name rejected' ($noName.code -ne 0) ('msg=' + $noName.msg)

$listS = GetR ("/api/admin/staff?keyword=" + $rnd + "&pageSize=50") $H
Check 'staff searchable by keyword' ($listS.code -eq 0 -and $listS.data.total -eq 2) ('total=' + $listS.data.total)

$byStore = GetR ("/api/admin/staff?storeId=" + $storeId + "&status=1") $H
Check 'staff filter by store+status' ($byStore.data.total -eq 2) ('total=' + $byStore.data.total)

$opts = GetR ("/api/admin/staff/options?storeId=" + $storeId) $H
Check 'staff options for cashier' ($opts.code -eq 0 -and $opts.data.Count -eq 2) ('count=' + $opts.data.Count)

$pos = GetR '/api/admin/staff/positions' $H
Check 'positions include used ones' ($pos.data -contains 'Cashier' -and $pos.data -contains 'Sales') ('positions=' + ($pos.data -join ','))

Write-Host '=== 3. link login account ===' -ForegroundColor Cyan
$uname = 'r6staff' + $rnd
$newUser = PostR '/api/admin/system/users' @{
    username = $uname; password = 'Staff@1234'; realName = 'R6 Staff Account'; storeId = $storeId
    dataScope = 2; status = 1; roleIds = @(4)
} $H $null
Check 'login account created' ($newUser.code -eq 0) ('userId=' + $newUser.data)
$userId = $newUser.data

$cand0 = GetR '/api/admin/staff/account-candidates' $H
$hasCand = @($cand0.data | Where-Object { $_.id -eq $userId }).Count -eq 1
Check 'new account is a candidate' $hasCand ('candidates=' + $cand0.data.Count)

$bind = PostR ("/api/admin/staff/" + $staffId + '/account') @{ userId = $userId } $H $null
Check 'bind account ok' ($bind.code -eq 0) ('username=' + $bind.data.username)

$afterBind = GetR ("/api/admin/staff/" + $staffId) $H
Check 'staff detail shows account' ($afterBind.data.account -ne $null -and $afterBind.data.account.username -eq $uname) ('account=' + $afterBind.data.account.username)
Check 'no account warning when consistent' ($afterBind.data.accountWarning -eq $null) ('warning=' + $afterBind.data.accountWarning)

$cand1 = GetR '/api/admin/staff/account-candidates' $H
$stillCand = @($cand1.data | Where-Object { $_.id -eq $userId }).Count
Check 'bound account removed from candidates' ($stillCand -eq 0) ('candidates=' + $cand1.data.Count)

$bindOther = PostR ("/api/admin/staff/" + $staff2.data.id + '/account') @{ userId = $userId } $H $null
Check 'account cannot bind two staff' ($bindOther.code -ne 0) ('msg=' + $bindOther.msg)

$bindNoUser = PostR ("/api/admin/staff/" + $staffId + '/account') @{ userId = 99999999 } $H $null
Check 'unknown account rejected' ($bindNoUser.code -ne 0) ('msg=' + $bindNoUser.msg)

Write-Host '=== 4. performance attributed to staff, not account ===' -ForegroundColor Cyan
$phone = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$m = PostR '/api/admin/members' @{ phone = $phone; realName = 'R6 Perf Member'; nickname = 'r6perf'; storeId = $storeId; source = 1 } $H $null
Check 'test member created' ($m.code -eq 0) ('memberId=' + $m.data.id)
$memberId = $m.data.id

# settle WITHOUT staffId: server must fall back to the account's bound staff.
# Use a dedicated cashier session so the fallback has something to bind to.
$cashLogin = PostR '/api/admin/auth/login' @{ username = $uname; password = 'Staff@1234' } $null $null
Check 'staff account can login' ($cashLogin.code -eq 0) ('perms=' + $cashLogin.data.userInfo.perms.Count)
$CH = @{ Authorization = 'Bearer ' + $cashLogin.data.accessToken }

$me = GetR '/api/admin/staff/me' $CH
Check 'account resolves its staff' ($me.code -eq 0 -and $me.data.id -eq $staffId) ('staff=' + $me.data.staffName + ' no=' + $me.data.staffNo)

$orderNoStaff = @{
    memberId = $memberId; orderType = 1; storeId = $storeId
    items = @(@{ itemType = 4; productName = 'R6 Perf Item'; price = 200; qty = 1 })
    usePoints = 0
    payments = @(@{ payMethod = 2; amount = 200 })
}
$st1 = PostR '/api/admin/orders/settle' $orderNoStaff $CH ([guid]::NewGuid().ToString())
Check 'settle without staffId ok' ($st1.code -eq 0) ('orderNo=' + $st1.data.orderNo)
$orderNo = $st1.data.orderNo

$perf = GetR ("/api/admin/staff/performance?startDate=" + $today + "&endDate=" + $today) $H
$row = $perf.data | Where-Object { $_.staffId -eq $staffId } | Select-Object -First 1
Check 'performance attributed via account fallback' ($row -ne $null) ('rows=' + $perf.data.Count)
Check 'performance amount 200' ($row -ne $null -and [decimal]$row.amount -eq 200) ('amount=' + $row.amount)
Check 'performance order count 1' ($row.orderCount -eq 1) ('count=' + $row.orderCount)
Check 'performance avg = amount' ($row -ne $null -and [decimal]$row.avgOrderAmount -eq 200) ('avg=' + $row.avgOrderAmount)
Check 'performance row carries staff no' ($row.staffNo -eq $staff1.data.staffNo) ('no=' + $row.staffNo)

# explicit staffId wins over the fallback (stand-in shift scenario)
$st2 = PostR '/api/admin/orders/settle' @{
    memberId = $memberId; orderType = 1; storeId = $storeId; staffId = $staff2.data.id
    items = @(@{ itemType = 4; productName = 'R6 Standin Item'; price = 50; qty = 1 })
    usePoints = 0
    payments = @(@{ payMethod = 2; amount = 50 })
} $CH ([guid]::NewGuid().ToString())
Check 'settle with explicit staffId ok' ($st2.code -eq 0) ('orderNo=' + $st2.data.orderNo)

$perf2 = GetR ("/api/admin/staff/performance?startDate=" + $today + "&endDate=" + $today) $H
$row2 = $perf2.data | Where-Object { $_.staffId -eq $staff2.data.id } | Select-Object -First 1
Check 'explicit staff gets the 2nd order' ($row2 -ne $null -and [decimal]$row2.amount -eq 50) ('amount=' + $row2.amount)
$row1b = $perf2.data | Where-Object { $_.staffId -eq $staffId } | Select-Object -First 1
Check 'account fallback staff unchanged' ($row1b -ne $null -and [decimal]$row1b.amount -eq 200) ('amount=' + $row1b.amount)

$sum = GetR ("/api/admin/staff/performance/summary?startDate=" + $today + "&endDate=" + $today) $H
Check 'performance summary ok' ($sum.code -eq 0) ('staff=' + $sum.data.activeStaff + ' amount=' + $sum.data.amount + ' onDuty=' + $sum.data.onDutyStaff)
Check 'summary sees >= 2 staff with perf' ([int]$sum.data.activeStaff -ge 2) ('activeStaff=' + $sum.data.activeStaff)

$d1 = GetR ("/api/admin/staff/" + $staffId + '/performance?startDate=' + $today + '&endDate=' + $today) $H
Check 'staff detail performance ok' ($d1.code -eq 0 -and [decimal]$d1.data.amount -eq 200) ('amount=' + $d1.data.amount)
Check 'detail has daily trend' ($d1.data.dailyTrend.Count -ge 1) ('trend=' + $d1.data.dailyTrend.Count)
Check 'detail has pay methods' ($d1.data.payMethods.Count -ge 1) ('methods=' + $d1.data.payMethods.Count)
Check 'detail has recent orders' ($d1.data.recentOrders.Count -ge 1) ('recent=' + $d1.data.recentOrders.Count)

$badRange = GetR ("/api/admin/staff/performance?startDate=2026-12-31&endDate=2026-01-01") $H
Check 'invalid date range rejected' ($badRange.code -ne 0) ('msg=' + $badRange.msg)

$rank = GetR '/api/admin/report/report/staff-ranking?limit=10' $H
Check 'dashboard staff ranking callable' ($rank.code -eq 0) ('rows=' + $rank.data.Count)
$rankRow = $rank.data | Where-Object { $_.staff_id -eq $staffId } | Select-Object -First 1
Check 'dashboard ranking now by staff_id' ($rankRow -ne $null -and $rankRow.staff_no -eq $staff1.data.staffNo) ('staff_no=' + $rankRow.staff_no)

Write-Host '=== 5. leave / reinstate keeps account untouched ===' -ForegroundColor Cyan
$leave = PostR ("/api/admin/staff/" + $staffId + '/leave') @{ reason = 'round6 test' } $H $null
Check 'leave ok' ($leave.code -eq 0) ('leaveDate=' + $leave.data.leaveDate)
Check 'leave reports account still active' ($leave.data.accountStillActive -eq $true) ('tip=' + $leave.data.tip)
Check 'leave keeps order count visible' ($leave.data.orderCount -ge 1) ('orders=' + $leave.data.orderCount)

$afterLeave = GetR ("/api/admin/staff/" + $staffId) $H
Check 'staff now resigned' ($afterLeave.data.status -eq 0 -and $afterLeave.data.statusText -eq '离职') ('status=' + $afterLeave.data.statusText)
Check 'leave date recorded' ($afterLeave.data.leaveDate -ne $null) ('leaveDate=' + $afterLeave.data.leaveDate)
Check 'account untouched by leave' ($afterLeave.data.account.status -eq 1) ('accountStatus=' + $afterLeave.data.account.statusText)
Check 'warning: resigned but account active' ($afterLeave.data.accountWarning -like '*仍可登录*') ('warning=' + $afterLeave.data.accountWarning)

# the login account still works after the staff resigned (that is the whole point)
$cashLogin2 = PostR '/api/admin/auth/login' @{ username = $uname; password = 'Staff@1234' } $null $null
Check 'login account unaffected by staff leave' ($cashLogin2.code -eq 0) ('code=' + $cashLogin2.code)

# performance of a resigned staff is still queryable
$perf3 = GetR ("/api/admin/staff/performance?startDate=" + $today + "&endDate=" + $today) $H
$rowResigned = $perf3.data | Where-Object { $_.staffId -eq $staffId } | Select-Object -First 1
Check 'resigned staff performance still visible' ($rowResigned -ne $null -and [decimal]$rowResigned.amount -eq 200) ('amount=' + $rowResigned.amount)
Check 'resigned flag in ranking' ($rowResigned.staffStatusText -eq '离职') ('flag=' + $rowResigned.staffStatusText)

# staffId fallback must ignore a resigned staff
$st3 = PostR '/api/admin/orders/settle' @{
    memberId = $memberId; orderType = 1; storeId = $storeId
    items = @(@{ itemType = 4; productName = 'R6 After Leave'; price = 30; qty = 1 })
    usePoints = 0
    payments = @(@{ payMethod = 2; amount = 30 })
} $CH ([guid]::NewGuid().ToString())
Check 'settle after staff resigned ok' ($st3.code -eq 0) ('orderNo=' + $st3.data.orderNo)
$perf4 = GetR ("/api/admin/staff/performance?startDate=" + $today + "&endDate=" + $today) $H
$rowResigned2 = $perf4.data | Where-Object { $_.staffId -eq $staffId } | Select-Object -First 1
Check 'resigned staff not used as fallback' ([decimal]$rowResigned2.amount -eq 200) ('amount=' + $rowResigned2.amount)

$leaveAgain = PostR ("/api/admin/staff/" + $staffId + '/leave') @{} $H $null
Check 'double leave rejected' ($leaveAgain.code -ne 0) ('msg=' + $leaveAgain.msg)

$reinstate = PostR ("/api/admin/staff/" + $staffId + '/reinstate') $null $H $null
Check 'reinstate ok' ($reinstate.code -eq 0) ('tip=' + $reinstate.data.tip)
$afterReinstate = GetR ("/api/admin/staff/" + $staffId) $H
Check 'staff active again' ($afterReinstate.data.status -eq 1) ('status=' + $afterReinstate.data.statusText)
Check 'leave date cleared' ($afterReinstate.data.leaveDate -eq $null) ('leaveDate=' + $afterReinstate.data.leaveDate)

$reinstateAgain = PostR ("/api/admin/staff/" + $staffId + '/reinstate') $null $H $null
Check 'double reinstate rejected' ($reinstateAgain.code -ne 0) ('msg=' + $reinstateAgain.msg)

# after reinstate the fallback works again
$st4 = PostR '/api/admin/orders/settle' @{
    memberId = $memberId; orderType = 1; storeId = $storeId
    items = @(@{ itemType = 4; productName = 'R6 After Reinstate'; price = 70; qty = 1 })
    usePoints = 0
    payments = @(@{ payMethod = 2; amount = 70 })
} $CH ([guid]::NewGuid().ToString())
Check 'settle after reinstate ok' ($st4.code -eq 0) ('orderNo=' + $st4.data.orderNo)
$perf5 = GetR ("/api/admin/staff/performance?startDate=" + $today + "&endDate=" + $today) $H
$rowBack = $perf5.data | Where-Object { $_.staffId -eq $staffId } | Select-Object -First 1
Check 'fallback works again after reinstate' ([decimal]$rowBack.amount -eq 270) ('amount=' + $rowBack.amount)

Write-Host '=== 6. account status does not touch staff ===' -ForegroundColor Cyan
$dis = PutR ("/api/admin/system/users/" + $userId + '/status?status=0') $null $H
Check 'account disabled' ($dis.code -eq 0) ('userId=' + $userId)
$afterDisable = GetR ("/api/admin/staff/" + $staffId) $H
Check 'staff still active after account disabled' ($afterDisable.data.status -eq 1) ('staffStatus=' + $afterDisable.data.statusText)
Check 'warning: active staff with disabled account' ($afterDisable.data.accountWarning -like '*无法登录*') ('warning=' + $afterDisable.data.accountWarning)

$loginDisabled = PostR '/api/admin/auth/login' @{ username = $uname; password = 'Staff@1234' } $null $null
Check 'disabled account cannot login' ($loginDisabled.code -ne 0) ('msg=' + $loginDisabled.msg)

PutR ("/api/admin/system/users/" + $userId + '/status?status=1') $null $H | Out-Null
$loginBack = PostR '/api/admin/auth/login' @{ username = $uname; password = 'Staff@1234' } $null $null
Check 'account enabled again' ($loginBack.code -eq 0) ('code=' + $loginBack.code)

Write-Host '=== 7. unbind + delete guards ===' -ForegroundColor Cyan
$unbind = DelR ("/api/admin/staff/" + $staffId + '/account') $H
Check 'unbind account ok' ($unbind.code -eq 0) ('code=' + $unbind.code)
$afterUnbind = GetR ("/api/admin/staff/" + $staffId) $H
Check 'staff has no account after unbind' ($afterUnbind.data.account -eq $null) ('account=' + $afterUnbind.data.account)
Check 'account row still exists after unbind' ((GetR ("/api/admin/system/users?keyword=" + $uname) $H).data.total -ge 1) ('user preserved')
$unbindAgain = DelR ("/api/admin/staff/" + $staffId + '/account') $H
Check 'double unbind rejected' ($unbindAgain.code -ne 0) ('msg=' + $unbindAgain.msg)

$delWithPerf = DelR ("/api/admin/staff/" + $staffId) $H
Check 'staff with performance cannot be deleted' ($delWithPerf.code -ne 0) ('msg=' + $delWithPerf.msg)

$freshStaff = PostR '/api/admin/staff' @{ staffName = ('R6 Temp ' + $rnd); storeId = $storeId } $H $null
Check 'temp staff created' ($freshStaff.code -eq 0) ('id=' + $freshStaff.data.id)
$delFresh = DelR ("/api/admin/staff/" + $freshStaff.data.id) $H
Check 'staff without performance deleted' ($delFresh.code -eq 0) ('code=' + $delFresh.code)
$gone = GetR ("/api/admin/staff/" + $freshStaff.data.id) $H
Check 'deleted staff no longer visible' ($gone.code -ne 0) ('msg=' + $gone.msg)

Write-Host '=== 8. store delete guard ===' -ForegroundColor Cyan
$storeList = GetR '/api/admin/stores' $H
$busyStore = $storeList.data | Where-Object { $_.id -eq $storeId } | Select-Object -First 1
Check 'referenced store marked not deletable' ($busyStore.deletable -eq $false) ('reason=' + $busyStore.deleteBlockReason)
Check 'store view exposes reference counts' ($busyStore.orderCount -ge 1 -and $busyStore.memberCount -ge 1) ('orders=' + $busyStore.orderCount + ' members=' + $busyStore.memberCount)

$delStoreBusy = DelR ("/api/admin/stores/" + $storeId) $H
Check 'referenced store cannot be deleted' ($delStoreBusy.code -ne 0) ('msg=' + $delStoreBusy.msg)

$st = PutR ("/api/admin/stores/" + $storeId + '/status?status=0') $null $H
Check 'store can be closed' ($st.code -eq 0) ('code=' + $st.code)
$closed = GetR ("/api/admin/stores/" + $storeId) $H
Check 'store statusText closed' ($closed.data.statusText -eq '已停业') ('status=' + $closed.data.statusText)

# closing is the right lever: history stays intact, store just leaves the selectable lists
$closedList = GetR '/api/admin/stores?status=0' $H
Check 'closed store listed under status=0' (@($closedList.data | Where-Object { $_.id -eq $storeId }).Count -eq 1) ('count=' + $closedList.data.Count)

$badStatus = PutR ("/api/admin/stores/" + $storeId + '/status?status=9') $null $H
Check 'invalid store status rejected' ($badStatus.code -ne 0) ('msg=' + $badStatus.msg)

# a brand-new store that nothing references is still deletable (typo-created stores must be removable)
$emptyStore = PostR '/api/admin/stores' @{ storeName = ('R6 Empty ' + $rnd); status = 1; sortNo = 97 } $H $null
Check 'empty store created' ($emptyStore.code -eq 0) ('id=' + $emptyStore.data.id)
Check 'empty store marked deletable' ($emptyStore.data.deletable -eq $true) ('deletable=' + $emptyStore.data.deletable)
$delEmpty = DelR ("/api/admin/stores/" + $emptyStore.data.id) $H
Check 'unreferenced store deleted' ($delEmpty.code -eq 0) ('code=' + $delEmpty.code)
$emptyGone = GetR ("/api/admin/stores/" + $emptyStore.data.id) $H
Check 'deleted store no longer visible' ($emptyGone.code -ne 0) ('msg=' + $emptyGone.msg)

# moving every staff out still leaves history references -> still not deletable
PutR ("/api/admin/staff/" + $staffId) @{ staffName = $afterUnbind.data.staffName; storeId = $seedStore.id; position = 'Cashier' } $H | Out-Null
PostR ("/api/admin/staff/" + $staff2.data.id + '/leave') @{} $H $null | Out-Null
$afterMove = GetR ("/api/admin/stores/" + $storeId) $H
Check 'store keeps active staff count after move' ($afterMove.data.staffCount -eq 0) ('staffCount=' + $afterMove.data.staffCount)
Check 'store still blocked by history refs' ($afterMove.data.deletable -eq $false -and $afterMove.data.orderCount -ge 1) ('orders=' + $afterMove.data.orderCount + ' historyStaff=' + $afterMove.data.historyStaffCount)
$delAfterMove = DelR ("/api/admin/stores/" + $storeId) $H
Check 'store with history still undeletable' ($delAfterMove.code -ne 0) ('msg=' + $delAfterMove.msg)

Write-Host '=== 9. access control ===' -ForegroundColor Cyan
$noPermLogin = PostR '/api/admin/auth/login' @{ username = $uname; password = 'Staff@1234' } $null $null
$NH = @{ Authorization = 'Bearer ' + $noPermLogin.data.accessToken }
$cashierStaffList = GetR '/api/admin/staff' $NH
Check 'cashier cannot list staff' ($cashierStaffList.code -ne 0) ('code=' + $cashierStaffList.code)
$cashierStoreEdit = PutR ("/api/admin/stores/" + $seedStore.id + '/status?status=1') $null $NH
Check 'cashier cannot edit store' ($cashierStoreEdit.code -ne 0) ('code=' + $cashierStoreEdit.code)
$anon = GetR '/api/admin/staff' $null
Check 'anonymous blocked' ($anon.code -ne 0) ('code=' + $anon.code)

Write-Host ''
Write-Host ("RESULT: PASS={0} FAIL={1}" -f $script:pass, $script:fail) -ForegroundColor Yellow
if ($script:fail -gt 0) { exit 1 }
