import { http, tokenStore, type ApiResult } from './request'

/* ==================== 券模板 ==================== */

export interface CouponTplRow {
  id: number
  tplCode: string
  tplName: string
  couponType: number
  faceValue: number
  minAmount: number
  maxDiscount?: number
  validType: number
  validStart?: string
  validEnd?: string
  validDays?: number
  totalQty: number
  issuedQty: number
  usedQty: number
  limitPerMember: number
  scopeStore: string
  scopeProduct: string
  allowStack: number
  stackGroup?: string
  claimable: number
  useRuleDesc?: string
  status: number
  sortNo: number
}

export interface CouponTplSave {
  id?: number
  tplName: string
  tplCode?: string
  couponType: number
  faceValue: number
  minAmount: number
  maxDiscount?: number
  validType: number
  validStart?: string
  validEnd?: string
  validDays?: number
  totalQty: number
  limitPerMember: number
  scopeStoreIds: number[]
  scopeProductIds: number[]
  excludeProductIds: number[]
  allowStack: boolean
  stackGroup?: string
  claimable: boolean
  useRuleDesc?: string
  status: number
  sortNo: number
}

export interface GrantCondition {
  levelIds: number[]
  tagIds: number[]
  status: number
  storeId?: number
  createdStart?: string
  createdEnd?: string
  lastConsumeStart?: string
  lastConsumeEnd?: string
  minTotalConsume?: number
  minBalance?: number
  birthdayMonth?: number
  limit?: number
}

export interface GrantRequest {
  tplId: number
  targetType: 'MEMBER' | 'CONDITION' | 'ALL'
  memberIds: number[]
  condition?: GrantCondition
  qtyPerMember: number
  notify: boolean
  reason?: string
  confirmAll?: boolean
}

export interface GrantResult {
  taskNo: string
  tplId: number
  tplName: string
  targetCount: number
  successCount: number
  failCount: number
  qtyPerMember: number
  totalIssued: number
  errors: Array<{ memberId: number; errorMsg: string }>
  tip: string
}

export const couponApi = {
  list: (params?: { keyword?: string; onlyEnabled?: boolean }) =>
    http.get<CouponTplRow[]>('/admin/marketing/coupon-tpls/list', params),
  detail: (id: number) => http.get<CouponTplRow>(`/admin/marketing/coupon-tpls/${id}/detail`),
  create: (data: CouponTplSave) => http.post<CouponTplRow>('/admin/marketing/coupon-tpls', data),
  update: (id: number, data: CouponTplSave) => http.put<CouponTplRow>(`/admin/marketing/coupon-tpls/${id}`, data),
  changeStatus: (id: number, status: number) =>
    http.put<void>(`/admin/marketing/coupon-tpls/${id}/status?status=${status}`),
  remove: (id: number) => http.delete<void>(`/admin/marketing/coupon-tpls/${id}`),
  statistics: (id: number) => http.get<Record<string, unknown>>(`/admin/marketing/coupon-tpls/${id}/statistics`),
  estimate: (data: GrantRequest) => http.post<{ count: number; limited: boolean; limit: number }>(
    '/admin/marketing/coupons/estimate',
    data
  ),
  grant: (data: GrantRequest) => http.postIdem<GrantResult>('/admin/marketing/coupons/grant-batch', data),
  grantProgress: (taskNo: string) =>
    http.get<Record<string, unknown>>(`/admin/marketing/coupons/grant-task/${taskNo}`),
  memberCoupons: (params: { memberId?: number; status?: number; tplId?: number; pageNum?: number; pageSize?: number }) =>
    http.get<{ list: Array<Record<string, unknown>>; total: number }>('/admin/marketing/coupons', params),
  voidCoupon: (couponNo: string, reason: string) =>
    http.postIdem<void>(`/admin/marketing/coupons/${couponNo}/void?reason=${encodeURIComponent(reason)}`),
  verify: (couponNo: string, storeId?: number) =>
    http.postIdem<Record<string, unknown>>(
      `/admin/marketing/coupons/verify?couponNo=${couponNo}${storeId ? '&storeId=' + storeId : ''}`
    )
}

