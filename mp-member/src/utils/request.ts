import { API_PREFIX, BASE_URL, IS_H5, REQUEST_TIMEOUT } from '@/config'

/** 统一响应体 */
export interface ApiResult<T = unknown> {
  code: number
  msg: string
  data: T
  traceId?: string
}

const TOKEN_KEY = 'mp_access_token'
const REFRESH_KEY = 'mp_refresh_token'

export const tokenStore = {
  get(): string {
    return uni.getStorageSync(TOKEN_KEY) || ''
  },
  getRefresh(): string {
    return uni.getStorageSync(REFRESH_KEY) || ''
  },
  set(access: string, refresh?: string) {
    uni.setStorageSync(TOKEN_KEY, access)
    if (refresh) {
      uni.setStorageSync(REFRESH_KEY, refresh)
    }
  },
  clear() {
    uni.removeStorageSync(TOKEN_KEY)
    uni.removeStorageSync(REFRESH_KEY)
  }
}

/** 生成幂等键 */
export function newIdempotencyKey(): string {
  return 'mp-' + Date.now() + '-' + Math.random().toString(36).slice(2, 12)
}

interface RequestOptions {
  url: string
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: Record<string, unknown> | unknown
  /** 是否显示 loading */
  loading?: boolean
  /** 是否静默（不弹错误提示） */
  silent?: boolean
  /** 资金类接口：自动携带幂等键 */
  idempotent?: boolean
  headers?: Record<string, string>
}

/** 是否正在刷新 token */
let refreshing = false

/**
 * 统一请求封装
 *
 * 约定：HTTP 始终返回 200，业务结果看 code；code=0 表示成功。
 */
export function request<T = unknown>(options: RequestOptions): Promise<T> {
  const { url, method = 'GET', data, loading = false, silent = false, idempotent = false } = options
  const fullUrl = IS_H5 ? API_PREFIX + url : BASE_URL + API_PREFIX + url

  if (loading) {
    uni.showLoading({ title: '加载中', mask: true })
  }

  const header: Record<string, string> = { 'Content-Type': 'application/json', ...(options.headers || {}) }
  const token = tokenStore.get()
  if (token) {
    header.Authorization = 'Bearer ' + token
  }
  if (idempotent) {
    header['X-Idempotency-Key'] = newIdempotencyKey()
  }

  return new Promise<T>((resolve, reject) => {
    uni.request({
      url: fullUrl,
      method,
      data: data as never,
      header,
      timeout: REQUEST_TIMEOUT,
      success: async (res) => {
        const body = res.data as ApiResult<T>
        if (!body || typeof body.code === 'undefined') {
          if (!silent) {
            uni.showToast({ title: '服务异常，请稍后重试', icon: 'none' })
          }
          reject(new Error('invalid response'))
          return
        }
        if (body.code === 0) {
          resolve(body.data)
          return
        }
        // 登录失效：尝试刷新一次
        if (body.code === 10002) {
          const ok = await tryRefresh()
          if (ok) {
            try {
              const retry = await request<T>({ ...options, loading: false })
              resolve(retry)
              return
            } catch (e) {
              reject(e)
              return
            }
          }
          handleUnauthorized()
          reject(new Error(body.msg || '登录已过期'))
          return
        }
        if (!silent) {
          uni.showToast({ title: body.msg || '操作失败', icon: 'none', duration: 2200 })
        }
        reject(Object.assign(new Error(body.msg), { code: body.code, data: body.data }))
      },
      fail: (err) => {
        if (!silent) {
          uni.showToast({ title: '网络异常，请检查网络', icon: 'none' })
        }
        reject(err)
      },
      complete: () => {
        if (loading) {
          uni.hideLoading()
        }
      }
    })
  })
}

/** 刷新 token */
function tryRefresh(): Promise<boolean> {
  const refreshToken = tokenStore.getRefresh()
  if (!refreshToken || refreshing) {
    return Promise.resolve(false)
  }
  refreshing = true
  return new Promise<boolean>((resolve) => {
    uni.request({
      url: (IS_H5 ? API_PREFIX : BASE_URL + API_PREFIX) + '/auth/refresh',
      method: 'POST',
      data: { refreshToken },
      header: { 'Content-Type': 'application/json' },
      success: (res) => {
        const body = res.data as ApiResult<{ accessToken: string; refreshToken: string }>
        if (body?.code === 0 && body.data?.accessToken) {
          tokenStore.set(body.data.accessToken, body.data.refreshToken)
          resolve(true)
        } else {
          resolve(false)
        }
      },
      fail: () => resolve(false),
      complete: () => {
        refreshing = false
      }
    })
  })
}

function handleUnauthorized() {
  tokenStore.clear()
  const pages = getCurrentPages()
  const current = pages.length ? pages[pages.length - 1].route : ''
  if (current && current.indexOf('bind-phone') === -1) {
    uni.showToast({ title: '登录已过期，请重进小程序', icon: 'none' })
  }
}

export const http = {
  get<T>(url: string, data?: Record<string, unknown>, silent = false): Promise<T> {
    return request<T>({ url, method: 'GET', data, silent })
  },
  post<T>(url: string, data?: unknown, extra?: Partial<RequestOptions>): Promise<T> {
    return request<T>({ url, method: 'POST', data, ...extra })
  },
  put<T>(url: string, data?: unknown, extra?: Partial<RequestOptions>): Promise<T> {
    return request<T>({ url, method: 'PUT', data, ...extra })
  }
}
