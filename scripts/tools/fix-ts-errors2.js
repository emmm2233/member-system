// scripts/tools/fix-ts-errors2.js —— 收尾最后一批类型错误（P2-5 第二轮）
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const root = path.join(__dirname, '..', '..');
const web = path.join(root, 'web-admin');
const dry = process.argv.includes('--dry');

const EDITS = [
    // cashier：交班汇总实际还有 payMethodDetail 字段（模板里用到了）
    ['src/views/cashier/index.vue',
        `  paidAmount?: number
  rechargeAmount?: number
  refundAmount?: number
} | null>(null)`,
        `  paidAmount?: number
  rechargeAmount?: number
  refundAmount?: number
  payMethodDetail?: Array<{ payMethod?: number; payMethodText?: string; orderCount?: number; amount?: number }>
} | null>(null)`],
    // cashier：接口返回声明与本地接口不一致，统一成 CashierCoupon[]
    ['src/views/cashier/index.vue',
        `    availableCoupons.value = (await http.get<CashierCoupon[]>(`,
        `    availableCoupons.value = (await http.get<CashierCoupon[]>(`],

    // grant：form.condition 声明为可选，但模板里无条件访问 —— 初始化时就必须给默认值
    ['src/views/marketing/grant/index.vue',
        ' const c = form.condition',
        ' const c = form.condition!'],
    // grant：模板里 form.condition.xxx 全部改成非空断言（表单初始化已保证存在）
    ['src/views/marketing/grant/index.vue', 'v-model="form.condition.levelIds"', 'v-model="form.condition!.levelIds"'],
    ['src/views/marketing/grant/index.vue', 'v-model="form.condition.tagIds"', 'v-model="form.condition!.tagIds"'],
    ['src/views/marketing/grant/index.vue', 'v-model="form.condition.minTotalConsume"', 'v-model="form.condition!.minTotalConsume"'],
    ['src/views/marketing/grant/index.vue', 'v-model="form.condition.minBalance"', 'v-model="form.condition!.minBalance"'],

    // member/import：el-upload 回调签名要求两个参数
    ['src/views/member/import/index.vue',
        'function onFileChange(uploadFile: { raw: File; name?: string }) {',
        'function onFileChange(uploadFile: { raw: File; name?: string }, _uploadFiles?: unknown[]) {']
];

let applied = 0;
const missed = [];
for (const [rel, from, to] of EDITS) {
    const abs = path.join(web, rel);
    if (!fs.existsSync(abs)) { missed.push(rel + ' (文件不存在)'); continue; }
    let src = fs.readFileSync(abs, 'utf8');
    if (!src.includes(from)) { missed.push(`${rel}: ${from.slice(0, 70)}…`); continue; }
    src = src.split(from).join(to);
    if (!dry) fs.writeFileSync(abs, src, 'utf8');
    applied++;
}
console.log(`已应用 ${applied} 处${dry ? '（dry-run）' : ''}`);
if (missed.length) { console.log('未匹配:'); missed.forEach((m) => console.log('  ' + m)); }
if (!dry) {
    let out = '';
    try { execSync('npx vue-tsc --noEmit', { cwd: web, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }); }
    catch (e) { out = (e.stdout || '') + (e.stderr || ''); }
    const rest = out.split('\n').filter((l) => l.includes('error TS'));
    console.log(`\n剩余 TS 错误: ${rest.length}`);
    rest.forEach((l) => console.log('  ' + l));
}
