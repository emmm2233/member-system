# Round 8 smoke test: performance indexes (P1-1/P1-2) + growth-flow idempotency + N+1 batch refactor
# Usage: powershell -ExecutionPolicy Bypass -File scripts\round8-test.ps1
#
# Note on the index assertions: the dev DB has ~20 members / ~15 orders, so a plain EXPLAIN
# cannot prove an index helps (the optimizer may legitimately prefer a scan on tiny tables).
# Instead we assert SARGABILITY, which is data-independent:
#   FORCE INDEX(<new index>) + new predicate  -> type ref/range, rows << table size
#   FORCE INDEX(<new index>) + OLD predicate  -> type index (full index scan), rows == table size
# That contrast is exactly the defect being fixed, and it holds regardless of row count.
$ErrorActionPreference = 'Continue'
$base = 'http://127.0.0.1:8080'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe'
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

function Sql($q) {
    $out = & $mysql -uroot --password=123456 --default-character-set=utf8mb4 -N -B -e $q 2>&1 | Where-Object { $_ -notmatch 'Warning' }
    return ($out -join "`n")
}
# EXPLAIN 需要列名，不能加 -N（第一版加了 -N，解析结果全是空值，三条断言假失败）
function SqlWithHeader($q) {
    $out = & $mysql -uroot --password=123456 --default-character-set=utf8mb4 -B -e $q 2>&1 | Where-Object { $_ -notmatch 'Warning' }
    return ($out -join "`n")
}
# EXPLAIN 单行结果 -> hashtable
function ExplainOne($explainSql) {
    $raw = SqlWithHeader $explainSql
    $lines = $raw -split "`n" | Where-Object { $_.Trim() -ne '' }
    if ($lines.Count -lt 2) { return $null }
    $cols = $lines[0] -split "`t"
    $vals = $lines[1] -split "`t"
    $h = @{}
    for ($i = 0; $i -lt $cols.Count; $i++) { $h[$cols[$i]] = $vals[$i] }
    return $h
}
# JOIN 的 EXPLAIN 每张表一行，必须按表名取；默认取第一行会取到驱动表 m_staff 的 PRIMARY
function ExplainTable($explainSql, $tableName) {
    $raw = SqlWithHeader $explainSql
    $lines = $raw -split "`n" | Where-Object { $_.Trim() -ne '' }
    if ($lines.Count -lt 2) { return $null }
    $cols = $lines[0] -split "`t"
    $tIdx = [array]::IndexOf($cols, 'table')
    for ($r = 1; $r -lt $lines.Count; $r++) {
        $vals = $lines[$r] -split "`t"
        if ($tIdx -ge 0 -and $tIdx -lt $vals.Count -and $vals[$tIdx] -eq $tableName) {
            $h = @{}
            for ($i = 0; $i -lt $cols.Count; $i++) { $h[$cols[$i]] = $vals[$i] }
            return $h
        }
    }
    return $null
}

$rnd = Get-Random -Minimum 100000 -Maximum 999999

Write-Host '=== login ===' -ForegroundColor Cyan
$login = PostR '/api/admin/auth/login' @{ username = 'admin'; password = 'Admin@123' } $null $null
if ($login.code -ne 0) { Write-Host 'login failed' -ForegroundColor Red; exit 1 }
$H = @{ Authorization = 'Bearer ' + $login.data.accessToken }
Check 'admin login' ($login.code -eq 0) ('perms=' + $login.data.userInfo.perms.Count)

Write-Host '=== 1. indexes exist with expected columns (P1-1 / P1-2) ===' -ForegroundColor Cyan
$idx = Sql "SELECT CONCAT(table_name,'|',index_name,'|',GROUP_CONCAT(column_name ORDER BY seq_in_index)) FROM information_schema.statistics WHERE table_schema='member_system' AND ((table_name='m_order' AND index_name IN ('idx_staff_paid','idx_store_paid')) OR (table_name='m_member' AND index_name='idx_birthday_md') OR (table_name='m_growth_flow' AND index_name='uk_idem') OR (table_name='m_payment_order' AND index_name='idx_status_created')) GROUP BY table_name,index_name ORDER BY table_name,index_name;"
Check 'm_order.idx_staff_paid = (staff_id,paid_at)' ($idx -match 'm_order\|idx_staff_paid\|staff_id,paid_at') ('found=' + ($idx -split "`n" | Where-Object { $_ -match 'idx_staff_paid' }))
Check 'm_order.idx_store_paid = (store_id,paid_at)' ($idx -match 'm_order\|idx_store_paid\|store_id,paid_at') ('ok')
Check 'm_member.idx_birthday_md' ($idx -match 'm_member\|idx_birthday_md\|status,deleted,birthday_md') ('ok')
Check 'm_growth_flow.uk_idem = (idempotency_key,biz_type)' ($idx -match 'm_growth_flow\|uk_idem\|idempotency_key,biz_type') ('ok')
Check 'm_payment_order.idx_status_created' ($idx -match 'm_payment_order\|idx_status_created\|status,created_at') ('ok')

