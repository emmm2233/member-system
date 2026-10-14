// Make round2-test.ps1 fully ASCII (PowerShell 5.1 reads BOM-less UTF-8 as GBK,
// so any CJK literal inside the script can break parsing).
// Also writes the file back as UTF-8 with BOM so both PS 5.1 and PS 7 parse it identically.
// Usage: node scripts/tools/make-test-ascii.js
const fs = require('fs');
const path = require('path');

const file = path.join(__dirname, '..', 'round2-test.ps1');
let s = fs.readFileSync(file, 'utf8');

// 1. CJK 断言改为 ASCII 判断（通过 code 字段而不是文案）
const fixes = [
  [
    "Check 'member level persisted' ($prof.data.levelName -eq 'Gold' -or $prof.data.levelName -match 'Gold|金') (\"levelName=\" + $prof.data.levelName)",
    "Check 'member level persisted' ($prof.data.levelId -eq 3) (\"levelId=\" + $prof.data.levelId)"
  ]
];

for (const [from, to] of fixes) {
  if (s.includes(from)) {
    s = s.replace(from, to);
    console.log('replaced CJK assertion with ASCII check');
  }
}

// 2. 报告仍存在的非 ASCII 字符位置，便于排查
const nonAscii = [];
s.split('\n').forEach((line, i) => {
  for (const ch of line) {
    if (ch.charCodeAt(0) > 127) {
      nonAscii.push({ line: i + 1, ch, code: ch.charCodeAt(0).toString(16) });
      break;
    }
  }
});
if (nonAscii.length) {
  console.log('remaining non-ASCII lines:', nonAscii.length);
  nonAscii.slice(0, 20).forEach((x) => console.log('  line ' + x.line + ' -> U+' + x.code));
} else {
  console.log('file is fully ASCII');
}

// 3. 写回带 BOM 的 UTF-8（PS 5.1 与 PS 7 都能正确解析）
fs.writeFileSync(file, '\uFEFF' + s, 'utf8');
console.log('written with BOM:', path.basename(file));
