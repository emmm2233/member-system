import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/store/user'

/**
 * 路由表
 *
 * meta.perm  : 权限码（无权限则无法访问，超管放行）
 * meta.title : 菜单标题
 * meta.hidden: 不在菜单中显示
 * meta.public: 无需登录
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录', public: true, hidden: true }
  },
  {
    path: '/',
    component: () => import('@/layout/index.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '工作台', perm: 'report:view', icon: 'DataLine' }
      },
      {
        path: 'member/list',
        name: 'MemberList',
        component: () => import('@/views/member/list/index.vue'),
        meta: { title: '会员列表', perm: 'member:list', icon: 'User' }
      },
      {
        path: 'member/detail/:id',
        name: 'MemberDetail',
        component: () => import('@/views/member/detail/index.vue'),
        meta: { title: '会员 360°', perm: 'member:query', hidden: true }
      },
      {
        path: 'member/import',
        name: 'MemberImport',
        component: () => import('@/views/member/import/index.vue'),
        meta: { title: '会员导入导出', perm: 'member:import', icon: 'Upload' }
      },
      {
        path: 'account/reconcile',
        name: 'AccountReconcile',
        component: () => import('@/views/account/reconcile/index.vue'),
        meta: { title: '对账中心', perm: 'account:reconcile', icon: 'Wallet' }
      },
      {
        path: 'marketing/coupon-tpl',
        name: 'CouponTpl',
        component: () => import('@/views/marketing/couponTpl/index.vue'),
        meta: { title: '优惠券模板', perm: 'coupon:tpl:list', icon: 'Discount' }
      },
      {
        path: 'marketing/grant',
        name: 'CouponGrant',
        component: () => import('@/views/marketing/grant/index.vue'),
        meta: { title: '发券向导', perm: 'coupon:grant', icon: 'Promotion' }
      },
      {
        path: 'marketing/referral',
        name: 'MarketingReferral',
        component: () => import('@/views/marketing/referral/index.vue'),
        meta: { title: '推荐有礼', perm: 'referral:list', icon: 'Share' }
      },
      {
        path: 'marketing/birthday',
        name: 'MarketingBirthday',
        component: () => import('@/views/marketing/birthday/index.vue'),
        meta: { title: '生日礼', perm: 'birthday:list', icon: 'Present' }
      },
      {
        path: 'marketing/message',
        name: 'MarketingMessage',
        component: () => import('@/views/marketing/message/index.vue'),
        meta: { title: '消息中心', perm: 'message:record:list', icon: 'Bell' }
      },
      {
        path: 'store/list',
        name: 'StoreList',
        component: () => import('@/views/store/list/index.vue'),
        meta: { title: '门店管理', perm: 'store:list', icon: 'OfficeBuilding' }
      },
      {
        path: 'store/staff',
        name: 'StoreStaff',
        component: () => import('@/views/store/staff/index.vue'),
        meta: { title: '员工管理', perm: 'staff:list', icon: 'Avatar' }
      },
      {
        path: 'store/performance',
        name: 'StorePerformance',
        component: () => import('@/views/store/performance/index.vue'),
        meta: { title: '业绩排行', perm: 'staff:perf', icon: 'Trophy' }
      },
      {
        path: 'level/list',
        name: 'LevelList',
        component: () => import('@/views/level/list/index.vue'),
        meta: { title: '等级与权益', perm: 'level:list', icon: 'Medal' }
      },
      {
        path: 'report/overview',
        name: 'ReportOverview',
        component: () => import('@/views/report/overview/index.vue'),
        meta: { title: '经营看板', perm: 'report:view', icon: 'TrendCharts' }
      },
      {
        path: 'system/user',
        name: 'SystemUser',
        component: () => import('@/views/system/user/index.vue'),
        meta: { title: '用户与权限', perm: 'system:user:list', icon: 'Lock' }
      },
      {
        path: 'order/consume',
        name: 'OrderConsume',
        component: () => import('@/views/order/consume/index.vue'),
        meta: { title: '消费订单', perm: 'order:list', icon: 'List' }
      },
      {
        path: 'order/refund',
        name: 'OrderRefund',
        component: () => import('@/views/order/refund/index.vue'),
        meta: { title: '退单管理', perm: 'order:refund:list', icon: 'RefreshLeft' }
      },
      {
        path: 'payment/index',
        name: 'PaymentOrder',
        component: () => import('@/views/payment/index.vue'),
        meta: { title: '支付单(预留)', perm: 'payment:list', icon: 'CreditCard' }
      },
      {
        path: 'system/config',
        name: 'SystemConfig',
        component: () => import('@/views/system/config/index.vue'),
        meta: { title: '系统参数', perm: 'system:config:list', icon: 'Setting' }
      }
    ]
  },
  {
    path: '/cashier',
    name: 'Cashier',
    component: () => import('@/views/cashier/index.vue'),
    meta: { title: '收银台', perm: 'order:add' }
  },
  {
    path: '/403',
    name: 'Forbidden',
    component: () => import('@/views/error/403.vue'),
    meta: { title: '无权限', public: true, hidden: true }
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/error/404.vue'),
    meta: { title: '页面不存在', public: true, hidden: true }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach(async (to) => {
  const userStore = useUserStore()

  if (to.meta.public) {
    // 已登录用户访问登录页则跳工作台
    if (to.path === '/login' && userStore.loaded) {
      return '/dashboard'
    }
    return true
  }

  // 恢复登录态
  const ok = await userStore.bootstrap()
  if (!ok) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 权限校验
  const perm = to.meta.perm as string | undefined
  if (perm && !userStore.has(perm)) {
    return '/403'
  }
  return true
})

router.afterEach((to) => {
  const title = (to.meta.title as string) || ''
  document.title = title ? `${title} · XX 会员中心` : 'XX 会员中心'
})

export default router
