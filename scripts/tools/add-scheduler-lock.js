// scripts/tools/add-scheduler-lock.js —— 给所有 @Scheduled 方法补 @SchedulerLock
//
// 背景（docs/13-优化方案.md §P2-1）：
//   项目引了 ShedLock 但从未启用，等于只能单实例部署。
//   本脚本给每个 @Scheduled 方法插入 @SchedulerLock(name=..., lockAtMostFor=...)：
//     - name 用「类名.方法名」，聚类唯一且可读（shedlock 表里看到的就是它）
//     - lockAtMostFor 按任务特征给：批量扫描类给 30 分钟，每 5 分钟跑一次的消息重试给 5 分钟
//       （必须小于任务间隔，否则下一轮会被自己的上一轮挡住）
//     - lockAtLeastFor 给一个小的防抖值，避免多实例反复抢锁空转

const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..', '..');
// 注意：Job 类不止在 com.company.member.job —— 后续几轮的模块各带了自己的 job 包
// （modules/birthday/job、modules/message/job、modules/referral/job）。
// 第一版只扫了 job/，漏掉整个第三轮新增的三个任务，这里改为全树扫描 *Job.java。
const javaRoot = path.join(root, 'backend', 'src', 'main', 'java');
const dry = process.argv.includes('--dry');

function findAllJobs(dir, out = []) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, e.name);
        if (e.isDirectory()) findAllJobs(p, out);
        else if (e.name.endsWith('Job.java')) out.push(p);
    }
    return out;
}

/** 锁参数按「同名任务」定制；key 为 类名#方法名 */
const LOCK_SPEC = {
    'MarketingJob#couponExpireJob': ['PT20M', 'PT1M'],
    'MarketingJob#pointExpireJob': ['PT20M', 'PT1M'],
    'MemberJob#cardExpireJob': ['PT20M', 'PT1M'],
    'MemberJob#levelEvaluateJob': ['PT30M', 'PT1M'],
    'BirthdayJob#issueJob': ['PT30M', 'PT1M'],
    'BirthdayJob#repairJob': ['PT20M', 'PT1M'],
    // 每 5 分钟一次，持锁上限必须小于间隔
    'MessageJob#retryJob': ['PT4M', 'PT30S'],
    'ReferralJob#settleScanJob': ['PT20M', 'PT1M'],
    'ReferralJob#rewardRetryJob': ['PT20M', 'PT1M'],
    'ReferralJob#reconcileJob': ['PT20M', 'PT1M'],
    // fixedDelay 10 分钟
    'HoldOrderJob#cleanExpiredHolds': ['PT4M', 'PT30S'],
    // PaymentJob 的间隔很短（30s / 60s / 5min），lockAtMostFor 必须显著小于间隔，
    // 否则「上一轮还没跑完 → 下一轮被自己的锁挡住」，补偿任务反而变成不补偿。
    // 这些任务本身都是幂等的（按支付单状态条件更新），短锁 + 频繁执行是合适的。
    'PaymentJob#paySyncJob': ['PT25S', 'PT5S'],
    'PaymentJob#payCloseJob': ['PT50S', 'PT5S'],
    'PaymentJob#couponUnlockJob': ['PT4M', 'PT30S']
};

let inserted = 0;
const missed = [];

for (const abs of findAllJobs(javaRoot)) {
    const name = path.basename(abs);
    const cls = name.replace('.java', '');
    let lines = fs.readFileSync(abs, 'utf8').split('\n');
    let changed = false;

    for (let i = 0; i < lines.length; i++) {
        const m = lines[i].match(/^\s*@Scheduled\(/);
        if (!m) continue;
        // 找方法名（往下 5 行内的 public void xxx()
        let method = null;
        let sigIdx = -1;
        for (let j = i; j < Math.min(lines.length, i + 6); j++) {
            const mm = lines[j].match(/public\s+void\s+(\w+)\s*\(/);
            if (mm) { method = mm[1]; sigIdx = j; break; }
        }
        if (!method) { missed.push(`${cls}: @Scheduled 后找不到方法（行 ${i + 1}）`); continue; }
        const key = `${cls}#${method}`;
        const spec = LOCK_SPEC[key];
        if (!spec) { missed.push(`${key}: 未在 LOCK_SPEC 中登记`); continue; }
        // 已存在则跳过
        if (/@SchedulerLock/.test(lines.slice(Math.max(0, i - 3), sigIdx + 1).join('\n'))) continue;
        lines.splice(sigIdx, 0,
            `    @SchedulerLock(name = "${key}",`,
            `            lockAtMostFor = "${spec[0]}",`,
            `            lockAtLeastFor = "${spec[1]}")`);
        inserted++;
        changed = true;
    }

    if (changed) {
        let src = lines.join('\n');
        if (!src.includes('import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;')) {
            src = src.replace(/^(import .*\n)/m,
                '$1import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;\n');
        }
        // import 顺序无所谓（编译不看顺序），但把 shedlock 的 import 放在 org.springframework 之前更整齐
        if (!dry) fs.writeFileSync(abs, src, 'utf8');
        console.log(`${dry ? '[dry] ' : ''}${cls}: 已加锁`);
    }
}

console.log(`\n共插入 @SchedulerLock ${inserted} 处`);
if (missed.length) {
    console.log('需人工确认:');
    missed.forEach((m) => console.log('  ' + m));
    process.exit(1);
}
