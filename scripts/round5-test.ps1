# Round 5 smoke test: referral rewards + birthday gift + message center (subscribe message)
# Usage: powershell -ExecutionPolicy Bypass -File scripts\round5-test.ps1
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
function SetCfg($key, $val, $h) { return PutR '/api/admin/system/configs' @{ configKey = $key; configValue = $val } $h }

function MpLogin($tag) {
    $code = 'r5-' + $tag + '-' + (Get-Random -Minimum 100000 -Maximum 999999)
    $lg = PostR '/api/mp/auth/login' @{ code = $code } $null $null
    if ($lg.code -ne 0) { return $null }
    $tkv = $lg.data.accessToken
    $mid = $lg.data.member.id
    $phone = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
    $bp = PostR '/api/mp/auth/bind-phone' @{ phone = $phone } @{ Authorization = 'Bearer ' + $tkv } $null
    if ($bp.code -ne 0) { return $null }
    # bind-phone may merge into an existing member and rotate the token
    if ($bp.data.memberId -ne $null) { $mid = $bp.data.memberId }
    if ($bp.data.accessToken -ne $null) { $tkv = $bp.data.accessToken }
    return [pscustomobject]@{ token = @{ Authorization = 'Bearer ' + $tkv }; memberId = $mid; phone = $phone }
}

function Consume($memberId, $amount, $h) {
    $order = @{
        memberId = $memberId; orderType = 1; storeId = 1; staffId = 1
        items = @(@{ itemType = 4; productName = 'R5 Item'; price = $amount; qty = 1 })
        usePoints = 0
        payments = @(@{ payMethod = 2; amount = $amount })
    }
    return PostR '/api/admin/orders/settle' $order $h ([guid]::NewGuid().ToString())
}

Write-Host '=== login + permissions ===' -ForegroundColor Cyan
$login = PostR '/api/admin/auth/login' @{ username = 'admin'; password = 'Admin@123' } $null $null
if ($login.code -ne 0) { Write-Host 'login failed' -ForegroundColor Red; exit 1 }
$H = @{ Authorization = 'Bearer ' + $login.data.accessToken }
Check 'admin login' ($login.code -eq 0) ('perms=' + $login.data.userInfo.perms.Count)
$newPerms = @('referral:list','referral:config','referral:settle','birthday:list','birthday:config','birthday:issue','message:tpl:list','message:tpl:edit','message:record:list','message:record:resend')
$missing = @($newPerms | Where-Object { $login.data.userInfo.perms -notcontains $_ })
Check 'round5 perms present' ($missing.Count -eq 0) ('missing=' + ($missing -join ','))

Write-Host '=== 1. message templates + channel readiness ===' -ForegroundColor Cyan
$tpls = GetR '/api/message/tpls' $H
Check 'tpl list' ($tpls.code -eq 0 -and $tpls.data.Count -ge 9) ('count=' + $tpls.data.Count)
$bdTpl = $tpls.data | Where-Object { $_.tplCode -eq 'BIRTHDAY_GIFT' } | Select-Object -First 1
Check 'birthday tpl has wx map' ($bdTpl -ne $null -and $bdTpl.wxDataMap -ne $null -and $bdTpl.wxDataMap.Length -gt 5) ('map=' + $bdTpl.wxDataMap)
Check 'birthday tpl placeholders' ($bdTpl.placeholders -contains 'memberName') ('ph=' + ($bdTpl.placeholders -join ','))

$ch0 = GetR '/api/message/channels' $H
$inner = $ch0.data | Where-Object { $_.code -eq 'INNER' } | Select-Object -First 1
$wx = $ch0.data | Where-Object { $_.code -eq 'WX_SUBSCRIBE' } | Select-Object -First 1
Check 'inner channel always available' ($inner -ne $null -and $inner.available -eq $true) ('name=' + $inner.name)
Check 'wx channel not ready without appid' ($wx -ne $null -and $wx.available -eq $false) ('reason=' + $wx.reason)

