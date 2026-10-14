// Reprocess the schema file using Node only (Windows PowerShell mangles UTF-8).
// 1. Report integrity of the Flyway copy (source of truth).
// 2. Verify/adjust the recharge-plan seed rows to include gift coupons.
// 3. Write a clean UTF-8 (no BOM) file back to both sql/ and db/migration/.
// Usage: node scripts/tools/normalize-sql.js [--check]
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..', '..');
const srcPath = path.join(root, 'backend', 'src', 'main', 'resources', 'db', 'migration', 'V1__init_schema.sql');
const outPath = path.join(root, 'sql', 'V1__init_schema.sql');
const checkOnly = process.argv.includes('--check');

let s = fs.readFileSync(srcPath, 'utf8').replace(/^\uFEFF/, '');

const report = (label, text) => {
    const tables = [...text.matchAll(/CREATE TABLE (?:IF NOT EXISTS )?`([^`]+)`/g)].map((m) => m[1]);
    const dup = tables.filter((t, i) => tables.indexOf(t) !== i);
    console.log('[' + label + ']');
    console.log('  bytes            :', Buffer.byteLength(text, 'utf8'));
    console.log('  tables           :', tables.length, dup.length ? '(DUP: ' + dup.join(',') + ')' : '(no dup)');
    console.log('  mojibake detected:', /\u6d93|\u714e|\u5a08|\u9359|\u951b|\u5a04/.test(text) ? 'YES (corrupted)' : 'no');
    console.log('  chinese chars    :', (text.match(/[\u4e00-\u9fa5]/g) || []).length);
};

console.log('=== before ===');
report('flyway copy', s);

// 充值方案补赠券（若尚未设置）
// 注意：必须同时补上列名 gift_coupon_tpl_ids，否则列数与值数不匹配
const planCols = '(`id`,`plan_name`,`recharge_amount`,`gift_amount`,`gift_points`,`status`,`sort_no`,`remark`,`created_at`,`updated_at`)';
const planColsNew = '(`id`,`plan_name`,`recharge_amount`,`gift_amount`,`gift_points`,`gift_coupon_tpl_ids`,`status`,`sort_no`,`remark`,`created_at`,`updated_at`)';
if (s.includes(planCols + ' VALUES')) {
    s = s.replace(planCols + ' VALUES', planColsNew + ' VALUES');
    console.log('plan columns: gift_coupon_tpl_ids added to column list');
}

const planFixes = [
    { id: 2, from: "'\u5145 500 \u9001 50',500.00,50.00,200,1,2,", to: "'\u5145 500 \u9001 50',500.00,50.00,200,'2:1',1,2," },
    { id: 3, from: "'\u5145 1000 \u9001 150',1000.00,150.00,500,1,3,", to: "'\u5145 1000 \u9001 150',1000.00,150.00,500,'2:1,1:1',1,3," },
    { id: 1, from: "'\u5145 200 \u9001 10',200.00,10.00,0,1,1,", to: "'\u5145 200 \u9001 10',200.00,10.00,0,NULL,1,1," },
    { id: 4, from: "'\u5145 2000 \u9001 400',2000.00,400.00,1500,1,4,", to: "'\u5145 2000 \u9001 400',2000.00,400.00,1500,NULL,1,4," },
];
for (const fix of planFixes) {
    if (s.includes(fix.to)) {
        console.log('plan', fix.id, ': already has gift_coupon_tpl_ids');
    } else if (s.includes(fix.from)) {
        s = s.replace(fix.from, fix.to);
        console.log('plan', fix.id, ': gift_coupon_tpl_ids added');
    } else {
        console.log('plan', fix.id, ': pattern NOT FOUND (manual check needed)');
    }
}

console.log('=== after ===');
report('normalized', s);

// 列数与值数一致性快速校验（针对充值方案这一条 INSERT）
// 注意：值中可能包含单引号内的逗号（如 '2:1,1:1'），必须按引号感知的方式切分
function splitTopLevel(text) {
    const out = [];
    let cur = '';
    let inQuote = false;
    for (const ch of text) {
        if (ch === "'") inQuote = !inQuote;
        if (ch === ',' && !inQuote) {
            out.push(cur);
            cur = '';
        } else {
            cur += ch;
        }
    }
    out.push(cur);
    return out;
}

const insertMatch = s.match(/INSERT IGNORE INTO `m_recharge_plan`\s*\(([^)]*)\)\s*VALUES([\s\S]*?);/);
if (insertMatch) {
    const colCount = insertMatch[1].split(',').length;
    const rows = insertMatch[2].split(/\),\s*\(/).map((r) => r.replace(/^\s*\(|\)\s*$/g, ''));
    const counts = rows.map((r) => splitTopLevel(r).length);
    const bad = counts.filter((c) => c !== colCount).length;
    console.log('plan insert columns:', colCount, '| rows:', rows.length,
        '| row value counts:', counts.join(','), '| mismatched:', bad);
}

if (checkOnly) {
    process.exit(0);
}

// 写回 Flyway 目录与 sql 目录，均为无 BOM 的 UTF-8
fs.writeFileSync(srcPath, s, 'utf8');
fs.writeFileSync(outPath, s, 'utf8');
console.log('written:');
console.log('  ' + path.relative(root, srcPath));
console.log('  ' + path.relative(root, outPath));
