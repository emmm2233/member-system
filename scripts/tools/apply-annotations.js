// scripts/tools/apply-annotations.js —— 一次性批量为控制器方法插入 @OpAudit / @NoAudit
//
// 为什么用脚本而不是手改 21 个文件：注解要插在 @PostMapping/@PutMapping/@DeleteMapping
// 之后、public 方法签名之前，手工操作容易插错位置或插漏。
// 脚本按「文件 + 方法名」精确定位，插完立即做语法自检（花括号配对 + 注解位置）。
//
// 用法: node scripts/tools/apply-annotations.js [--dry]

const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..', '..');
const dry = process.argv.includes('--dry');

// 需要「显式中文标签」的接口（默认已审计，这里只是让日志可读）
const AUDIT = [
    ['modules/account/controller/RechargeController.java', 'recharge', '充值办理', 'RECHARGE'],
    ['modules/account/controller/RechargeController.java', 'revoke', '充值撤销', 'REFUND'],
    ['modules/cashier/controller/CashierController.java', 'applyRefund', '退单申请', 'REFUND'],
    ['modules/cashier/controller/CashierController.java', 'approve', '退单审批', 'AUDIT'],
    ['modules/cashier/controller/CashierController.java', 'hold', '挂单', 'CREATE'],
    ['modules/cashier/controller/CashierController.java', 'resumeHold', '取单', 'UPDATE'],
    ['modules/cashier/controller/CashierController.java', 'cancelHold', '弃单', 'DELETE'],
    ['modules/card/controller/MemberCardController.java', 'sell', '次卡售卡', 'SALE'],
    ['modules/card/controller/MemberCardController.java', 'verify', '次卡核销', 'VERIFY'],
    ['modules/card/controller/MemberCardController.java', 'refund', '次卡退卡', 'REFUND'],
    ['modules/card/controller/MemberCardController.java', 'freeze', '次卡冻结解冻', 'UPDATE'],
    ['modules/card/controller/MemberCardController.java', 'extend', '次卡延期', 'UPDATE'],
    ['modules/marketing/controller/CouponController.java', 'grant', '手工发券', 'GRANT'],
    ['modules/marketing/controller/CouponController.java', 'grantBatch', '批量发券', 'GRANT'],
    ['modules/marketing/controller/CouponController.java', 'verify', '券核销', 'VERIFY'],
    ['modules/marketing/controller/CouponController.java', 'voidCoupon', '券作废', 'DELETE'],
    ['modules/member/controller/MemberController.java', 'create', '会员建档', 'CREATE'],
    ['modules/member/controller/MemberController.java', 'update', '会员资料修改', 'UPDATE'],
    ['modules/member/controller/MemberController.java', 'changeStatus', '会员状态变更', 'UPDATE'],
    ['modules/member/controller/MemberController.java', 'changeLevel', '会员调级', 'UPDATE'],
    ['modules/member/controller/MemberController.java', 'importMembers', '会员导入', 'IMPORT'],
    ['modules/member/controller/MemberController.java', 'export', '会员数据导出', 'EXPORT'],
    ['modules/report/controller/DashboardController.java', 'recalculate', '等级批量重算', 'UPDATE'],
    ['modules/report/controller/DashboardController.java', 'saveRule', '等级规则修改', 'UPDATE'],
    ['modules/referral/controller/ReferralController.java', 'manualSettle', '推荐人工结算', 'AUDIT'],
    ['modules/referral/controller/ReferralController.java', 'invalidate', '推荐关系作废', 'UPDATE'],
    ['modules/referral/controller/ReferralController.java', 'saveConfig', '推荐规则修改', 'UPDATE'],
    ['modules/birthday/controller/BirthdayController.java', 'issue', '生日礼手工发放', 'GRANT'],
    ['modules/birthday/controller/BirthdayController.java', 'saveConfig', '生日礼规则修改', 'UPDATE'],
    ['modules/staff/controller/StaffController.java', 'leave', '员工离职', 'UPDATE'],
    ['modules/staff/controller/StaffController.java', 'reinstate', '员工复职', 'UPDATE'],
    ['modules/staff/controller/StaffController.java', 'bindAccount', '员工关联账号', 'UPDATE'],
    ['modules/staff/controller/StaffController.java', 'unbindAccount', '员工解绑账号', 'UPDATE'],
    ['modules/store/controller/StoreController.java', 'changeStatus', '门店停业营业', 'UPDATE'],
    ['modules/system/controller/SystemController.java', 'resetPassword', '重置用户密码', 'UPDATE'],
    ['modules/system/controller/SystemController.java', 'saveRoleMenus', '角色菜单授权', 'UPDATE'],
    ['modules/system/controller/SystemConfigController.java', 'update', '系统参数修改', 'UPDATE'],
    ['modules/message/controller/MessageController.java', 'resend', '消息重发', 'RETRY'],
    ['modules/message/controller/MessageController.java', 'updateTpl', '消息模板修改', 'UPDATE']
];

// 显式豁免（理由必填）
const NO_AUDIT_METHOD = [
    ['modules/cashier/controller/CashierController.java', 'preview', '开单试算：纯计算无副作用，不产生任何业务变更'],
    ['modules/cashier/controller/CashierController.java', 'cleanExpiredHolds', '运维触发清理超时挂单：无人工操作语义'],
    ['modules/marketing/controller/CouponController.java', 'estimate', '发券人数预估：纯查询无副作用'],
    ['modules/payment/controller/PaymentController.java', 'notify', '渠道回调：原文与验签结果已单独落 m_payment_notify_log'],
    ['modules/payment/controller/PaymentController.java', 'wechatRefresh', '刷新微信支付派生缓存：无业务数据变更'],
    ['modules/card/controller/MemberCardController.java', 'expireJob', '运维手动触发次卡过期任务：无人工操作语义']
];

