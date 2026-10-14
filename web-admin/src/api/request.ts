import axios, { type AxiosInstance, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'

/** 统一响应体（与后端 R<T> 对应） */
export interface ApiResult<T = unknown> {
  code: number
  msg: string
  data: T
  traceId?: string
}

/** 分页结果 */
export interface PageData<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
  pages: number
}

/** 错误码常量（与后端 ErrorCode 对齐，便于前端做差异化处理） */
export const ErrorCode = {
  SUCCESS: 0,
  PARAM_INVALID: 10001,
  UNAUTHORIZED: 10002,
  FORBIDDEN: 10003,
  DATA_NOT_FOUND: 10004,
  REPEAT_SUBMIT: 10006,
  PHONE_EXISTS: 20001,
  MEMBER_FROZEN: 20002,
  BALANCE_NOT_ENOUGH: 30001,
  POINTS_NOT_ENOUGH: 30002,
  BALANCE_EXCEED_LIMIT: 30006,
  CONCURRENT_MODIFY: 30007,
  ORDER_ALREADY_PAID: 40002,
  PAYMENT_AMOUNT_MISMATCH: 40006,
  COUPON_THRESHOLD_NOT_MET: 50003,
  PAY_CHANNEL_NOT_READY: 70001
} as const

const TOKEN_KEY = 'member_access_token'
const REFRESH_KEY = 'member_refresh_token'

export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY) || '',
  getRefresh: () => localStorage.getItem(REFRESH_KEY) || '',
  set(access: string, refresh?: string) {
    localStorage.setItem(TOKEN_KEY, access)
    if (refresh) localStorage.setItem(REFRESH_KEY, refresh)
  },
  clear() {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(REFRESH_KEY)
  }
}

/** 生成幂等键（资金类接口必须携带） */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return 'idem-' + Date.now() + '-' + Math.random().toString(36).slice(2, 12)
}

const service: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

/** 是否正在刷新 token，避免并发刷新 */
let refreshing = false
/** 刷新期间挂起的请求 */
let pendingQueue: Array<(token: string) => void> = []

service.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = tokenStore.get()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

service.interceptors.response.use(
  // 返回值故意是「已解包的业务数据」而不是 AxiosResponse，
  // 因此显式标注 any：调用方通过 http.get<T>() 拿回 T，类型安全在那一层保证。
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  async (response): Promise<any> => {
    const result = response.data as ApiResult
    if (result.code === ErrorCode.SUCCESS) {
      return result.data
    }
    // 未登录 / 登录过期：尝试刷新一次
    if (result.code === ErrorCode.UNAUTHORIZED) {
      const original = response.config
      const refreshed = await tryRefresh()
      if (refreshed) {
        original.headers.Authorization = `Bearer ${tokenStore.get()}`
        return service.request(original)
      }
      handleLogout()
      return Promise.reject(new Error(result.msg || '登录已过期'))
    }
    // 业务错误：统一提示（401/403 由后端返回业务码，这里只做展示）
    ElMessage.error(result.msg || '操作失败')
    return Promise.reject(Object.assign(new Error(result.msg), { code: result.code, result }))
  },
  (error) => {
    const status = error?.response?.status
    if (status === 401) {
      handleLogout()
    } else if (status === 403) {
      ElMessage.error('无权限执行该操作')
    } else if (status >= 500) {
      ElMessage.error('服务器异常，请稍后重试')
    } else {
      ElMessage.error(error?.message || '网络异常')
    }
    return Promise.reject(error)
  }
)

async function tryRefresh(): Promise<boolean> {
  const refreshToken = tokenStore.getRefresh()
  if (!refreshToken) return false
  if (refreshing) {
    return new Promise((resolve) => {
      pendingQueue.push(() => resolve(true))
      setTimeout(() => resolve(false), 8000)
    })
  }
  refreshing = true
  try {
    const res = await axios.post('/api/admin/auth/refresh', { refreshToken })
    const data = res.data?.data
    if (res.data?.code === 0 && data?.accessToken) {
      tokenStore.set(data.accessToken, data.refreshToken)
      pendingQueue.forEach((cb) => cb(data.accessToken))
      pendingQueue = []
      return true
    }
    return false
  } catch {
    return false
  } finally {
    refreshing = false
  }
}

function handleLogout() {
  tokenStore.clear()
  if (!location.hash.includes('/login')) {
    ElMessageBox.alert('登录状态已失效，请重新登录', '提示', {
      confirmButtonText: '去登录',
      type: 'warning'
    })
      .then(() => {
        location.href = '/#/login'
        location.reload()
      })
      .catch(() => {
        location.href = '/#/login'
      })
  }
}

/** 通用请求方法 */
export const http = {
  get<T>(url: string, params?: Record<string, unknown>, config?: AxiosRequestConfig) {
    return service.get(url, { params, ...config }) as unknown as Promise<T>
  },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return service.post(url, data, config) as unknown as Promise<T>
  },
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return service.put(url, data, config) as unknown as Promise<T>
  },
  delete<T>(url: string, config?: AxiosRequestConfig) {
    return service.delete(url, config) as unknown as Promise<T>
  },
  /** 资金类写操作：自动携带幂等键 */
  postIdem<T>(url: string, data?: unknown, idemKey?: string) {
    return service.post(url, data, {
      headers: { 'X-Idempotency-Key': idemKey || newIdempotencyKey() }
    }) as unknown as Promise<T>
  }
}

export default service