SetCfg 'wx.miniapp.appid' 'wxtestappid0001' $H | Out-Null
SetCfg 'wx.miniapp.secret' 'testsecret0001' $H | Out-Null
$ch1 = GetR '/api/message/channels' $H
$wx1 = $ch1.data | Where-Object { $_.code -eq 'WX_SUBSCRIBE' } | Select-Object -First 1
Check 'wx channel ready after config' ($wx1.available -eq $true) ('available=' + $wx1.available)
SetCfg 'wx.miniapp.appid' '' $H | Out-Null
SetCfg 'wx.miniapp.secret' '' $H | Out-Null
$ch2 = GetR '/api/message/channels' $H
$wx2 = $ch2.data | Where-Object { $_.code -eq 'WX_SUBSCRIBE' } | Select-Object -First 1
Check 'wx channel back to degraded' ($wx2.available -eq $false) ('reason=' + $wx2.reason)

Write-Host '=== 2. referral rule config validation ===' -ForegroundColor Cyan
$rc = GetR '/api/referral/config' $H
Check 'referral config items' ($rc.code -eq 0 -and $rc.data.Count -ge 14) ('items=' + $rc.data.Count)
$bad1 = PutR '/api/referral/config' @{ 'referral.settle-mode' = 'NOT_A_MODE' } $H
Check 'invalid settle-mode rejected' ($bad1.code -ne 0) ('code=' + $bad1.code + ' msg=' + $bad1.msg)
$bad2 = PutR '/api/referral/config' @{ 'referral.reward-referrer-type' = 'COUPON'; 'referral.reward-referrer-coupon-tpl-id' = '0' } $H
Check 'coupon reward without tpl rejected' ($bad2.code -ne 0) ('msg=' + $bad2.msg)
$bad3 = PutR '/api/referral/config' @{ 'referral.bind-window-days' = '99999' } $H
Check 'out-of-range window rejected' ($bad3.code -ne 0) ('msg=' + $bad3.msg)
$good = PutR '/api/referral/config' @{
    'referral.enabled' = 'true'; 'referral.settle-mode' = 'FIRST_CONSUME'
    'referral.bind-window-days' = '7'; 'referral.min-amount' = '0'
    'referral.require-phone' = 'true'; 'referral.max-per-referrer-month' = '20'
    'referral.reward-referrer-type' = 'POINTS'; 'referral.reward-referrer-value' = '500'
    'referral.reward-invitee-type' = 'COUPON'; 'referral.reward-invitee-coupon-tpl-id' = '5'
} $H
Check 'valid rule saved' ($good.code -eq 0) ('code=' + $good.code)

Write-Host '=== 3. two members + invite code ===' -ForegroundColor Cyan
$A = MpLogin 'A'
$B = MpLogin 'B'
Check 'member A created' ($A -ne $null) ('id=' + $A.memberId)
Check 'member B created' ($B -ne $null) ('id=' + $B.memberId)
if ($A -eq $null -or $B -eq $null) { Write-Host 'member setup failed, abort' -ForegroundColor Red; exit 1 }

$centerA = GetR '/api/mp/referral' $A.token
Check 'referral center ok' ($centerA.code -eq 0 -and $centerA.data.enabled -eq $true) ('code=' + $centerA.data.inviteCode)
$codeA = $centerA.data.inviteCode
Check 'invite code non-empty' ($codeA.Length -ge 5) ('code=' + $codeA)
Check 'no invitees yet' ($centerA.data.inviteCount -eq 0) ('inviteCount=' + $centerA.data.inviteCount)

Write-Host '=== 4. bind anti-fraud chain ===' -ForegroundColor Cyan
$self = PostR '/api/mp/referral/bind' @{ inviteCode = $codeA } $A.token $null
Check 'self-referral rejected' ($self.code -ne 0) ('msg=' + $self.msg)
$garbage = PostR '/api/mp/referral/bind' @{ inviteCode = 'ZZZZZZZZ' } $B.token $null
Check 'invalid code rejected' ($garbage.code -ne 0) ('msg=' + $garbage.msg)
$tampered = $codeA.Substring(0, $codeA.Length - 1) + $(if ($codeA.EndsWith('2')) { '3' } else { '2' })
$bad = PostR '/api/mp/referral/bind' @{ inviteCode = $tampered } $B.token $null
Check 'tampered code rejected' ($bad.code -ne 0) ('code=' + $tampered + ' msg=' + $bad.msg)

