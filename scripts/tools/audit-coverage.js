// scripts/tools/audit-coverage.js —— 操作留痕覆盖检查
//
// 背景（见 docs/13-优化方案.md §P0-2）：
//   m_op_log 曾经 0 行 —— 有查询接口、无写入方。
//   实测全仓 78 个写接口只有 8 个带 @Idempotent；若按「加注解才记录」的思路，
//   退单审批、次卡退卡、会员调级、RBAC 变更全都不留痕，且以后新增接口会继续默认不留痕。
//
// 现改为 opt-out（默认全记）：OpAuditAspect 匹配所有控制器写映射，
// 只有显式 @NoAudit 的接口才跳过。
//
// 本脚本的职责因此变成两件事：
//   1. 列出全部写接口的留痕状态（@OpAudit 中文标签 / 默认留痕 / @NoAudit 豁免）
//   2. 复核豁免清单 —— 防止有人为了让日志「干净点」随手加 @NoAudit 绕过审计
//
// 用法: node scripts/tools/audit-coverage.js [--strict]
//   --strict  豁免清单变化或资金类接口被豁免时退出码 1（可放进 CI）

const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..', '..');
const strict = process.argv.includes('--strict');

/**
 * 基准豁免清单（代码审查过的）
 * 新增任何一条都会被 --strict 判为失败，必须有人确认。
 */
const EXPECTED_EXEMPTIONS = [
    'AuthController#changePassword',
    'AuthController#login',
    'AuthController#logout',
    'AuthController#refresh',
    'AuthController#resetFail',
    'MpAuthController#*',
    'MpMemberController#*',
    'MpRechargeController#*',
    'MpReferralController#*',
    'CashierController#preview',
    'CashierController#cleanExpiredHolds',
    'CouponController#estimate',
    'PaymentController#notify',
    'PaymentController#wechatRefresh',
    'MemberCardController#expireJob'
];

/** 资金/权限类接口不允许被豁免 —— 命中即视为绕过审计 */
const NEVER_EXEMPT = /adjust|refund|recharge|settle|grant|verify|void|leave|issue|approve|sell|freeze|extend|reconcile|recalculate|saveRule|saveConfig|saveMenus|resetPassword|changeLevel|changeStatus|import|export/i;

function walk(dir, out = []) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, e.name);
        if (e.isDirectory()) walk(p, out);
        else if (e.name.endsWith('Controller.java')) out.push(p);
    }
    return out;
}

const rows = [];
for (const file of walk(path.join(root, 'backend', 'src', 'main', 'java'))) {
    const src = fs.readFileSync(file, 'utf8');
    const cls = path.basename(file, '.java');
    const classMapping = (src.match(/@RequestMapping\("([^"]+)"\)/) || [, ''])[1];
    const lines = src.split('\n');
    // 类级豁免：@NoAudit 写在第 0 列（方法级的一定有缩进）
    const classExempt = lines.some((l) => /^@NoAudit\(/.test(l));

    for (let i = 0; i < lines.length; i++) {
        const m = lines[i].match(/@(Post|Put|Delete)Mapping(?:\(\s*(?:"([^"]*)"|value\s*=\s*"([^"]*)")[^)]*\))?/);
        if (!m) continue;
        const httpMethod = m[1].toUpperCase();
        const sub = m[2] ?? m[3] ?? '';

        // 方法签名向下找
        let sigLine = i + 1;
        for (let j = i; j < Math.min(lines.length, i + 12); j++) {
            if (/public\s+[\w<>,\s.\[\]]+\s+\w+\s*\(/.test(lines[j])) { sigLine = j; break; }
        }
        // 注解块 = 映射行上方连续的 @ 行 + 映射行到签名行
        // 不能简单「向上看 N 行」：那会跨过方法边界，把上一个方法的 @NoAudit 算到本方法头上
        // （这是本脚本第一版的真实 bug，导致 settle/grantBatch 被误判为已豁免）
        let up = i - 1;
        while (up >= 0 && /^\s*@/.test(lines[up])) up--;
        const block = lines.slice(up + 1, sigLine + 1).join('\n');

        const methodMatch = lines[sigLine] && lines[sigLine].match(/public\s+[\w<>,\s.\[\]]+\s+(\w+)\s*\(/);
        const method = methodMatch ? methodMatch[1] : '?';

        const methodExempt = /@NoAudit\(/.test(block);
        rows.push({
            cls,
            http: httpMethod,
            path: classMapping + sub,
            method,
            methodExempt,
            classExempt,
            exempt: methodExempt || classExempt,
            labelled: /@OpAudit\(/.test(block),
            idem: /@Idempotent/.test(block),
            sig: `${cls}#${method}`
        });
    }
}

const exempted = rows.filter((r) => r.exempt);
const audited = rows.filter((r) => !r.exempt);
const labelled = rows.filter((r) => !r.exempt && r.labelled);

console.log(`控制器写接口总数: ${rows.length}`);
console.log(`  默认留痕: ${audited.length}（其中 ${labelled.length} 个带中文标签）`);
console.log(`  显式豁免: ${exempted.length}`);
console.log('');

console.log('豁免清单（需人工复核）:');
exempted.forEach((r) => {
    const expected = EXPECTED_EXEMPTIONS.includes(r.sig) || EXPECTED_EXEMPTIONS.includes(`${r.cls}#*`);
    const kind = r.classExempt ? '类级' : '方法级';
    console.log(`  ${expected ? '✓' : '⚠'} [${kind}] ${r.sig.padEnd(34)} ${r.http.padEnd(6)} ${r.path}`);
});

const violations = [];
// 1. 「方法级」豁免落在资金/权限类接口上 → 直接违规（类级豁免的小程序端已整体复核，另见下）
exempted
    .filter((r) => r.methodExempt && (NEVER_EXEMPT.test(r.method) || NEVER_EXEMPT.test(r.path)))
    .forEach((r) => violations.push(`资金/权限类接口被方法级豁免，疑似绕过审计: ${r.sig} ${r.path}`));
// 2. 出现基准清单之外的豁免 → 需要人工确认并登记
exempted
    .filter((r) => !EXPECTED_EXEMPTIONS.includes(r.sig) && !EXPECTED_EXEMPTIONS.includes(`${r.cls}#*`))
    .forEach((r) => violations.push(`新增豁免未登记: ${r.sig}（如确需豁免，请更新本脚本的 EXPECTED_EXEMPTIONS）`));

console.log('');
if (violations.length) {
    console.log('问题:');
    violations.forEach((v) => console.log('  ✗ ' + v));
    if (strict) {
        console.log('\n[FAIL] 操作留痕覆盖检查未通过');
        process.exit(1);
    }
} else {
    console.log('[OK] 所有写接口均已留痕，豁免清单与基准一致');
}