/* ==================== 等级 ==================== */

export interface LevelRow {
  id: number
  levelCode: string
  levelName: string
  levelValue: number
  discountRate: number
  discountText: string
  pointsRate: number
  cardDiscountRate?: number
  upgradeGiftPoints: number
  birthdayCouponTplId?: number
  isDefault: number
  validMonths: number
  status: number
  sortNo: number
  remark?: string
  memberCount: number
  rule: { configured: boolean; logicType: string; items: Array<Record<string, unknown>> }
}

export interface LevelSave {
  id?: number
  levelName: string
  levelCode?: string
  levelValue?: number
  discountRate: number
  pointsRate: number
  cardDiscountRate?: number
  upgradeGiftPoints: number
  birthdayCouponTplId?: number
  isDefault: number
  validMonths: number
  status: number
  sortNo: number
  remark?: string
}

export const levelApi = {
  list: () => http.get<LevelRow[]>('/admin/report/levels'),
  create: (data: LevelSave) => http.post<LevelRow>('/admin/report/levels', data),
  update: (id: number, data: LevelSave) => http.put<LevelRow>(`/admin/report/levels/${id}`, data),
  remove: (id: number) => http.delete<void>(`/admin/report/levels/${id}`),
  getRule: (id: number) => http.get<Record<string, unknown>>(`/admin/report/levels/${id}/rule`),
  saveRule: (id: number, data: Record<string, unknown>) =>
    http.put<Record<string, unknown>>(`/admin/report/levels/${id}/rule`, data),
  ruleFields: () =>
    http.get<Array<{ field: string; label: string; ops: string[] }>>('/admin/report/levels/rule-fields'),
  simulate: (memberId: number) => http.get<Record<string, unknown>>(`/admin/report/levels/simulate/${memberId}`),
  evaluate: (memberId: number) => http.post<Record<string, unknown>>(`/admin/report/levels/evaluate/${memberId}`),
  recalculate: (batchSize = 200) =>
    http.post<Record<string, unknown>>(`/admin/report/levels/recalculate?batchSize=${batchSize}`)
}

/* ==================== 系统管理（RBAC） ==================== */

export interface SysUserRow {
  id: number
  username: string
  realName?: string
  phone?: string
  storeId?: number
  dataScope: number
  status: number
  lastLoginAt?: string
  createdAt?: string
  roleCodes: string[]
}

export interface SysRoleRow {
  id: number
  roleCode: string
  roleName: string
  dataScope: number
  sortNo: number
  status: number
  remark?: string
  userCount: number
  menuCount: number
}

export interface MenuTreeNode {
  id: number
  parentId: number
  menuType: number
  menuName: string
  permCode?: string
  routePath?: string
  sortNo: number
  children: MenuTreeNode[]
}

export const systemApi = {
  users: (params: { keyword?: string; status?: number; pageNum?: number; pageSize?: number }) =>
    http.get<{ list: SysUserRow[]; total: number }>('/admin/system/users', params),
  createUser: (data: Record<string, unknown>) => http.post<number>('/admin/system/users', data),
  updateUser: (id: number, data: Record<string, unknown>) => http.put<void>(`/admin/system/users/${id}`, data),
  changeUserStatus: (id: number, status: number) =>
    http.put<void>(`/admin/system/users/${id}/status?status=${status}`),
  resetPassword: (id: number) =>
    http.post<string>(`/admin/system/users/${id}/reset-password`),
  roles: () => http.get<SysRoleRow[]>('/admin/system/roles'),
  createRole: (data: Record<string, unknown>) => http.post<number>('/admin/system/roles', data),
  updateRole: (id: number, data: Record<string, unknown>) => http.put<void>(`/admin/system/roles/${id}`, data),
  removeRole: (id: number) => http.delete<void>(`/admin/system/roles/${id}`),
  roleMenus: (id: number) => http.get<number[]>(`/admin/system/roles/${id}/menus`),
  saveRoleMenus: (id: number, menuIds: number[]) => http.put<void>(`/admin/system/roles/${id}/menus`, menuIds),
  menuTree: () => http.get<MenuTreeNode[]>('/admin/system/menus/tree'),
  opLogs: (params: Record<string, unknown>) =>
    http.get<{ list: Array<Record<string, unknown>>; total: number }>('/admin/system/op-logs', params),
  loginLogs: (params: Record<string, unknown>) =>
    http.get<{ list: Array<Record<string, unknown>>; total: number }>('/admin/system/login-logs', params)
}

