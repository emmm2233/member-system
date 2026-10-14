// Make round4-test.ps1 fully ASCII + BOM + LF (PowerShell 5.1 reads BOM-less UTF-8 as GBK).
// Usage: node scripts/tools/ascii-fix.js <file>
const fs = require('fs');
const path = require('path');

const targets = process.argv.slice(2);
if (!targets.length) {
    console.error('usage: node scripts/tools/ascii-fix.js <ps1 file> [more files]');
    process.exit(1);
}

for (const rel of targets) {
    const file = path.isAbsolute(rel) ? rel : path.join(__dirname, '..', '..', rel);
    let s = fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '');

    // 常见中文字面量 -> ASCII（测试脚本里仅用于展示，不影响断言）
    const dict = [
        [/'满 50 减 10'/g, "'R4 cut 10 off 50'"],
        [/'满 50 减 12'/g, "'R4 cut 12 off 50'"],
        [/'单笔满 50 元可用'/g, "'min 50 per order'"],
        [/'invalid'/g, "'invalid'"],
        [/'R4 钻石\+'/g, "'R4 Diamond Plus'"],
        [/'R4 测试角色'/g, "'R4 Test Role'"],
        [/'R4 Cashier'/g, "'R4 Cashier'"],
        [/'round4 targeted grant'/g, "'round4 targeted grant'"],
        [/'round4 condition grant'/g, "'round4 condition grant'"],
        [/'r4 all'/g, "'r4 all'"],
        [/'r4 limit'/g, "'r4 limit'"],
        [/'round4'/g, "'round4'"]
    ];
    for (const [from, to] of dict) {
        s = s.replace(from, to);
    }

    // 去掉 & 之类的 shell 元字符（脚本内 URL 用双引号包裹更稳）
    s = s.replace(/(GetR|PostR|PutR|Req\s+'[A-Z]+')\s+'([^']*&[^']*)'/g, (m, fn, url) => `${fn} "${url}"`);

    // 规范为 BOM + LF
    s = s.replace(/\r\n/g, '\n');
    fs.writeFileSync(file, '\uFEFF' + s, 'utf8');

    // 报告残留非 ASCII
    const leftovers = [];
    s.split('\n').forEach((line, i) => {
        for (const ch of line) {
            if (ch.charCodeAt(0) > 127) {
                leftovers.push({ line: i + 1, ch, code: ch.charCodeAt(0).toString(16) });
                break;
            }
        }
    });
    console.log(path.basename(file), '| non-ASCII lines:', leftovers.length,
        leftovers.length ? '-> first at line ' + leftovers[0].line + ' (U+' + leftovers[0].code + ')' : '');
}
