import { http, tokenStore } from '@/utils/request'

/* ==================== 类型 ==================== */

export interface MpMember {
  id: number
  memberNo: string
  nickname?: string
  avatar?: string
  phoneMasked?: string
  levelName?: string
}

export interface LoginResult {
  accessToken: string
  refreshToken: string
  expiresIn: number
  isNewMember: boolean
  needBindPhone: boolean
  member: MpMember
}

export interface HomeSummary {
  member: {
    id: number
    memberNo: string
    nickname?: string
    avatar?: string
    levelName?: string
    growth: number
    nextLevelName?: string
    nextLevelNeedGrowth?: number
    growthProgress: number
  }
  account: { balance: number; giftBalance: number; points: number; growth: number; available: number }
  couponCount: { unused: number; used: number; expired: number; expiringIn7d: number; expiringPoints: number }
  lastOrder?: { orderNo: string; amount: number; storeName?: string; createdAt: string }
  stat: { lastConsumeAt?: string; orderCount12m?: number; payAmount12m?: number }
  pendingOrders: Array<{ orderNo: string; orderType: number; orderTypeText: string; amount: number; expireTime?: string }>
  payEnabled: boolean
  serverTime: string
}

export interface AccountInfo {
  balance: number
  giftBalance: number
  available: number
  points: number
  growth: number
  totalRecharge: number
  totalConsume: number
  totalConsumeCount: number
  levelName?: string
  nextLevelName?: string
  nextLevelNeedGrowth?: number
  growthProgress: number
  expiringPoints30d: number
  pointsExchangeRate: number
  maxDeductRatio: number
  pointsPerYuan: number
}

export interface BillRow {
  orderNo: string
  orderType: number
  orderTypeText: string
  storeName?: string
  totalAmount: number
  payableAmount: number
  paidAmount: number
  discountAmount: number
  pointsGained: number
  giftAmount: number
  status: number
  statusText: string
  refundAmount: number
  createdAt: string
  month?: string
  itemSummary?: string
  itemCount: number
}

export interface CouponRow {
  couponNo: string
  tplName: string
  couponType: number
  faceValue: number
  minAmount: number
  maxDiscount?: number
  status: number
  statusText: string
  source: string
  validStart: string
  validEnd: string
  usedAt?: string
  expiringSoon: boolean
  daysLeft?: number
  useRuleDesc: string
}

export interface VerifyCodeResult {
  code: string
  qrContent: string
  expiresIn: number
  memberNo: string
  memberName?: string
  levelName?: string
  balance: number
  points: number
  tip: string
}

export interface RechargePlan {
  planId: number
  planName: string
  rechargeAmount: number
  giftAmount: number
  giftPoints: number
  hasCoupon: boolean
  remark?: string
  giftText: string
}

export interface PendingRechargeOrder {
  orderNo: string
  rechargeAmount: number
  giftAmount: number
  giftPoints: number
  planId?: number
  planName?: string
  status: number
  statusText: string
  expireTime?: string
}

export interface PrepayResult {
  paymentNo: string
  bizNo: string
  channel: string
  amount: number
  status: number
  statusText: string
  expireTime?: string
  payParams: Record<string, string>
}

export interface PayQueryResult {
  paymentNo: string
  bizNo: string
  channel: string
  amount: number
  status: number
  statusText: string
  paidAt?: string
  accountCredited: boolean
}

/* ==================== 接口 ==================== */