$bind = PostR '/api/mp/referral/bind' @{ inviteCode = $codeA; channel = 'POSTER' } $B.token $null
Check 'bind ok' ($bind.code -eq 0) ('referralId=' + $bind.data.referralId)
$again = PostR '/api/mp/referral/bind' @{ inviteCode = $codeA } $B.token $null
Check 'double bind rejected' ($again.code -ne 0) ('msg=' + $again.msg)
$centerA2 = GetR '/api/mp/referral' $A.token
Check 'invitee visible to referrer' ($centerA2.data.inviteCount -eq 1) ('inviteCount=' + $centerA2.data.inviteCount)

Write-Host '=== 5. settle on first consume ===' -ForegroundColor Cyan
$accA0 = (GetR '/api/mp/home/summary' $A.token).data.account
Check 'referrer starts with 0 points' ($accA0.points -eq 0) ('points=' + $accA0.points)
$st1 = Consume $B.memberId 100 $H
Check 'invitee first consume ok' ($st1.code -eq 0) ('orderNo=' + $st1.data.orderNo)

$rel = GetR '/api/referral/relations?status=2' $H
Check 'referral settled' ($rel.code -eq 0 -and $rel.data.total -ge 1) ('settled=' + $rel.data.total)
$relRow = $rel.data.list | Where-Object { $_.inviteeId -eq $B.memberId } | Select-Object -First 1
Check 'settle row bound to correct invitee' ($relRow -ne $null -and $relRow.status -eq 2 -and $relRow.settleAt -ne $null) ('bizNo=' + $relRow.triggerBizNo)

$rwA = GetR ("/api/referral/rewards?memberId=" + $A.memberId) $H
$rowA = $rwA.data.list | Select-Object -First 1
Check 'referrer reward issued' ($rowA -ne $null -and $rowA.status -eq 2 -and $rowA.rewardType -eq 1) ('type=' + $rowA.rewardTypeText + ' value=' + $rowA.rewardValue)
Check 'referrer reward value 500' ($rowA.rewardValue -eq 500) ('value=' + $rowA.rewardValue)

$rwB = GetR ("/api/referral/rewards?memberId=" + $B.memberId) $H
$rowB = $rwB.data.list | Select-Object -First 1
Check 'invitee coupon reward issued' ($rowB -ne $null -and $rowB.status -eq 2 -and $rowB.rewardType -eq 3 -and $rowB.couponId -ne $null) ('couponId=' + $rowB.couponId)

$accA1 = (GetR '/api/mp/home/summary' $A.token).data.account
Check 'referrer points +500' ($accA1.points -eq 500) ('points=' + $accA1.points)
$cpsB = (GetR '/api/mp/home/summary' $B.token).data.couponCount
Check 'invitee has coupon in wallet' ($cpsB.unused -ge 1) ('unused=' + $cpsB.unused)

$st2 = Consume $B.memberId 50 $H
Check 'invitee second consume ok' ($st2.code -eq 0) ('orderNo=' + $st2.data.orderNo)
$accA2 = (GetR '/api/mp/home/summary' $A.token).data.account
Check 'no double reward on 2nd order' ($accA2.points -eq 500) ('points=' + $accA2.points)
$rwA2 = GetR ("/api/referral/rewards?memberId=" + $A.memberId) $H
Check 'still one reward row for referrer' ($rwA2.data.total -eq 1) ('total=' + $rwA2.data.total)

$centerA3 = GetR '/api/mp/referral' $A.token
Check 'center shows settled stats' ($centerA3.data.settledCount -eq 1 -and $centerA3.data.totalPoints -eq 500) ('settled=' + $centerA3.data.settledCount + ' points=' + $centerA3.data.totalPoints)

Write-Host '=== 6. monthly cap only limits referrer ===' -ForegroundColor Cyan
SetCfg 'referral.max-per-referrer-month' '1' $H | Out-Null
$D = MpLogin 'D'
Check 'member D created' ($D -ne $null) ('id=' + $D.memberId)
$bindD = PostR '/api/mp/referral/bind' @{ inviteCode = $codeA; channel = 'LINK' } $D.token $null
Check 'D bind ok' ($bindD.code -eq 0) ('referralId=' + $bindD.data.referralId)
$stD = Consume $D.memberId 100 $H
Check 'D consume ok' ($stD.code -eq 0) ('orderNo=' + $stD.data.orderNo)