/* ==================== 推荐有礼 ==================== */

export interface ReferralConfigItem {
  key: string
  name: string
  value: string | number | boolean
  type: 'BOOLEAN' | 'NUMBER' | 'DECIMAL' | 'ENUM' | 'TEXT'
  desc: string
  options: string[] | null
}

export const referralApi = {
  relations: (params: { referrerId?: number; status?: number; pageNum?: number; pageSize?: number }) =>
    http.get<{ list: Array<Record<string, unknown>>; total: number }>('/referral/relations', params),
  rewards: (params: { memberId?: number; status?: number; rewardTarget?: number; pageNum?: number; pageSize?: number }) =>
    http.get<{ list: Array<Record<string, unknown>>; total: number }>('/referral/rewards', params),
  stats: () => http.get<Record<string, unknown>>('/referral/stats'),
  config: () => http.get<ReferralConfigItem[]>('/referral/config'),
  saveConfig: (values: Record<string, string>) => http.put<void>('/referral/config', values),
  settle: (id: number, reason?: string) => http.postIdem<void>(`/referral/relations/${id}/settle`, { reason: reason || '' }),
  invalidate: (id: number, reason?: string) =>
    http.postIdem<void>(`/referral/relations/${id}/invalidate`, { reason: reason || '' }),
  settleScan: (limit = 500) => http.post<number>(`/referral/tasks/settle-scan?limit=${limit}`),
  retryRewards: (limit = 200) => http.post<number>(`/referral/tasks/retry-rewards?limit=${limit}`)
}

/* ==================== 生日礼 ==================== */

export const birthdayApi = {
  stats: () => http.get<Record<string, unknown>>('/birthday/stats'),
  config: () => http.get<ReferralConfigItem[]>('/birthday/config'),
  saveConfig: (values: Record<string, string>) => http.post<void>('/birthday/config', values),
  preview: (monthDay?: string) => http.get<Record<string, unknown>>('/birthday/preview', { monthDay }),
  logs: (params: { memberId?: number; status?: number; year?: number; pageNum?: number; pageSize?: number }) =>
    http.get<{ list: Array<Record<string, unknown>>; total: number }>('/birthday/logs', params),
  issue: (memberId: number, year?: number, force = true) =>
    http.postIdem<Record<string, unknown>>('/birthday/issue', { memberId, year, force }),
  issueBatch: (limit = 2000) => http.post<number>(`/birthday/tasks/issue?limit=${limit}`),
  repair: (limit = 200) => http.post<number>(`/birthday/tasks/repair?limit=${limit}`)
}

/* ==================== 消息中心 ==================== */

export interface MessageTplRow {
  id: number
  tplCode: string
  tplName: string
  channel: string
  channelText: string
  wxTemplateId?: string
  wxDataMap?: string
  title?: string
  contentTpl: string
  pagePath?: string
  status: number
  remark?: string
  placeholders: string[]
}

export interface MessageChannelRow {
  code: string
  name: string
  available: boolean
  reason?: string
}

