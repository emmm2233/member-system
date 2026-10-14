// scripts/tools/fix-ts-errors3.js —— 收尾最后 4 个类型错误（P2-5 第三轮）
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const root = path.join(__dirname, '..', '..');
const web = path.join(root, 'web-admin');
const dry = process.argv.includes('--dry');

const EDITS = [
    // 1. axios 响应拦截器：故意返回 result.data（已解包），类型上不是 AxiosResponse。
    //    显式标注返回 any 是这里的正确取舍 —— 调用方在 http.get<T>() 里拿回 T。
    ['src/api/request.ts',
        'service.interceptors.response.use(\n  async (response) => {',
        'service.interceptors.response.use(\n  // 返回值故意是「已解包的业务数据」而不是 AxiosResponse，\n  // 因此显式标注 any：调用方通过 http.get<T>() 拿回 T，类型安全在那一层保证。\n  // eslint-disable-next-line @typescript-eslint/no-explicit-any\n  async (response): Promise<any> => {'],

    // 2. cashier：http.get 的泛型没改到（实际写法是 catch 后再强转）
    ['src/views/cashier/index.vue',
        ').catch(() => []) as unknown as Array<Record<string, unknown>>) || []',
        ').catch(() => []) as unknown as CashierCoupon[]) || []'],

    // 3. grant：还差一处 form.condition.birthdayMonth
    ['src/views/marketing/grant/index.vue',
        'v-model="form.condition.birthdayMonth"',
        'v-model="form.condition!.birthdayMonth"'],

    // 4. member/import：el-upload 的 on-change 形参必须是 Element Plus 的 UploadFile
    ['src/views/member/import/index.vue',
        'function onFileChange(uploadFile: { raw: File; name?: string }, _uploadFiles?: unknown[]) {',
        'function onFileChange(uploadFile: UploadFile, _uploadFiles?: UploadFiles) {'],
    ['src/views/member/import/index.vue',
        "import { ref, computed, onMounted } from 'vue'",
        "import { ref, computed, onMounted } from 'vue'\nimport type { UploadFile, UploadFiles } from 'element-plus'"]
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