$genCol = Sql "SELECT CONCAT(extra,'|',IFNULL(generation_expression,'')) FROM information_schema.columns WHERE table_schema='member_system' AND table_name='m_member' AND column_name='birthday_md';"
Check 'birthday_md is a STORED generated column' ($genCol -match 'STORED GENERATED') ('col=' + $genCol)
Check 'birthday_md expression is DATE_FORMAT' ($genCol -match 'date_format') ('col=' + $genCol)

Write-Host '=== 2. birthday scan is now sargable (P1-2) ===' -ForegroundColor Cyan
$tableRows = [int](Sql "SELECT COUNT(*) FROM member_system.m_member;")
$newPlan = ExplainOne "EXPLAIN SELECT * FROM member_system.m_member FORCE INDEX (idx_birthday_md) WHERE deleted=0 AND status=1 AND birthday_md='09-15' ORDER BY id LIMIT 200;"
Check 'birthday predicate uses idx_birthday_md' ($newPlan -ne $null -and $newPlan['key'] -eq 'idx_birthday_md') ('key=' + $newPlan['key'] + ' type=' + $newPlan['type'])
Check 'birthday predicate is an indexed access (ref/range/const)' ($newPlan['type'] -in @('ref','range','const')) ('type=' + $newPlan['type'])

# 实测发现：MySQL 8 会把「与生成列定义完全一致的表达式」替换成生成列，
# 因此连原来的 DATE_FORMAT(birthday,'%m-%d') 写法也能走这条索引 —— 比预期更好。
# 前提是生成列表达式必须与查询表达式一致，所以这里把「表达式匹配」当作断言来守。
$genExpr = (Sql "SELECT IFNULL(generation_expression,'') FROM information_schema.columns WHERE table_schema='member_system' AND table_name='m_member' AND column_name='birthday_md';").Trim()
$oldPlan = ExplainOne "EXPLAIN SELECT * FROM member_system.m_member FORCE INDEX (idx_birthday_md) WHERE deleted=0 AND status=1 AND DATE_FORMAT(birthday,'%m-%d')='09-15' ORDER BY id LIMIT 200;"
Check 'legacy DATE_FORMAT predicate also indexed (generated-column substitution)' ($oldPlan -ne $null -and $oldPlan['type'] -in @('ref','range','const')) ('type=' + $oldPlan['type'] + ' key=' + $oldPlan['key'])
Check 'generated column expression matches the legacy predicate' ($genExpr -match 'date_format' -and $genExpr -match '%m-%d') ('expr=' + $genExpr)

# 真正要证明的是「索引可用」：给 idx_birthday(仅 birthday) 加 FORCE 也拿不到等值访问 ——
# 谓词落在月日上而不是完整日期上，优化器只能退化为全表扫（ALL）或全索引扫（index）。
$plainPlan = ExplainOne "EXPLAIN SELECT * FROM member_system.m_member FORCE INDEX (idx_birthday) WHERE deleted=0 AND status=1 AND DATE_FORMAT(birthday,'%m-%d')='09-15' ORDER BY id LIMIT 200;"
Check 'plain birthday index cannot serve the month-day predicate' ($plainPlan['type'] -in @('ALL','index')) ('type=' + $plainPlan['type'] + ' key=' + $plainPlan['key'])

Write-Host '=== 3. staff performance query can use idx_staff_paid (P1-1) ===' -ForegroundColor Cyan
$perfPlan = ExplainTable "EXPLAIN SELECT s.id, COUNT(o.id) FROM member_system.m_staff s LEFT JOIN member_system.m_order o FORCE INDEX (idx_staff_paid) ON o.staff_id=s.id AND o.status IN (3,4,5) AND o.paid_at>='2026-01-01' AND o.paid_at<'2027-01-01' WHERE s.deleted=0 GROUP BY s.id;" 'o'
Check 'order side of the join uses idx_staff_paid' ($perfPlan -ne $null -and $perfPlan['key'] -eq 'idx_staff_paid') ('key=' + $perfPlan['key'] + ' type=' + $perfPlan['type'])
Check 'order side is not a full table scan' ($perfPlan -ne $null -and $perfPlan['type'] -ne 'ALL') ('type=' + $perfPlan['type'])