export const messageApi = {
  tpls: () => http.get<MessageTplRow[]>('/message/tpls'),
  updateTpl: (id: number, data: Record<string, unknown>) => http.put<void>(`/message/tpls/${id}`, data),
  channels: () => http.get<MessageChannelRow[]>('/message/channels'),
  records: (params: {
    memberId?: number
    tplCode?: string
    status?: number
    keyword?: string
    pageNum?: number
    pageSize?: number
  }) => http.get<{ list: Array<Record<string, unknown>>; total: number }>('/message/records', params),
  resend: (id: number) => http.postIdem<void>(`/message/records/${id}/resend`),
  retry: (limit = 200) => http.post<number>(`/message/records/retry?limit=${limit}`)
}

/* ==================== 系统参数 ==================== */

export const configApi = {
  list: () => http.get<Array<Record<string, unknown>>>('/admin/system/configs'),
  update: (configKey: string, configValue: string) =>
    http.put<void>('/admin/system/configs', { configKey, configValue })
}

/* ==================== 员工与门店 ==================== */

export interface StaffRow {
  id: number
  staffNo: string
  staffName: string
  phone?: string
  storeId?: number
  storeName?: string
  position?: string
  entryDate?: string
  leaveDate?: string
  status: number
  statusText: string
  createdAt?: string
  account?: { id: number; username: string; realName?: string; status: number; statusText: string } | null
  /** 员工状态与账号状态不一致时的告警文案（后端算好，前端直接展示） */
  accountWarning?: string
}

export interface StaffSave {
  staffNo?: string
  staffName: string
  phone?: string
  storeId?: number
  position?: string
  entryDate?: string
  leaveDate?: string
  status?: number
  userId?: number
}

export interface StoreRow {
  id: number
  storeCode: string
  storeName: string
  province?: string
  city?: string
  district?: string
  address?: string
  contactName?: string
  contactPhone?: string
  businessHours?: string
  managerId?: number
  managerName?: string
  status: number
  statusText: string
  sortNo: number
  staffCount: number
  /** 是否可删除（被员工/订单/会员/账号任一引用即不可删，前端据此禁用按钮并给出原因） */
  deletable?: boolean
  deleteBlockReason?: string
  historyStaffCount?: number
  orderCount?: number
  memberCount?: number
  userCount?: number
}

export const staffApi = {
  list: (params: {
    keyword?: string
    storeId?: number
    status?: number
    position?: string
    pageNum?: number
    pageSize?: number
  }) => http.get<{ list: StaffRow[]; total: number }>('/admin/staff', params),
  detail: (id: number) => http.get<StaffRow & { orderCount: number }>(`/admin/staff/${id}`),
  create: (data: StaffSave) => http.post<StaffRow>('/admin/staff', data),
  update: (id: number, data: StaffSave) => http.put<StaffRow>(`/admin/staff/${id}`, data),
  remove: (id: number) => http.delete<void>(`/admin/staff/${id}`),
  leave: (id: number, leaveDate?: string, reason?: string) =>
    http.post<{ tip: string; accountStillActive: boolean; accountUsername?: string; orderCount: number }>(
      `/admin/staff/${id}/leave`,
      { leaveDate, reason }
    ),
  reinstate: (id: number) =>
    http.postIdem<{ tip: string; accountActive: boolean; accountUsername?: string }>(`/admin/staff/${id}/reinstate`),
  bindAccount: (id: number, userId: number) =>
    http.post<{ username: string; storeSynced: boolean; tip: string }>(`/admin/staff/${id}/account`, { userId }),
  unbindAccount: (id: number) => http.delete<void>(`/admin/staff/${id}/account`),
  accountCandidates: () =>
    http.get<Array<{ id: number; username: string; realName?: string; storeId?: number; status: number }>>(
      '/admin/staff/account-candidates'
    ),
  options: (storeId?: number) =>
    http.get<Array<{ id: number; staffNo: string; staffName: string; storeId?: number; position?: string }>>(
      '/admin/staff/options',
      { storeId }
    ),
  positions: () => http.get<string[]>('/admin/staff/positions'),
  storeOptions: () =>
    http.get<Array<{ id: number; storeCode: string; storeName: string; status: number; staffCount: number }>>(
      '/admin/staff/store-options'
    ),
  me: () =>
    http.get<{ id: number; staffNo: string; staffName: string; storeId?: number; position?: string } | null>(
      '/admin/staff/me'
    ),
  performance: (params: { storeId?: number; startDate?: string; endDate?: string; limit?: number }) =>
    http.get<Array<Record<string, unknown>>>('/admin/staff/performance', params),
  performanceSummary: (params: { storeId?: number; startDate?: string; endDate?: string }) =>
    http.get<Record<string, unknown>>('/admin/staff/performance/summary', params),
  staffPerformance: (id: number, params: { startDate?: string; endDate?: string }) =>
    http.get<Record<string, unknown>>(`/admin/staff/${id}/performance`, params)
}