$capRows = (GetR ("/api/referral/rewards?memberId=" + $A.memberId + "&status=3") $H).data
Check 'referrer reward skipped over cap' ($capRows.total -ge 1) ('skipped=' + $capRows.total)
$capRow = $capRows.list | Select-Object -First 1
Check 'skip reason recorded' ($capRow.failReason -ne $null -and $capRow.failReason.Length -gt 0) ('reason=' + $capRow.failReason)
Check 'skipped row is referrer POINTS reward' ($capRow.rewardTarget -eq 1 -and $capRow.rewardType -eq 1) ('target=' + $capRow.rewardTarget + ' type=' + $capRow.rewardType)
$accA3 = (GetR '/api/mp/home/summary' $A.token).data.account
Check 'referrer points unchanged over cap' ($accA3.points -eq 500) ('points=' + $accA3.points)
$rwD = (GetR ("/api/referral/rewards?memberId=" + $D.memberId) $H).data
Check 'invitee reward still paid over cap' ($rwD.total -eq 1 -and $rwD.list[0].status -eq 2) ('status=' + $rwD.list[0].status)
SetCfg 'referral.max-per-referrer-month' '20' $H | Out-Null

Write-Host '=== 7. referral overview + manual settle ===' -ForegroundColor Cyan
$stats = GetR '/api/referral/stats' $H
Check 'referral stats ok' ($stats.code -eq 0 -and $stats.data.settled -ge 2) ('bound=' + $stats.data.bound + ' settled=' + $stats.data.settled + ' rate=' + $stats.data.settleRate)
Check 'stats has reward breakdown' ($stats.data.rewardByType.Count -eq 5) ('types=' + $stats.data.rewardByType.Count)
$scan = PostR '/api/referral/tasks/settle-scan' $null $H $null
Check 'settle-scan callable' ($scan.code -eq 0) ('processed=' + $scan.data)
$retry = PostR '/api/referral/tasks/retry-rewards' $null $H $null
Check 'retry-rewards callable' ($retry.code -eq 0) ('processed=' + $retry.data)

Write-Host '=== 8. birthday gift ===' -ForegroundColor Cyan
$today = Get-Date
$md = $today.ToString('MM-dd')
$yyyy = $today.Year
$bdStr = '1990-' + $md

$mb = GetR ("/api/admin/members/" + $B.memberId) $H
Check 'member B fetchable by admin' ($mb.code -eq 0) ('memberNo=' + $mb.data.memberNo)
$upd = PutR ("/api/admin/members/" + $B.memberId) @{
    phone = $B.phone; realName = 'Birthday Kid'; nickname = 'bd'; storeId = 1; source = 2; birthday = $bdStr
} $H
Check 'set birthday to today' ($upd.code -eq 0) ('birthday=' + $bdStr + ' msg=' + $upd.msg)

$prev = GetR ("/api/birthday/preview?monthDay=" + $md) $H
Check 'birthday preview finds member' ($prev.code -eq 0 -and $prev.data.count -ge 1) ('count=' + $prev.data.count + ' md=' + $md)
Check 'preview lists coupon tpl name' ($prev.data.couponTplName -ne $null) ('tpl=' + $prev.data.couponTplName)

$beforeB = (GetR '/api/mp/home/summary' $B.token).data.account.points
$issue = PostR '/api/birthday/tasks/issue' $null $H $null
Check 'birthday batch issued' ($issue.code -eq 0 -and $issue.data -ge 1) ('issued=' + $issue.data)

$logs = GetR ("/api/birthday/logs?memberId=" + $B.memberId + "&year=" + $yyyy) $H
$log = $logs.data.list | Select-Object -First 1
Check 'birthday log created' ($log -ne $null -and $log.status -eq 1) ('status=' + $log.statusText + ' fail=' + $log.failReason)
Check 'birthday points 200' ($log.points -eq 200) ('points=' + $log.points)
Check 'birthday coupon issued' ($log.couponId -ne $null) ('couponId=' + $log.couponId)

$issue2 = PostR '/api/birthday/tasks/issue' $null $H $null
Check 'birthday batch idempotent' ($issue2.code -eq 0 -and $issue2.data -eq 0) ('issued=' + $issue2.data)

$dup = PostR '/api/birthday/issue' @{ memberId = $B.memberId; year = $yyyy; force = $false } $H $null
Check 'manual duplicate rejected' ($dup.code -ne 0) ('msg=' + $dup.msg)