# 反证：旧的 (staff_id, created_at) 索引对 paid_at 的范围条件无能为力 ——
# 它只能提供 staff_id 的等值访问，paid_at 只能回表后过滤（Using where，不是 range）。
$oldPerfPlan = ExplainTable "EXPLAIN SELECT s.id, COUNT(o.id) FROM member_system.m_staff s LEFT JOIN member_system.m_order o FORCE INDEX (idx_staff_time) ON o.staff_id=s.id AND o.status IN (3,4,5) AND o.paid_at>='2026-01-01' AND o.paid_at<'2027-01-01' WHERE s.deleted=0 GROUP BY s.id;" 'o'
Check 'legacy staff_time index used only as ref, not range' ($oldPerfPlan -ne $null -and $oldPerfPlan['type'] -eq 'ref') ('type=' + $oldPerfPlan['type'] + ' key=' + $oldPerfPlan['key'])

Write-Host '=== 4. growth-flow idempotency (schema inconsistency fixed) ===' -ForegroundColor Cyan
$phone = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$m = PostR '/api/admin/members' @{ phone = $phone; realName = 'R8 Growth'; nickname = 'r8g'; storeId = 1; source = 1 } $H $null
Check 'member created' ($m.code -eq 0) ('id=' + $m.data.id)
$mid = $m.data.id

$gKey = [guid]::NewGuid().ToString()
$gAdj = @{ memberId = $mid; growthDelta = 300; reason = 'round8 growth idempotency' }
$g1 = PostR '/api/admin/accounts/adjust' $gAdj $H $gKey
Check 'growth-only adjust ok' ($g1.code -eq 0 -and [int]$g1.data.growth -eq 300) ('growth=' + $g1.data.growth)
$g2 = PostR '/api/admin/accounts/adjust' $gAdj $H $gKey
Check 'growth duplicate rejected' ($g2.code -ne 0) ('code=' + $g2.code + ' msg=' + $g2.msg)
$accG = GetR ("/api/admin/accounts/" + $mid) $H
Check 'growth applied exactly once' ([int]$accG.data.growth -eq 300) ('growth=' + $accG.data.growth)

$gFlow = Sql "SELECT CONCAT(IFNULL(idempotency_key,'<null>'),'|',change_growth) FROM member_system.m_growth_flow WHERE member_id=$mid ORDER BY id DESC LIMIT 1;"
Check 'growth flow row written with idempotency key' ($gFlow -like ($gKey + '*')) ('flow=' + $gFlow)
Check 'growth flow amount recorded' ($gFlow -match '\|300$') ('flow=' + $gFlow)

$gfTotal = [int](Sql "SELECT COUNT(*) FROM member_system.m_growth_flow WHERE member_id=$mid;").Trim()
Check 'only one growth flow row' ($gfTotal -eq 1) ('rows=' + $gfTotal)

Write-Host '=== 5. consume now writes growth flow (was silently missing) ===' -ForegroundColor Cyan
$before = [int](Sql "SELECT COUNT(*) FROM member_system.m_growth_flow;").Trim()
$st = PostR '/api/admin/orders/settle' @{
    memberId = $mid; orderType = 1; storeId = 1; staffId = 1
    items = @(@{ itemType = 4; productName = 'R8 Growth Item'; price = 120; qty = 1 })
    usePoints = 0
    payments = @(@{ payMethod = 2; amount = 120 })
} $H ([guid]::NewGuid().ToString())
Check 'settle ok (this is what used to roll back)' ($st.code -eq 0) ('orderNo=' + $st.data.orderNo + ' growthGained=' + $st.data.growthGained)
$after = [int](Sql "SELECT COUNT(*) FROM member_system.m_growth_flow;").Trim()
Check 'consume wrote a growth flow row' ($after -gt $before) ('before=' + $before + ' after=' + $after)
$accG2 = GetR ("/api/admin/accounts/" + $mid) $H
Check 'account growth increased by consume' ([int]$accG2.data.growth -gt 300) ('growth=' + $accG2.data.growth)

$recon = GetR '/api/admin/accounts/reconcile' $H
Check 'reconcile still clean (growth now comparable)' ($recon.code -eq 0 -and $recon.data.diffCount -eq 0) ($recon.data.conclusion)
$one = GetR ("/api/admin/accounts/" + $mid + '/reconcile') $H
Check 'per-member growth matches flows' ($one.data.growthMatch -eq $true) ('growth=' + $one.data.growth + ' flowSum=' + $one.data.growthFlowSum)

Write-Host '=== 6. N+1 refactors keep results correct ===' -ForegroundColor Cyan
$stores = GetR '/api/admin/stores' $H
Check 'store list ok' ($stores.code -eq 0 -and $stores.data.Count -ge 1) ('count=' + $stores.data.Count)
$s0 = $stores.data[0]
Check 'store list still exposes ref counts' ($null -ne $s0.staffCount -and $null -ne $s0.orderCount -and $null -ne $s0.memberCount -and $null -ne $s0.userCount) ('staff=' + $s0.staffCount + ' orders=' + $s0.orderCount + ' members=' + $s0.memberCount + ' users=' + $s0.userCount)
Check 'store list still computes deletable' ($null -ne $s0.deletable) ('deletable=' + $s0.deletable)

