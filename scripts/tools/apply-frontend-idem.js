// scripts/tools/apply-frontend-idem.js —— 把资金/审计相关的前端调用统一改为 postIdem
// 一次性脚本：PostMapping 上补了 @Idempotent 之后，前端必须真的带上 X-Idempotency-Key，
// 否则注解形同虚设（这正是 P0-1 的成因：前端发了、后端没接；反过来同样致命）。

const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..', '..');
const files = [
    path.join(root, 'web-admin', 'src', 'api', 'admin.ts'),
    path.join(root, 'web-admin', 'src', 'api', 'index.ts')
];

// 每个条目：文件 + 唯一锚点片段（含 `http.post` 调用）→ 把该片段里的 http.post 换成 http.postIdem
const TARGETS = [
    ['admin.ts', "http.post<GrantResult>('/admin/marketing/coupons/grant-batch', data)"],
    ['admin.ts', 'http.post<void>(`/admin/marketing/coupons/${couponNo}/void?reason=${encodeURIComponent(reason)}`)'],
    ['admin.ts', 'http.post<Record<string, unknown>>(\n      `/admin/marketing/coupons/verify?couponNo=${couponNo}${storeId ? \'&storeId=\' + storeId : \'\'}`\n    )'],
    ['admin.ts', 'http.post<void>(`/referral/relations/${id}/settle`, { reason: reason || \'\' })'],
    ['admin.ts', 'http.post<void>(`/referral/relations/${id}/invalidate`, { reason: reason || \'\' })'],
    ['admin.ts', 'http.post<Record<string, unknown>>(\'/birthday/issue\', { memberId, year, force })'],
    ['admin.ts', 'http.post<void>(`/message/records/${id}/resend`)'],
    ['admin.ts', 'http.post<{ tip: string; accountActive: boolean; accountUsername?: string }>(`/admin/staff/${id}/reinstate`)'],
    ['index.ts', 'http.post<void>(\n      `/admin/orders/refunds/${refundNo}/approve?pass=${pass}&remark=${encodeURIComponent(remark || \'\')}`\n    )']
];

let done = 0;
const missed = [];

for (const [name, anchor] of TARGETS) {
    const file = files.find((f) => f.endsWith(name));
    let src = fs.readFileSync(file, 'utf8');
    if (!src.includes(anchor)) { missed.push(`${name}: ${anchor.slice(0, 60)}…`); continue; }
    // 只替换该锚点内的 http.post（锚点本身唯一，且不含其它 http.post）
    const replaced = anchor.replace('http.post', 'http.postIdem');
    src = src.replace(anchor, replaced);
    fs.writeFileSync(file, src, 'utf8');
    done++;
}

console.log(`已改为 postIdem: ${done} 处`);
if (missed.length) {
    console.log('\n未匹配（需手工确认）:');
    missed.forEach((m) => console.log('  ' + m));
}