// 类级豁免（整个控制器）
const NO_AUDIT_CLASS = [
    ['mp/controller/MpAuthController.java', '会员端认证：登录/绑手机属于会员自助行为，已由 m_login_log 记录'],
    ['mp/controller/MpMemberController.java', '会员端自助查询与核销码：非管理操作，资产变动另有流水可查'],
    ['mp/controller/MpRechargeController.java', '会员端在线充值下单：资金结果由支付单与资产流水完整记录'],
    ['mp/controller/MpReferralController.java', '会员端推荐/生日/消息自助：非管理操作'],
    ['controller/AuthController.java', '认证类接口：登录/登出/刷新/改密/清计数，已由 m_login_log 覆盖，且参数含口令不宜留档']
];

function findFile(suffix) {
    const stack = [path.join(root, 'backend', 'src', 'main', 'java')];
    while (stack.length) {
        const dir = stack.pop();
        for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
            const p = path.join(dir, e.name);
            if (e.isDirectory()) stack.push(p);
            else if (p.replace(/\\/g, '/').endsWith(suffix)) return p;
        }
    }
    return null;
}

let auditDone = 0;
let noAuditDone = 0;
let classDone = 0;
const problems = [];

/** 在该方法签名前插入注解行 */
function insertBeforeMethod(file, methodName, annotationLines) {
    const src = fs.readFileSync(file, 'utf8');
    const lines = src.split('\n');
    const sigRe = new RegExp('^\\s*public\\s+[\\w<>,.\\[\\]\\s]+\\s+' + methodName + '\\s*\\(');
    const idx = lines.findIndex((l) => sigRe.test(l));
    if (idx < 0) {
        problems.push(`${path.basename(file)}: 找不到方法 ${methodName}`);
        return false;
    }
    // 已存在则不重复插
    const window = lines.slice(Math.max(0, idx - 8), idx).join('\n');
    if (annotationLines.some((a) => window.includes(a.trim().split('(')[0]))) {
        return 'skip';
    }
    lines.splice(idx, 0, ...annotationLines);
    fs.writeFileSync(file, lines.join('\n'), 'utf8');
    return true;
}

function insertBeforeClass(file, annotationLines) {
    const src = fs.readFileSync(file, 'utf8');
    if (annotationLines.some((a) => src.includes(a.trim().split('(')[0]))) return 'skip';
    const lines = src.split('\n');
    const idx = lines.findIndex((l) => /^public\s+class\s+\w+/.test(l));
    if (idx < 0) {
        problems.push(`${path.basename(file)}: 找不到类声明`);
        return false;
    }
    // 插到类 javadoc / 其它类注解之后、class 行之前
    lines.splice(idx, 0, ...annotationLines);
    fs.writeFileSync(file, lines.join('\n'), 'utf8');
    return true;
}

function ensureImport(file, importLine, anchor) {
    const src = fs.readFileSync(file, 'utf8');
    if (src.includes(importLine)) return;
    const lines = src.split('\n');
    const idx = lines.findIndex((l) => l.startsWith(anchor));
    if (idx < 0) {
        problems.push(`${path.basename(file)}: 找不到 import 锚点 ${anchor}`);
        return;
    }
    lines.splice(idx, 0, importLine);
    fs.writeFileSync(file, lines.join('\n'), 'utf8');
}

if (dry) {
    console.log(`[dry] 将插入 @OpAudit ${AUDIT.length} 处、@NoAudit(方法) ${NO_AUDIT_METHOD.length} 处、@NoAudit(类) ${NO_AUDIT_CLASS.length} 处`);
    process.exit(0);
}

for (const [suffix, method, desc, opType] of AUDIT) {
    const file = findFile(suffix);
    if (!file) { problems.push(`找不到文件 ${suffix}`); continue; }
    ensureImport(file, 'import com.company.member.common.annotation.OpAudit;', 'import com.company.member.common.');
    const r = insertBeforeMethod(file, method, [`    @OpAudit(opType = "${opType}", desc = "${desc}")`]);
    if (r === true) auditDone++;
}

for (const [suffix, method, reason] of NO_AUDIT_METHOD) {
    const file = findFile(suffix);
    if (!file) { problems.push(`找不到文件 ${suffix}`); continue; }
    ensureImport(file, 'import com.company.member.common.annotation.NoAudit;', 'import com.company.member.common.');
    const r = insertBeforeMethod(file, method, [`    @NoAudit("${reason}")`]);
    if (r === true) noAuditDone++;
}

for (const [suffix, reason] of NO_AUDIT_CLASS) {
    const file = findFile(suffix);
    if (!file) { problems.push(`找不到文件 ${suffix}`); continue; }
    ensureImport(file, 'import com.company.member.common.annotation.NoAudit;', 'import com.company.member.common.');
    const r = insertBeforeClass(file, [`@NoAudit("${reason}")`]);
    if (r === true) classDone++;
}

console.log(`@OpAudit 插入 ${auditDone} 处`);
console.log(`@NoAudit(方法) 插入 ${noAuditDone} 处`);
console.log(`@NoAudit(类) 插入 ${classDone} 处`);
if (problems.length) {
    console.log('\n问题:');
    problems.forEach((p) => console.log('  ' + p));
    process.exit(1);
}
