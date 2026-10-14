// SQL schema checker: validates encoding and structural completeness of V1__init_schema.sql
// Usage: node scripts/tools/check-schema.js
const fs = require('fs');
const path = require('path');

const file = path.join(__dirname, '..', '..', 'sql', 'V1__init_schema.sql');
const buf = fs.readFileSync(file);
const hasBom = buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf;
const s = buf.toString('utf8');

const tables = [...s.matchAll(/CREATE TABLE (?:IF NOT EXISTS )?`([^`]+)`/g)].map((m) => m[1]);
const dups = tables.filter((t, i) => tables.indexOf(t) !== i);
const blocks = s.split(/CREATE TABLE (?:IF NOT EXISTS )?/).slice(1);
const ddlTables = blocks.map((b) => (b.match(/`([^`]+)`/) || [])[1]).filter(Boolean);

let noPk = 0;
let noEngine = 0;
blocks.forEach((b, i) => {
  const name = ddlTables[i] || '(unknown)';
  // 仅校验真正的 CREATE TABLE 块（跳过文件头注释中出现的 "CREATE DATABASE" 等前面的文本）
  if (!/^\s*`/.test(b)) return;
  if (!/PRIMARY KEY/.test(b)) { noPk++; console.log('  [warn] missing PRIMARY KEY:', name); }
  if (!/ENGINE=InnoDB/.test(b)) { noEngine++; console.log('  [warn] missing ENGINE=InnoDB:', name); }
});

const inserts = [...s.matchAll(/INSERT IGNORE INTO `([^`]+)`/g)].map((m) => m[1]);
const unknownTargets = [...new Set(inserts)].filter((t) => !ddlTables.includes(t));

console.log('file         :', path.relative(process.cwd(), file));
console.log('size         :', (buf.length / 1024).toFixed(1), 'KB');
console.log('hasBOM       :', hasBom, '(expect false)');
console.log('hasReplacementChar :', s.includes('\uFFFD'), '(expect false)');
console.log('tableCount   :', tables.length);
console.log('dupTables    :', dups.length ? dups.join(', ') : 'none');
console.log('blocksMissingPk     :', noPk);
console.log('blocksMissingEngine :', noEngine);
console.log('insertIgnoreStmts   :', inserts.length);
console.log('insertTargetsDefined:', unknownTargets.length ? 'NO -> ' + unknownTargets.join(', ') : 'yes');
