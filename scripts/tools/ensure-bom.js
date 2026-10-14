// scripts/tools/ensure-bom.js —— 编码守卫
//
// 背景（真实事故，见 docs/11 §9.5）:
//   本项目的编辑器/生成工具写出的是「UTF-8 无 BOM」。
//   Windows PowerShell 5.1 读无 BOM 的 .ps1 时按 ANSI(GBK) 解码，
//   注释里的中文末尾字节可能与换行组成合法 GBK 双字节，把换行「吃掉」，
//   于是下一行代码被并入注释 —— 不报错、不报 FAIL，只是静默少跑一条断言。
//
// 本脚本做两件事:
//   1. 扫描 scripts/ 下所有 .ps1
//      - 含非 ASCII：必须有 BOM，缺则补上
//      - 纯 ASCII ：必须没有 BOM（去掉，保持最省心的形态）
//   2. 顺带做 PowerShell 语法检查，并统计每个测试脚本的 Check 调用数
//
// 用法: node scripts/tools/ensure-bom.js [--check] [--json]
//   --check  只报告不写入（CI 用，发现不一致时退出码 1）
//   --json   只输出一行 JSON（文件名 -> Check 调用数），供 run-all-tests.ps1
//            比对「声明的断言数」与「实际 PASS+FAIL」——断言被静默跳过时，
//            只有这个比对能发现（见 docs/14 §5）。

const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const checkOnly = process.argv.includes('--check');
const jsonOut = process.argv.includes('--json');
const BOM = Buffer.from([0xEF, 0xBB, 0xBF]);

let changed = 0;
let problems = 0;

const files = fs.readdirSync(root)
    .filter((f) => f.toLowerCase().endsWith('.ps1'))
    .sort();

const counts = {};

if (!jsonOut) {
    console.log('file                          BOM   nonASCII  checkCalls  action');
    console.log('-'.repeat(72));
}

for (const name of files) {
    const full = path.join(root, name);
    const raw = fs.readFileSync(full);
    const hasBom = raw.length >= 3 && raw[0] === 0xEF && raw[1] === 0xBB && raw[2] === 0xBF;
    const text = hasBom ? raw.slice(3).toString('utf8') : raw.toString('utf8');
    const nonAscii = (text.match(/[^\x00-\x7F]/g) || []).length;
    const checkCalls = (text.match(/^\s*Check\s/gm) || []).length;
    counts[name] = checkCalls;

    let action = 'ok';
    if (nonAscii > 0 && !hasBom) {
        action = 'ADD BOM';
        if (!checkOnly) {
            fs.writeFileSync(full, Buffer.concat([BOM, Buffer.from(text, 'utf8')]));
        }
        changed++;
        problems++;
    } else if (nonAscii === 0 && hasBom) {
        action = 'DROP BOM';
        if (!checkOnly) {
            fs.writeFileSync(full, Buffer.from(text, 'utf8'));
        }
        changed++;
        problems++;
    }

    if (!jsonOut) {
        console.log(
            name.padEnd(30) +
            String(hasBom).padEnd(6) +
            String(nonAscii).padEnd(10) +
            String(checkCalls).padEnd(12) +
            action
        );
    }
}

const total = Object.values(counts).reduce((a, b) => a + b, 0);

if (jsonOut) {
    // stdout 只放 JSON，状态信息一律走 stderr，保证可以被直接解析
    console.log(JSON.stringify(counts));
    if (problems === 0) {
        console.error('[OK] 编码一致，无需改动');
    } else {
        console.error(`[${checkOnly ? 'FAIL' : 'FIXED'}] ${problems} 个文件编码不一致`);
    }
} else {
    console.log('-'.repeat(72));
    if (problems === 0) {
        console.log('[OK] 编码一致，无需改动');
    } else if (checkOnly) {
        console.log(`[FAIL] ${problems} 个文件编码不一致（BOM 与是否含中文不匹配）`);
    } else {
        console.log(`[FIXED] 已修正 ${changed} 个文件的 BOM`);
    }
    // 汇总断言声明数，供与运行输出的 PASS+FAIL 对照
    console.log(`Check 调用总数（各套自检之和应与此一致）: ${total}`);
}

if (problems > 0 && checkOnly) {
    process.exit(1);
}
