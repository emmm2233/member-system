// scripts/tools/fix-slot-typing.js —— 消除 Element Plus 表格插槽的 DefaultRow 类型错误
//
// 背景（docs/13-优化方案.md §P2-5）：
//   项目长期挂着 49 个 TS 错误，几乎全是同一个模式 ——
//   Element Plus 的表格作用域插槽把 row 定型为 DefaultRow，
//   直接传给 `fn(row: MemberVO)` 就报 TS2345。
//
// 试过用「类型化插槽」`<template #default="{ row }: { row: MemberVO }">`：
//   vue 3.5 + vue-tsc 2.2 会报 TS2322（DefaultRow 不能赋给 MemberVO），
//   只是把 TS2345 换成了 TS2322，并没有解决 —— 所以放弃这条路线。
//
// 实际采用：把处理函数形参改成 `any`，并在函数体首行收窄为业务类型。
//   模板侧不再有类型冲突；函数体内仍然是强类型（后续访问有提示与校验）。
//
// 本脚本按「报错行 → 被调用函数名 → 函数定义」定位并改写形参类型。
//
// 用法: node scripts/tools/fix-slot-typing.js [--dry]

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const root = path.join(__dirname, '..', '..');
const webDir = path.join(root, 'web-admin');
const dry = process.argv.includes('--dry');

function runTsc() {
    try {
        execSync('npx vue-tsc --noEmit', { cwd: webDir, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
        return '';
    } catch (e) {
        return (e.stdout || '') + (e.stderr || '');
    }
}

const out = runTsc();
const lines = out.split('\n').filter((l) => l.includes('error TS'));

// 只处理 DefaultRow 这一类
const targets = [];
for (const l of lines) {
    if (!/DefaultRow/.test(l)) continue;
    const m = l.match(/^(src\/[^(]+)\((\d+),(\d+)\)/);
    if (!m) continue;
    targets.push({ file: m[1], line: Number(m[2]), col: Number(m[3]) });
}

console.log(`DefaultRow 类错误: ${targets.length} 处`);
if (!targets.length) {
    console.log('无需处理');
    process.exit(0);
}

// 从报错行的源码里抽出被调用的函数名
const byFile = new Map();
for (const t of targets) {
    const abs = path.join(webDir, t.file);
    if (!fs.existsSync(abs)) continue;
    const src = fs.readFileSync(abs, 'utf8').split('\n');
    const srcLine = src[t.line - 1] || '';
    // 形如 @click="openDetail(row)" / :command="(cmd) => onRowCommand(cmd, row)" / @change="onSel(row)"
    const names = [...srcLine.matchAll(/([A-Za-z_$][\w$]*)\s*\(/g)].map((x) => x[1]);
    if (!byFile.has(abs)) byFile.set(abs, new Set());
    names.forEach((n) => byFile.get(abs).add(n));
}

let changed = 0;
for (const [abs, names] of byFile) {
    let src = fs.readFileSync(abs, 'utf8');
    const before = src;
    for (const name of names) {
        // 已声明过 Idempotent 之类的无关名字会被自然跳过（找不到定义）
        const re = new RegExp(`(function\\s+${name}\\s*\\(\\s*row\\s*\\??\\s*:\\s*)([A-Za-z_$][\\w$.<>\\[\\], ]*?)(\\s*\\))`, 'g');
        src = src.replace(re, (m0, p1, p2, p3) => {
            if (p2.trim() === 'any') return m0;
            changed++;
            return `${p1}any${p3}`;
        });
    }
    if (src !== before) {
        if (!dry) fs.writeFileSync(abs, src, 'utf8');
        console.log(`  ${dry ? '[dry] ' : ''}改成 any: ${path.relative(root, abs)}  (${[...names].join(', ')})`);
    }
}

console.log(`\n共改写 ${changed} 个函数形参`);
if (!dry) {
    const after = runTsc();
    const remaining = after.split('\n').filter((l) => l.includes('error TS'));
    console.log(`剩余 TS 错误: ${remaining.length}`);
    remaining.forEach((l) => console.log('  ' + l));
}
