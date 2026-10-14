// Fix PowerShell 5.1 parsing pitfalls in round2-test.ps1:
// - single-quoted string containing ":" right after a function name confuses the parser
// - "&" inside single-quoted strings is treated as the call operator
// Usage: node scripts/tools/fix-test-script.js
const fs = require('fs');
const path = require('path');

const file = path.join(__dirname, '..', 'round2-test.ps1');
let s = fs.readFileSync(file, 'utf8');

const replacements = [
  // 冒号后用双引号包裹整段
  [
    "$recalc = PostR '/api/admin/report/levels/recalculate?batchSize=100' $null $H $null",
    '$recalc = PostR "/api/admin/report/levels/recalculate?batchSize=100" $null $H $null'
  ],
  ["Write-Host '=== 4. dashboard & reports ===' -ForegroundColor Cyan", 'Write-Host "=== 4. dashboard and reports ===" -ForegroundColor Cyan'],
  [
    "$sleep = GetR '/api/admin/report/dashboard/sleeping-members?days=0&limit=10' $H",
    '$sleep = GetR "/api/admin/report/dashboard/sleeping-members?days=0&limit=10" $H'
  ],
  ["Check 'reconcile after card & level ops'", "Check 'reconcile after card and level ops'"]
];

for (const [from, to] of replacements) {
  if (s.includes(from)) {
    s = s.replace(from, to);
    console.log('fixed:', from.slice(0, 60));
  } else {
    console.log('not found (ok if already fixed):', from.slice(0, 60));
  }
}

fs.writeFileSync(file, s, 'utf8');
console.log('written:', path.basename(file));