$manual = PostR '/api/birthday/issue' @{ memberId = $A.memberId; year = $yyyy; force = $true } $H $null
Check 'manual issue for another member' ($manual.code -eq 0 -and $manual.data.status -eq 1) ('points=' + $manual.data.points + ' coupon=' + $manual.data.couponId)

$accB1 = (GetR '/api/mp/home/summary' $B.token).data.account
Check 'birthday points added (+200)' (($accB1.points - $beforeB) -eq 200) ('before=' + $beforeB + ' after=' + $accB1.points)

$mpBd = GetR '/api/mp/birthday' $B.token
Check 'mp birthday page' ($mpBd.code -eq 0 -and $mpBd.data.hasBirthday -eq $true) ('birthday=' + $mpBd.data.birthday)
Check 'mp birthday daysUntil 0' ($mpBd.data.daysUntil -eq 0) ('daysUntil=' + $mpBd.data.daysUntil)
Check 'mp birthday gifted flag' ($mpBd.data.giftedThisYear -eq $true) ('gifted=' + $mpBd.data.giftedThisYear)
Check 'mp birthday gift desc' ($mpBd.data.giftDesc.Length -gt 0) ('desc=' + $mpBd.data.giftDesc)

Write-Host '=== 9. message center (MP) ===' -ForegroundColor Cyan
$msgs = GetR '/api/mp/messages?pageSize=50' $B.token
Check 'mp messages listed' ($msgs.code -eq 0 -and $msgs.data.total -ge 2) ('total=' + $msgs.data.total)
$bdMsg = $msgs.data.list | Where-Object { $_.tplCode -eq 'BIRTHDAY_GIFT' } | Select-Object -First 1
Check 'birthday message delivered' ($bdMsg -ne $null -and $bdMsg.status -eq 2) ('channel=' + $bdMsg.channel + ' title=' + $bdMsg.title)
Check 'birthday message degraded to inner' ($bdMsg.channel -eq 'INNER') ('channel=' + $bdMsg.channel)
Check 'degrade reason recorded' ($bdMsg.errorMsg -ne $null -and $bdMsg.errorMsg.Length -gt 0) ('err=' + $bdMsg.errorMsg)
Check 'message content rendered' ($bdMsg.content -notlike '*{memberName}*') ('content=' + $bdMsg.content.Substring(0, [Math]::Min(40, $bdMsg.content.Length)))

$msgsA = GetR '/api/mp/messages?pageSize=50' $A.token
$refMsg = $msgsA.data.list | Where-Object { $_.tplCode -eq 'REFERRAL_REWARD' } | Select-Object -First 1
Check 'referral reward message delivered' ($refMsg -ne $null -and $refMsg.status -eq 2) ('content=' + $refMsg.content)

$unread = GetR '/api/mp/messages/unread-count' $B.token
Check 'unread count > 0' ($unread.code -eq 0 -and $unread.data -ge 2) ('unread=' + $unread.data)
$rd = PostR ("/api/mp/messages/" + $bdMsg.id + '/read') $null $B.token $null
Check 'mark read ok' ($rd.code -eq 0) ('msgId=' + $bdMsg.id)
$msgs2 = GetR '/api/mp/messages?pageSize=50' $B.token
$bdMsg2 = $msgs2.data.list | Where-Object { $_.id -eq $bdMsg.id } | Select-Object -First 1
Check 'message status now read' ($bdMsg2.status -eq 4) ('status=' + $bdMsg2.statusText)
$ra = PostR '/api/mp/messages/read-all' $null $B.token $null
Check 'read-all ok' ($ra.code -eq 0) ('updated=' + $ra.data)
$unread2 = GetR '/api/mp/messages/unread-count' $B.token
Check 'unread now 0' ($unread2.data -eq 0) ('unread=' + $unread2.data)