export const mpApi = {
  /* 认证 */
  login: (code: string, inviteCode?: string) =>
    http.post<LoginResult>('/auth/login', { code, inviteCode }, { silent: true }),
  bindPhone: (payload: { code?: string; phone?: string }) =>
    http.post<{ bound: boolean; merged: boolean; memberId: number; conflict?: boolean; tip?: string; accessToken?: string; refreshToken?: string }>(
      '/auth/bind-phone',
      payload
    ),
  configStatus: () => http.get<{ appIdConfigured: boolean; mockLogin: boolean; tip: string }>('/auth/config-status'),
  profile: () => http.get<Record<string, unknown>>('/auth/profile'),
  updateProfile: (data: Record<string, unknown>) => http.put<void>('/auth/profile', data),

  /* 首页与资产 */
  homeSummary: () => http.get<HomeSummary>('/home/summary', undefined, true),
  account: () => http.get<AccountInfo>('/account'),
  balanceFlows: (params: { type?: string; pageNum?: number; pageSize?: number }) =>
    http.get<{ list: Array<Record<string, unknown>>; total: number }>('/account/balance-flows', params),
  pointFlows: (params: { type?: string; pageNum?: number; pageSize?: number }) =>
    http.get<{ list: Array<Record<string, unknown>>; total: number }>('/account/point-flows', params),
  pointExpireCalendar: () => http.get<Array<{ month: string; points: number }>>('/account/point-expire-calendar'),

  /* 账单 */
  orders: (params: { orderType?: number; pageNum?: number; pageSize?: number }) =>
    http.get<{ list: BillRow[]; total: number; pageNum: number; pageSize: number }>('/orders', params),
  orderDetail: (orderNo: string) => http.get<Record<string, unknown>>(`/orders/${orderNo}`),

  /* 卡券 */
  coupons: (params: { status?: number; pageNum?: number; pageSize?: number }) =>
    http.get<{
      list: CouponRow[]
      total: number
      summary: { unused: number; used: number; expired: number }
    }>('/coupons', params),
  claimableCoupons: () => http.get<Array<Record<string, unknown>>>('/coupons/claimable'),

  /* 核销码 */
  verifyCode: () => http.get<VerifyCodeResult>('/verify-code', undefined, true),
  staticCode: () => http.get<{ qrContent: string; memberNo: string; tip: string }>('/verify-code/static'),

  /* 等级与门店 */
  levels: () => http.get<Array<Record<string, unknown>>>('/levels'),
  stores: () => http.get<Array<Record<string, unknown>>>('/stores'),

  /* 在线充值 */
  rechargePlans: () => http.get<RechargePlan[]>('/recharge/plans'),
  rechargeConfig: () => http.get<Record<string, unknown>>('/recharge/config'),
  createRechargeOrder: (payload: { planId?: number; customAmount?: number; storeId?: number }) =>
    http.post<PendingRechargeOrder>('/recharge/orders', payload, { idempotent: true }),

  /* 支付（预留，Mock 渠道可全链路跑通） */
  payChannels: () => http.get<Array<Record<string, unknown>>>('/pay/channels'),
  prepay: (payload: { bizType: number; bizNo: string; channel?: string }) =>
    http.post<PrepayResult>('/pay/prepay', payload, { idempotent: true }),
  payStatus: (paymentNo: string) => http.get<PayQueryResult>(`/pay/orders/${paymentNo}`, undefined, true),
  closePay: (paymentNo: string) => http.post<void>(`/pay/orders/${paymentNo}/close`),
  mockSuccess: (paymentNo: string) => http.post<string>(`/pay/orders/${paymentNo}/mock-success`),

  /* 推荐有礼 / 生日礼 / 消息中心 */
  referralCenter: () => http.get<ReferralCenter>('/referral'),
  bindReferral: (inviteCode: string, channel = 'LINK') =>
    http.post<{ referralId: number; referrerId: number; status: number; bindAt: string }>('/referral/bind', {
      inviteCode,
      channel
    }),
  birthday: () => http.get<BirthdayInfo>('/birthday'),
  messages: (params: { status?: number; pageNum?: number; pageSize?: number }) =>
    http.get<{ list: MessageRow[]; total: number; pageNum: number; pageSize: number }>('/messages', params),
  unreadCount: () => http.get<number>('/messages/unread-count', undefined, true),
  readMessage: (id: number) => http.post<void>(`/messages/${id}/read`),
  readAllMessages: () => http.post<number>('/messages/read-all'),
  reportSubscribe: (templateIds: string[]) =>
    http.post<number>('/messages/subscribe/report', { templateIds }),
  subscribeQuota: () =>
    http.get<Array<{ wxTemplateId: string; tplCode?: string; remainCount: number; totalCount: number }>>(
      '/messages/subscribe/quota'
    ),

  /* 登录态 */
  logout: () => http.post<void>('/auth/logout').catch(() => undefined),
  clearToken: () => tokenStore.clear()
}

/* ==================== 推荐 / 生日 / 消息 类型 ==================== */

export interface ReferralRewardRow {
  id: number
  target: number
  targetText: string
  rewardType: number
  rewardTypeText: string
  rewardValue: number
  status: number
  statusText: string
  createdAt: string
}

export interface ReferralInviteeRow {
  nickname: string
  phone: string
  bindAt: string
  status: number
  statusText: string
}

export interface ReferralCenter {
  enabled: boolean
  inviteCode: string
  settleMode: string
  minAmount: number
  ruleDesc: string
  posterUrl: string
  bindWindowDays: number
  bound: boolean
  inviteCount: number
  settledCount: number
  totalPoints: number
  totalBalance: number
  rewards: ReferralRewardRow[]
  invitees: ReferralInviteeRow[]
}

export interface BirthdayInfo {
  enabled: boolean
  wishText: string
  giftDesc: string
  daysBefore: number
  hasBirthday: boolean
  birthday?: string
  birthdayMonthDay?: string
  daysUntil?: number
  giftedThisYear: boolean
  lastGift?: {
    id: number
    giftYear: number
    giftDate: string
    points: number
    balance: number
    growth: number
    couponId?: number
    status: number
    statusText: string
    createdAt: string
  } | null
}

export interface MessageRow {
  id: number
  tplCode: string
  channel: string
  channelText: string
  title?: string
  content: string
  bizNo?: string
  status: number
  statusText: string
  errorMsg?: string
  sentAt?: string
  readAt?: string
  createdAt: string
}
