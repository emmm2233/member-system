import { http } from './request'

/* ==================== 类型定义（与后端 VO/DTO 对齐） ==================== */

export interface UserInfo {
  userId: number
  username: string
  realName: string
  phone?: string
  storeId?: number
  storeName?: string
  dataScope: number
  roles: string[]
  perms: string[]
  userType: string
}

export interface LoginResult {
  accessToken: string
  refreshToken: string
  expiresIn: number
  mustChangePassword?: boolean
  userInfo: UserInfo
}

export interface MenuNode {
  id: number
  parentId: number
  menuType: number
  menuName: string
  permCode?: string
  path?: string
  component?: string
  icon?: string
  sortNo?: number
  hidden?: boolean
  children: MenuNode[]
}

export interface MemberVO {
  id: number
  memberNo: string
  phone?: string
  phoneMasked: string
  nickname?: string
  avatar?: string
  realName?: string
  gender?: number
  birthday?: string
  levelId?: number
  levelName?: string
  levelValue?: number
  status: number
  statusText: string
  source: number
  sourceText: string
  storeId?: number
  storeName?: string
  balance?: number
  giftBalance?: number
  points?: number
  growth?: number
  totalConsume?: number
  totalConsumeCount?: number
  totalRecharge?: number
  lastConsumeAt?: string
  createdAt?: string
  remark?: string
  tags: Array<{ id: number; tagName: string; tagColor?: string }>
}

export interface MemberDetail {
  member: MemberVO
  account: {
    balance: number
    giftBalance: number
    frozenAmount: number
    points: number
    growth: number
    totalRecharge: number
    totalGift: number
    totalConsume: number
    totalConsumeCount: number
    balanceExpireAt?: string
    available: number
    nextLevelId?: number
    nextLevelName?: string
    nextLevelNeedGrowth?: number
    levelProgress?: number
  }
  stat: {
    firstConsumeAt?: string
    lastConsumeAt?: string
    payAmount30d?: number
    payAmount90d?: number
    payAmount12m?: number
    orderCount12m?: number
    visitDays30d?: number
    avgOrderAmount?: number
  }
  couponSummary: { unused: number; expiringIn7d: number; used: number; expired: number }
  cardSummary: { normal: number; totalRemainTimes: number }
  recentOrders: Array<{
    orderNo: string
    orderType: number
    orderTypeText: string
    payableAmount: number
    paidAmount: number
    status: number
    statusText: string
    storeName?: string
    createdAt: string
  }>
}

export interface RechargePlan {
  id: number
  planName: string
  rechargeAmount: number
  giftAmount: number
  giftPoints: number
  giftCouponTplIds?: string
  status: number
  sortNo: number
  remark?: string
}

export interface AccountInfo {
  id: number
  memberId: number
  balance: number
  giftBalance: number
  frozenAmount: number
  points: number
  growth: number
  totalRecharge: number
  totalGift: number
  totalConsume: number
  totalConsumeCount: number
  version: number
}

export interface FlowRow {
  id: number
  flowNo: string
  accountType?: number
  accountTypeText?: string
  bizType: number
  bizTypeText: string
  changeAmount?: number
  beforeAmount?: number
  afterAmount?: number
  changePoints?: number
  beforePoints?: number
  afterPoints?: number
  bizNo?: string
  storeName?: string
  remark?: string
  expireTime?: string
  occurTime: string
}

export interface SettleResult {
  orderNo?: string
  status?: number
  totalAmount: number
  discountAmount: number
  couponAmount: number
  pointDeductAmount: number
  pointDeductPoints: number
  payableAmount: number
  paidAmount: number
  changeAmount?: number
  pointsGained: number
  growthGained: number
  maxUsablePoints: number
  maxPointDeductAmount: number
  items: Array<{
    productId?: number
    productName: string
    price: number
    qty: number
    totalAmount: number
    discountAmount: number
    actualAmount: number
    joinDiscount?: boolean
    joinPoints?: boolean
  }>
  couponResults: Array<{ couponNo: string; tplName?: string; discountAmount: number }>
  unavailableCoupons: Array<{ couponNo: string; tplName?: string; reason: string }>
  memberSnapshot?: {
    memberId: number
    memberNo: string
    nickname?: string
    levelName?: string
    discountRate: number
    pointsRate: number
    balance: number
    giftBalance: number
    points: number
    growth: number
  }
  account?: {
    balance: number
    giftBalance: number
    points: number
    growth: number
  }
}