Write-Host '=== 10. subscribe quota ===' -ForegroundColor Cyan
$rep0 = PostR '/api/mp/messages/subscribe/report' @{ templateIds = @() } $B.token $null
Check 'empty report ok' ($rep0.code -eq 0 -and $rep0.data -eq 0) ('granted=' + $rep0.data)
$rep1 = PostR '/api/mp/messages/subscribe/report' @{ templateIds = @('wxtpl-test-0001') } $B.token $null
Check 'report grants quota' ($rep1.code -eq 0 -and $rep1.data -eq 1) ('granted=' + $rep1.data)
$quota = GetR '/api/mp/messages/subscribe/quota' $B.token
$q1 = $quota.data | Where-Object { $_.wxTemplateId -eq 'wxtpl-test-0001' } | Select-Object -First 1
Check 'quota recorded' ($q1 -ne $null -and $q1.remainCount -eq 1) ('remain=' + $q1.remainCount + ' total=' + $q1.totalCount)
$rep2 = PostR '/api/mp/messages/subscribe/report' @{ templateIds = @('wxtpl-test-0001') } $B.token $null
$quota2 = GetR '/api/mp/messages/subscribe/quota' $B.token
$q2 = $quota2.data | Where-Object { $_.wxTemplateId -eq 'wxtpl-test-0001' } | Select-Object -First 1
Check 'quota accumulates' ($q2.remainCount -eq 2) ('remain=' + $q2.remainCount)

Write-Host '=== 11. admin message records + resend ===' -ForegroundColor Cyan
$recs = GetR ("/api/message/records?memberId=" + $B.memberId + '&pageSize=10') $H
Check 'admin record list' ($recs.code -eq 0 -and $recs.data.total -ge 2) ('total=' + $recs.data.total)
$openidMasked = $recs.data.list[0].openid
Check 'openid masked in admin list' ($openidMasked -eq $null -or $openidMasked -notlike '*abcdef*') ('openid=' + $openidMasked)
$target = $recs.data.list | Where-Object { $_.tplCode -eq 'BIRTHDAY_GIFT' } | Select-Object -First 1
$rs = PostR ("/api/message/records/" + $target.id + '/resend') $null $H $null
Check 'resend ok' ($rs.code -eq 0) ('msgId=' + $target.id)
$rsBad = PostR '/api/message/records/99999999/resend' $null $H $null
Check 'resend unknown id rejected' ($rsBad.code -ne 0) ('msg=' + $rsBad.msg)
$rt = PostR '/api/message/records/retry?limit=50' $null $H $null
Check 'retry batch callable' ($rt.code -eq 0) ('processed=' + $rt.data)

$tpl2 = $tpls.data | Where-Object { $_.tplCode -eq 'BIRTHDAY_GIFT' } | Select-Object -First 1
$origTitle = $tpl2.title
$origContent = $tpl2.contentTpl
$origMap = $tpl2.wxDataMap
$tu = PutR ("/api/message/tpls/" + $tpl2.id) @{ title = 'Happy Birthday'; contentTpl = 'Hi {memberName}, enjoy {giftDesc}!' } $H
Check 'tpl edit ok' ($tu.code -eq 0) ('id=' + $tpl2.id)
$tuRead = GetR '/api/message/tpls' $H
$tpl2b = $tuRead.data | Where-Object { $_.tplCode -eq 'BIRTHDAY_GIFT' } | Select-Object -First 1
Check 'tpl edit persisted' ($tpl2b.title -eq 'Happy Birthday') ('title=' + $tpl2b.title)
Check 'tpl placeholders re-parsed' ($tpl2b.placeholders -contains 'giftDesc') ('ph=' + ($tpl2b.placeholders -join ','))
$tuBad = PutR ("/api/message/tpls/" + $tpl2.id) @{ contentTpl = '' } $H
Check 'empty content rejected' ($tuBad.code -ne 0) ('msg=' + $tuBad.msg)
$tuJson = PutR ("/api/message/tpls/" + $tpl2.id) @{ wxDataMap = '{not-json' } $H
Check 'bad wx map rejected' ($tuJson.code -ne 0) ('msg=' + $tuJson.msg)
# restore the original template body captured from the API (avoids non-ASCII literals in this script)
PutR ("/api/message/tpls/" + $tpl2.id) @{ title = $origTitle; contentTpl = $origContent; wxDataMap = $origMap } $H | Out-Null
$tuRead2 = GetR '/api/message/tpls' $H
$tpl2c = $tuRead2.data | Where-Object { $_.tplCode -eq 'BIRTHDAY_GIFT' } | Select-Object -First 1
Check 'tpl restored' ($tpl2c.title -eq $origTitle -and $tpl2c.contentTpl -eq $origContent) ('title=' + $tpl2c.title)

