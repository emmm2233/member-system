// Inspect and repair the SQL seed file: report BOM, size, key rows, and search for damage.
// Usage: node scripts/tools/inspect-sql.js [--fix]
const fs = require('fs');
const path = require('path');

const file = path.join(__dirname, '..', '..', 'sql', 'V1__init_schema.sql');
const buf = fs.readFileSync(file);
const hasBom = buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf;
let s = buf.toString('utf8');
if (hasBom) s = s.slice(1);

const tables = [...s.matchAll(/CREATE TABLE (?:IF NOT EXISTS )?`([^`]+)`/g)].map((m) => m[1]);
const dupTables = tables.filter((t, i) => tables.indexOf(t) !== i);
const crlf = (s.match(/\r\n/g) || []).length;
const lf = (s.match(/(?<!\r)\n/g) || []).length;

console.log('file           :', path.relative(process.cwd(), file));
console.log('size           :', buf.length, 'bytes');
console.log('hasBOM         :', hasBom, '(expect false)');
console.log('replacementChar:', s.includes('\uFFFD'), '(expect false)');
console.log('tableCount     :', tables.length, '(expect 47)');
console.log('dupTables      :', dupTables.length ? dupTables.join(',') : 'none');
console.log('lineEndings    : CRLF=' + crlf + ' bareLF=' + lf, '(CRLF is fine)');
console.log('endsWithSemi   :', s.trimEnd().endsWith(';'), '(expect true)');

// 关键种子数据检查
const checks = [
    ['recharge plan 2 with coupon', '\u5145 500 \u9001 50', '2:1'],
    ['recharge plan 3 with coupon', '\u5145 1000 \u9001 150', '2:1,1:1'],
    ['admin password hash', 'bXtmQmfwbMGO6BN5', null],
    ['menu seed present', 'm_sys_menu', null],
];
for (const [label, needle, mustContain] of checks) {
    const idx = s.indexOf(needle);
    let ok = idx >= 0;
    if (ok && mustContain) {
        ok = s.slice(idx, idx + 240).includes(mustContain);
    }
    console.log('seed check     :', label, ok ? 'OK' : 'MISSING');
}

// 重复段落检测（同一表出现两次 CREATE TABLE 的情况已在 dupTables 处理；
// 这里额外检查 seed 段是否被重复插入）
const adminInsertCount = (s.match(/INSERT IGNORE INTO `m_sys_user`/g) || []).length;
console.log('adminSeedCount :', adminInsertCount, '(expect 1)');

if (process.argv.includes('--fix') && hasBom) {
    fs.writeFileSync(file, s, 'utf8');
    console.log('FIXED          : removed BOM');
}
