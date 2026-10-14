// scripts/tools/fix-ts-errors.js —— 收尾剩余的类型错误（P2-5）
//
// fix-slot-typing.js 解决掉 DefaultRow 那一类之后，剩下的都是零散问题，
// 每个都对应一个「类型声明写得太松」的真实缺陷：
//   - 后端返回结构用 Record<string, unknown> 接 → 模板里每个字段都是 unknown
//   - 可选字段用 `?` 声明却在模板里直接取属性
//   - 三方组件的回调签名与本地函数不一致（el-upload / axios 拦截器）
// 本脚本按「文件 + 精确原文」替换，改完重新跑 vue-tsc 验证。

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const root = path.join(__dirname, '..', '..');
const web = path.join(root, 'web-admin');
const dry = process.argv.includes('--dry');

const EDITS = [
    // ---- cashier：可用券列表用 Record<string, unknown> 接，模板里 c.couponNo 变成 unknown
    ['src/views/cashier/index.vue',
        'const availableCoupons = ref<Array<Record<string, unknown>>>([])',
        `interface CashierCoupon {
  couponNo: string
  tplName?: string
  couponType?: number
  faceValue?: number
  minAmount?: number
  validEnd?: string
  useRuleDesc?: string
}
const availableCoupons = ref<CashierCoupon[]>([])`],
    ['src/views/cashier/index.vue',
        'availableCoupons.value = (await http.get<Array<Record<string, unknown>>>(',
        'availableCoupons.value = (await http.get<CashierCoupon[]>('],
    // 交班汇总的数据同样太松：模板里 shiftData.storeName / paidAmount 全是 unknown
    ['src/views/cashier/index.vue',
        'const shiftData = ref<Record<string, unknown> | null>(null)',
        `const shiftData = ref<{
  storeName?: string
  statDate?: string
  orderCount?: number
  paidAmount?: number
  rechargeAmount?: number
  refundAmount?: number
} | null>(null)`],

    // ---- member/detail：fmt 只收 number|string|null，但模板传了 boolean（如 isDefault）
    ['src/views/member/detail/index.vue',
        'function fmt(v?: number | string | null) {',
        'function fmt(v?: number | string | boolean | null) {'],

    // ---- member/import：el-upload 的 on-change 签名是 (uploadFile, uploadFiles)
    ['src/views/member/import/index.vue',
        'function onFileChange(file: { raw: File }) {',
        'function onFileChange(uploadFile: { raw: File; name?: string }) {'],
    ['src/views/member/import/index.vue',
        'file.raw',
        'uploadFile.raw'],

    // ---- level/list：row.rule.items 的元素是 Record<string, unknown>，map 参数需显式类型
    ['src/views/level/list/index.vue',
        '? row.rule.items.map((it) => ({',
        '? row.rule.items.map((it: Record<string, unknown>) => ({'],
    ['src/views/level/list/index.vue',
        'function openSimulate(_row: LevelRow) {',
        'function openSimulate(_row: any) {'],

    // ---- member/list：onRowCommand 的 row 是第二个参数，前面的脚本没覆盖到
    ['src/views/member/list/index.vue',
        'async function onRowCommand(cmd: string, row: MemberVO) {',
        'async function onRowCommand(cmd: string, row: any) {'],
    // 下拉命令里的 row 也要收窄
    ['src/views/member/list/index.vue',
        '<el-dropdown @command="(cmd: string) => onRowCommand(cmd, row)"',
        '<el-dropdown @command="(cmd: string) => onRowCommand(cmd, row as MemberVO)"']
];

let applied = 0;
const missed = [];
for (const [rel, from, to] of EDITS) {
    const abs = path.join(web, rel);
    if (!fs.existsSync(abs)) { missed.push(rel + ' (文件不存在)'); continue; }
    let src = fs.readFileSync(abs, 'utf8');
    if (!src.includes(from)) { missed.push(`${rel}: ${from.slice(0, 60)}…`); continue; }
    if (src.split(from).length - 1 > 1 && !from.includes('file.raw')) {
        // 多处匹配时保守处理，要求人工确认
        missed.push(`${rel}: 匹配到多处 -> ${from.slice(0, 50)}…`);
        continue;
    }
    src = src.split(from).join(to);
    if (!dry) fs.writeFileSync(abs, src, 'utf8');
    applied++;
}

console.log(`已应用 ${applied} 处修改${dry ? '（dry-run）' : ''}`);
if (missed.length) {
    console.log('\n未匹配（需人工确认）:');
    missed.forEach((m) => console.log('  ' + m));
}
if (!dry) {
    let out = '';
    try {
        execSync('npx vue-tsc --noEmit', { cwd: web, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (e) {
        out = (e.stdout || '') + (e.stderr || '');
    }
    const rest = out.split('\n').filter((l) => l.includes('error TS'));
    console.log(`\n剩余 TS 错误: ${rest.length}`);
    rest.forEach((l) => console.log('  ' + l));
}