export interface OrderRow {
  id: number
  orderNo: string
  orderType: number
  orderTypeText: string
  memberId: number
  storeId: number
  storeName?: string
  totalAmount: number
  discountAmount: number
  couponAmount: number
  pointDeductAmount: number
  payableAmount: number
  paidAmount: number
  giftAmount: number
  pointsGained: number
  status: number
  statusText: string
  refundAmount: number
  createdAt: string
  paidAt?: string
}

export interface Product {
  id: number
  productCode: string
  productName: string
  categoryId?: number
  productType: number
  price: number
  memberPrice?: number
  unit?: string
  pointsRate?: number
  joinDiscount?: number
  joinPoints?: number
  stockEnabled?: number
  stockQty?: number
  status: number
  sortNo?: number
}

/* ==================== 认证 ==================== */

export const authApi = {
  login: (username: string, password: string) =>
    http.post<LoginResult>('/admin/auth/login', { username, password }),
  logout: () => http.post<void>('/admin/auth/logout'),
  me: () => http.get<UserInfo>('/admin/auth/me'),
  menus: () => http.get<MenuNode[]>('/admin/auth/menus'),
  changePassword: (oldPassword: string, newPassword: string) =>
    http.post<void>('/admin/auth/change-password', { oldPassword, newPassword })
}

/* ==================== 会员 ==================== */

export const memberApi = {
  page: (params: Record<string, unknown>) =>
    http.get<{ list: MemberVO[]; total: number; pageNum: number; pageSize: number }>('/admin/members', params),
  detail: (id: number) => http.get<MemberVO>(`/admin/members/${id}`),
  overview: (id: number) => http.get<MemberDetail>(`/admin/members/${id}/overview`),
  create: (data: Record<string, unknown>) => http.post<MemberVO>('/admin/members', data),
  update: (id: number, data: Record<string, unknown>) => http.put<MemberVO>(`/admin/members/${id}`, data),
  changeStatus: (id: number, status: number, remark?: string) =>
    http.put<void>(`/admin/members/${id}/status`, null, { params: { status, remark } }),
  changeLevel: (id: number, levelId: number, reason?: string) =>
    http.post<void>(`/admin/members/${id}/level`, null, { params: { levelId, reason } }),
  setTags: (id: number, tagIds: number[]) => http.post<void>(`/admin/members/${id}/tags`, tagIds),
  tags: () => http.get<Array<{ id: number; tagName: string; tagColor?: string }>>('/admin/members/tags'),
  searchForCashier: (keyword: string) =>
    http.get<MemberVO[]>('/admin/members/search-for-cashier', { keyword })
}

/* ==================== 资产 ==================== */

export const accountApi = {
  account: (memberId: number) => http.get<AccountInfo>(`/admin/accounts/${memberId}`),
  balanceFlows: (memberId: number, params: Record<string, unknown>) =>
    http.get<{ list: FlowRow[]; total: number; pageNum: number; pageSize: number }>(
      `/admin/accounts/${memberId}/balance-flows`,
      params
    ),
  pointFlows: (memberId: number, params: Record<string, unknown>) =>
    http.get<{ list: FlowRow[]; total: number; pageNum: number; pageSize: number }>(
      `/admin/accounts/${memberId}/point-flows`,
      params
    ),
  adjust: (data: Record<string, unknown>) => http.postIdem('/admin/accounts/adjust', data),
  reconcile: () =>
    http.get<{ balanceDiffCount: number; pointDiffCount: number; diffCount: number; conclusion: string; checkTime: string }>(
      '/admin/accounts/reconcile'
    ),
  reconcileOne: (memberId: number) => http.get<Record<string, unknown>>(`/admin/accounts/${memberId}/reconcile`),
  liability: () => http.get<Record<string, number>>('/admin/accounts/liability')
}

/* ==================== 充值 ==================== */