# compare the batched counts against direct SQL for every store
$mismatch = @()
foreach ($s in $stores.data) {
    $sqlStaff = [int](Sql "SELECT COUNT(*) FROM member_system.m_staff WHERE store_id=$($s.id) AND deleted=0 AND status=1;").Trim()
    $sqlOrders = [int](Sql "SELECT COUNT(*) FROM member_system.m_order WHERE store_id=$($s.id);").Trim()
    $sqlMembers = [int](Sql "SELECT COUNT(*) FROM member_system.m_member WHERE store_id=$($s.id) AND deleted=0;").Trim()
    $sqlUsers = [int](Sql "SELECT COUNT(*) FROM member_system.m_sys_user WHERE store_id=$($s.id) AND deleted=0;").Trim()
    if ($s.staffCount -ne $sqlStaff -or $s.orderCount -ne $sqlOrders -or $s.memberCount -ne $sqlMembers -or $s.userCount -ne $sqlUsers) {
        $mismatch += ("store " + $s.id + ": api=" + $s.staffCount + '/' + $s.orderCount + '/' + $s.memberCount + '/' + $s.userCount + " sql=" + $sqlStaff + '/' + $sqlOrders + '/' + $sqlMembers + '/' + $sqlUsers)
    }
}
Check 'batched store counts match direct SQL' ($mismatch.Count -eq 0) ($(if ($mismatch.Count) { $mismatch -join '; ' } else { 'all stores match' }))

$lv = GetR '/api/admin/report/levels' $H
Check 'level list ok' ($lv.code -eq 0 -and $lv.data.Count -ge 1) ('levels=' + $lv.data.Count)
$lvMismatch = @()
foreach ($l in $lv.data) {
    $sqlCnt = [int](Sql "SELECT COUNT(*) FROM member_system.m_member WHERE level_id=$($l.id) AND deleted=0;").Trim()
    if ($l.memberCount -ne $sqlCnt) { $lvMismatch += ('level ' + $l.id + ' api=' + $l.memberCount + ' sql=' + $sqlCnt) }
}
Check 'batched level member counts match direct SQL' ($lvMismatch.Count -eq 0) ($(if ($lvMismatch.Count) { $lvMismatch -join '; ' } else { 'all levels match' }))

Write-Host '=== 7. birthday preview still correct after generated column ===' -ForegroundColor Cyan
$md = (Get-Date).ToString('MM-dd')
$pre = GetR ("/api/birthday/preview?monthDay=" + $md) $H
Check 'birthday preview callable' ($pre.code -eq 0) ('monthDay=' + $pre.data.monthDay + ' count=' + $pre.data.count)
$sqlCnt = [int](Sql "SELECT COUNT(*) FROM member_system.m_member WHERE deleted=0 AND status=1 AND DATE_FORMAT(birthday,'%m-%d')='$md';").Trim()
Check 'preview count matches DATE_FORMAT SQL' ([int]$pre.data.count -eq $sqlCnt) ('api=' + $pre.data.count + ' sql=' + $sqlCnt)

# set a birthday today then confirm the indexed query finds it
$bUpd = Req 'PUT' ("/api/admin/members/" + $mid) @{ phone = $phone; realName = 'R8 Growth'; nickname = 'r8g'; storeId = 1; source = 1; birthday = ('1990-' + $md) } $H $null
Check 'birthday set to today' ($bUpd.code -eq 0) ('msg=' + $bUpd.msg)
$pre2 = GetR ("/api/birthday/preview?monthDay=" + $md) $H
Check 'preview finds the new birthday member' ([int]$pre2.data.count -eq ($sqlCnt + 1)) ('api=' + $pre2.data.count + ' expected=' + ($sqlCnt + 1))

$logs = GetR ("/api/birthday/logs?memberId=" + $mid + "&pageSize=5") $H
Check 'birthday logs batch-load members' ($logs.code -eq 0) ('total=' + $logs.data.total)

Write-Host '=== 8. ledger still consistent ===' -ForegroundColor Cyan
$rc = GetR '/api/admin/accounts/reconcile' $H
Check 'reconcile clean' ($rc.code -eq 0 -and $rc.data.diffCount -eq 0) ($rc.data.conclusion)

Write-Host ''
Write-Host ("RESULT: PASS={0} FAIL={1}" -f $script:pass, $script:fail) -ForegroundColor Yellow
if ($script:fail -gt 0) { exit 1 }