export const storeApi = {
  list: (params?: { keyword?: string; status?: number }) => http.get<StoreRow[]>('/admin/stores', params),
  detail: (id: number) => http.get<StoreRow>(`/admin/stores/${id}`),
  create: (data: Record<string, unknown>) => http.post<StoreRow>('/admin/stores', data),
  update: (id: number, data: Record<string, unknown>) => http.put<StoreRow>(`/admin/stores/${id}`, data),
  changeStatus: (id: number, status: number) => http.put<void>(`/admin/stores/${id}/status?status=${status}`),
  remove: (id: number) => http.delete<void>(`/admin/stores/${id}`)
}

/* ==================== 看板报表 ==================== */

export const reportApi = {
  overview: (params?: { storeId?: number; days?: number }) =>
    http.get<Record<string, unknown>>('/admin/report/dashboard/overview', params),
  memberTrend: (days = 30) => http.get<Array<Record<string, unknown>>>('/admin/report/dashboard/member-trend', { days }),
  consumeTrend: (days = 30) => http.get<Array<Record<string, unknown>>>('/admin/report/dashboard/consume-trend', { days }),
  levelDistribution: (storeId?: number) =>
    http.get<Array<Record<string, unknown>>>('/admin/report/dashboard/level-distribution', { storeId }),
  retention: (months = 6) => http.get<Array<Record<string, unknown>>>('/admin/report/dashboard/retention', { months }),
  rfm: () => http.get<Record<string, unknown>>('/admin/report/dashboard/rfm'),
  sleepingMembers: (params: { days?: number; limit?: number }) =>
    http.get<Array<Record<string, unknown>>>('/admin/report/dashboard/sleeping-members', params),
  liability: () => http.get<Record<string, number>>('/admin/report/report/liability'),
  storeRanking: (limit = 10) => http.get<Array<Record<string, unknown>>>('/admin/report/report/store-ranking', { limit }),
  staffRanking: (limit = 10) => http.get<Array<Record<string, unknown>>>('/admin/report/report/staff-ranking', { limit }),
  productRanking: (limit = 10) => http.get<Array<Record<string, unknown>>>('/admin/report/report/product-ranking', { limit }),
  storeOptions: () => http.get<Array<{ id: number; storeName: string }>>('/admin/report/report/store-options')
}

/** 带鉴权的文件下载（模板/导出等二进制接口） */
export async function downloadFile(url: string, body?: unknown, fallbackName = 'download'): Promise<void> {
  const resp = await fetch(url, {
    method: body === undefined ? 'GET' : 'POST',
    headers: {
      Authorization: 'Bearer ' + tokenStore.get(),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' })
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  if (!resp.ok) {
    const text = await resp.text()
    let msg = 'HTTP ' + resp.status
    try {
      msg = (JSON.parse(text) as ApiResult).msg || msg
    } catch {
      /* 非 JSON 响应 */
    }
    throw new Error(msg)
  }
  const blob = await resp.blob()
  const cd = resp.headers.get('Content-Disposition') || ''
  let name = fallbackName
  const match = cd.match(/filename\*=UTF-8''([^;]+)/)
  if (match) {
    name = decodeURIComponent(match[1])
  }
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = name
  a.click()
  URL.revokeObjectURL(a.href)
}