Write-Host '=== 13. wechat pay channel readiness + local self-test ===' -ForegroundColor Cyan
$chAll = GetR '/api/admin/payments/channels' $H
Check 'payment channel status ok' ($chAll.code -eq 0) ('payEnabled=' + $chAll.data.payEnabled + ' default=' + $chAll.data.defaultChannel)
$wxCh = $chAll.data.channels | Where-Object { $_.channel -eq 'WECHAT' } | Select-Object -First 1
$mockCh = $chAll.data.channels | Where-Object { $_.channel -eq 'MOCK' } | Select-Object -First 1
Check 'mock channel registered' ($mockCh -ne $null) ('ready=' + $mockCh.ready)
Check 'wechat channel registered and not ready' ($wxCh -ne $null -and $wxCh.ready -eq $false) ('missing=' + $wxCh.missing.Count)
Check 'wechat diagnose exposed' ($wxCh.diagnose -ne $null -and $wxCh.diagnose.privateKeyConfigured -eq $false) ('certMode=' + $wxCh.diagnose.platformCertMode)

$st0 = GetR '/api/admin/payments/channels/wechat/self-test' $H
Check 'self-test callable when unconfigured' ($st0.code -eq 0 -and $st0.data.ready -eq $false) ('problems=' + $st0.data.problems.Count)

# generate a throwaway PKCS#8 RSA key with openssl (ships with Git for Windows).
# If openssl is unavailable the happy-path key checks are skipped instead of failed.
$keyDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'logs\wxpay'
if (-not (Test-Path $keyDir)) { New-Item -ItemType Directory -Path $keyDir -Force | Out-Null }
$keyFile = Join-Path $keyDir 'apiclient_key.pem'
$openssl = $null
foreach ($candidate in @('openssl', 'C:\Program Files\Git\usr\bin\openssl.exe', 'C:\Program Files\Git\mingw64\bin\openssl.exe')) {
    if (Get-Command $candidate -ErrorAction SilentlyContinue) { $openssl = $candidate; break }
}
$keyReady = $false
if ($openssl) {
    if (Test-Path $keyFile) { Remove-Item $keyFile -Force }
    & $openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out $keyFile 2>$null | Out-Null
    $keyReady = (Test-Path $keyFile) -and ((Get-Content $keyFile -Raw) -like '*BEGIN PRIVATE KEY*')
}
if (-not $keyReady) {
    Write-Host '  [SKIP] openssl unavailable, happy-path private key checks skipped' -ForegroundColor Yellow
}
Check 'test private key generated' ($keyReady) ('file=' + $keyFile)

$v3key = 'AbCdEfGhIjKlMnOpQrStUvWxYz012345'
SetCfg 'pay.wechat.mch-id' '1900000109' $H | Out-Null
SetCfg 'pay.wechat.mch-serial-no' '5F2A1B3C4D5E6F708192A3B4C5D6E7F801234567' $H | Out-Null
SetCfg 'pay.wechat.appid' 'wx1234567890abcdef' $H | Out-Null
SetCfg 'pay.wechat.apiv3-key' $v3key $H | Out-Null
SetCfg 'pay.wechat.private-key' ('file:' + $keyFile) $H | Out-Null
SetCfg 'pay.wechat.notify-url' 'https://member.example.com/api/open/pay/notify/wechat' $H | Out-Null
if ($keyReady) {
    $st1 = GetR '/api/admin/payments/channels/wechat/self-test' $H
    Check 'self-test ready with full config' ($st1.data.ready -eq $true) ('problems=' + ($st1.data.problems -join ';'))
    Check 'private key parsed' ($st1.data.privateKey.parsed -eq $true) ('algorithm=' + $st1.data.privateKey.algorithm)
    Check 'private key self-verify passes' ($st1.data.privateKey.selfVerify -eq $true) ('selfVerify=' + $st1.data.privateKey.selfVerify)
    Check 'apiv3 key length valid' ($st1.data.apiV3Key.valid -eq $true) ('length=' + $st1.data.apiV3Key.length)
    Check 'notify url is https' ($st1.data.notify.https -eq $true) ($st1.data.notify.url)

    $chReady = GetR '/api/admin/payments/channels' $H
    $wxCh2 = $chReady.data.channels | Where-Object { $_.channel -eq 'WECHAT' } | Select-Object -First 1
    Check 'wechat channel now ready' ($wxCh2.ready -eq $true) ('missing=' + $wxCh2.missing.Count)
    Check 'wechat diagnose hides secrets' ($wxCh2.diagnose.privateKeyConfigured -eq $true -and $wxCh2.diagnose.apiV3KeyConfigured -eq $true) ('mchId=' + $wxCh2.diagnose.mchId)
    Check 'wechat diagnose never returns key material' ($wxCh2.diagnose.PSObject.Properties.Name -notcontains 'privateKey') ('fields=' + ($wxCh2.diagnose.PSObject.Properties.Name -join ','))
}