export const rechargeApi = {
  recharge: (data: Record<string, unknown>, idemKey?: string) =>
    http.postIdem<{
      orderNo: string
      rechargeAmount: number
      giftAmount: number
      giftPoints: number
      pointsAfter: number
      balanceAfter: number
      giftBalanceAfter: number
      grantedCoupons: Array<{ couponNo: string; tplName: string; validEnd?: string }>
      account: { balance: number; giftBalance: number; points: number }
      idempotentHit: boolean
    }>('/admin/recharge', data, idemKey),
  revoke: (orderNo: string, reason?: string) =>
    http.postIdem<void>(`/admin/recharge/${orderNo}/revoke?reason=${encodeURIComponent(reason || '')}`, {}),
  plans: (onlyEnabled = true) => http.get<RechargePlan[]>('/admin/recharge/plans', { onlyEnabled })
}

/* ==================== 收银与订单 ==================== */

export const orderApi = {
  preview: (data: Record<string, unknown>) => http.post<SettleResult>('/admin/orders/preview', data),
  settle: (data: Record<string, unknown>, idemKey?: string) =>
    http.postIdem<SettleResult>('/admin/orders/settle', data, idemKey),
  page: (params: Record<string, unknown>) =>
    http.get<{ list: OrderRow[]; total: number; pageNum: number; pageSize: number }>('/admin/orders', params),
  detail: (orderNo: string) => http.get<Record<string, unknown>>(`/admin/orders/${orderNo}`),
  applyRefund: (orderNo: string, refundAmount: number, reason: string) =>
    http.postIdem<{ refundNo: string; status: number; refundAmount: number }>(
      `/admin/orders/${orderNo}/refund?refundAmount=${refundAmount}&reason=${encodeURIComponent(reason)}`,
      {}
    ),
  approveRefund: (refundNo: string, pass: boolean, remark?: string) =>
    http.postIdem<void>(
      `/admin/orders/refunds/${refundNo}/approve?pass=${pass}&remark=${encodeURIComponent(remark || '')}`
    ),
  refunds: (params: Record<string, unknown>) =>
    http.get<{ list: Record<string, unknown>[]; total: number }>('/admin/orders/refunds', params),
  shiftSummary: (storeId: number, date?: string) =>
    http.get<Record<string, unknown>>('/admin/orders/shift/summary', { storeId, date }),

  /* -------- 挂单 / 取单 / 弃单 -------- */
  hold: (data: Record<string, unknown>, idemKey?: string) =>
    http.postIdem<{
      holdNo: string
      memberId?: number
      memberName?: string
      itemCount: number
      totalAmount: number
      expireTime?: string
      tip: string
    }>('/admin/orders/hold', data, idemKey),
  holdList: (params: { storeId?: number; cashierId?: number; onlyMine?: boolean }) =>
    http.get<
      Array<{
        holdNo: string
        memberId?: number
        memberName?: string
        storeName?: string
        totalAmount: number
        itemCount: number
        createdAt: string
        expireTime?: string
        expired: boolean
        note?: string
        items: Array<{
          itemType: number
          productId?: number
          productName: string
          price: number
          qty: number
        }>
      }>
    >('/admin/orders/hold/list', params),
  resumeHold: (holdNo: string) =>
    http.post<{
      holdNo: string
      memberId?: number
      memberName?: string
      storeId?: number
      itemCount: number
      totalAmount: number
      note?: string
      items: Array<{
        itemType: number
        productId?: number
        productName: string
        price: number
        qty: number
      }>
    }>(`/admin/orders/hold/${holdNo}/resume`),
  cancelHold: (holdNo: string, reason?: string) =>
    http.delete<void>(`/admin/orders/hold/${holdNo}?reason=${encodeURIComponent(reason || '')}`)
}

/* ==================== 商品 ==================== */

export const productApi = {
  page: (params: Record<string, unknown>) =>
    http.get<{ list: Product[]; total: number }>('/admin/products', params),
  all: () => http.get<Product[]>('/admin/products/all'),
  categories: () => http.get<Array<{ id: number; cateName: string; cateType: number }>>('/admin/products/categories')
}

/* ==================== 支付（预留） ==================== */

export const paymentApi = {
  channels: () => http.get<Record<string, unknown>>('/admin/payments/channels'),
  page: (params: Record<string, unknown>) =>
    http.get<{ list: Record<string, unknown>[]; total: number }>('/admin/payments', params),
  reconcile: () => http.get<Record<string, unknown>>('/admin/payments/reconcile')
}