SetCfg 'pay.wechat.apiv3-key' 'tooshort' $H | Out-Null
$st2 = GetR '/api/admin/payments/channels/wechat/self-test' $H
Check 'short apiv3 key rejected' ($st2.data.ready -eq $false -and $st2.data.apiV3Key.valid -eq $false) ('length=' + $st2.data.apiV3Key.length)
SetCfg 'pay.wechat.apiv3-key' $v3key $H | Out-Null

$badFile = Join-Path $keyDir 'bad_pkcs1.pem'
[System.IO.File]::WriteAllText($badFile, "-----BEGIN RSA PRIVATE KEY-----`nMIIBOgIBAAJBAKj34GkxFhD90vcNLYLInFEX6Ppy1tPf9Cnzj4p4WGeKLs1Pt8Qu`n-----END RSA PRIVATE KEY-----`n", (New-Object System.Text.UTF8Encoding($false)))
if ($keyReady) {
    SetCfg 'pay.wechat.private-key' ('file:' + $badFile) $H | Out-Null
    $st3 = GetR '/api/admin/payments/channels/wechat/self-test' $H
    Check 'pkcs1 key rejected with hint' ($st3.data.ready -eq $false -and ($st3.data.problems -join ' ') -like '*PKCS#8*') ('problems=' + ($st3.data.problems -join ';'))
    SetCfg 'pay.wechat.private-key' ('file:' + $keyFile) $H | Out-Null
}

SetCfg 'pay.wechat.notify-url' 'http://member.example.com/api/open/pay/notify/wechat' $H | Out-Null
$st4 = GetR '/api/admin/payments/channels/wechat/self-test' $H
Check 'non-https notify url rejected' ($st4.data.ready -eq $false -and $st4.data.notify.https -eq $false) ('url=' + $st4.data.notify.url)

$rf = PostR '/api/admin/payments/channels/wechat/refresh' $null $H $null
Check 'refresh cache callable' ($rf.code -eq 0) ('code=' + $rf.code)

# restore a clean state: channel stays unconfigured by default
foreach ($k in @('pay.wechat.mch-id','pay.wechat.mch-serial-no','pay.wechat.appid','pay.wechat.apiv3-key','pay.wechat.private-key','pay.wechat.notify-url')) {
    SetCfg $k '' $H | Out-Null
}
$st5 = GetR '/api/admin/payments/channels/wechat/self-test' $H
Check 'self-test back to unconfigured' ($st5.data.ready -eq $false) ('problems=' + $st5.data.problems.Count)

Write-Host '=== 14. access control ===' -ForegroundColor Cyan
$mpOnAdmin = GetR '/api/referral/relations' $B.token
Check 'mp token cannot read admin referral' ($mpOnAdmin.code -ne 0) ('code=' + $mpOnAdmin.code)
$adminOnMp = GetR '/api/mp/referral' $H
Check 'admin token cannot read mp referral' ($adminOnMp.code -ne 0) ('code=' + $adminOnMp.code)
$anon = GetR '/api/message/tpls' $null
Check 'anonymous blocked' ($anon.code -ne 0) ('code=' + $anon.code)

Write-Host '=== 15. ledger still consistent ===' -ForegroundColor Cyan
$rc2 = GetR '/api/admin/accounts/reconcile' $H
Check 'reconcile clean' ($rc2.code -eq 0 -and $rc2.data.diffCount -eq 0) ($rc2.data.conclusion)

Write-Host ''
Write-Host ("RESULT: PASS={0} FAIL={1}" -f $script:pass, $script:fail) -ForegroundColor Yellow
if ($script:fail -gt 0) { exit 1 }
