/**
 * 全局配置
 *
 * BASE_URL 说明：
 *  - H5 调试：走 Vite 代理，用相对路径即可（同域）
 *  - 微信小程序：必须填完整域名（需在微信公众平台配置 request 合法域名）
 *    本地开发勾选"不校验合法域名"后可直接用 http://127.0.0.1:8080
 */
export const BASE_URL = 'http://127.0.0.1:8080'

/** 是否为 H5 环境（H5 用相对路径走代理，避免跨域） */
export const IS_H5 = (() => {
  // #ifdef H5
  return true
  // #endif
  // eslint-disable-next-line no-unreachable
  return false
})()

export const API_PREFIX = '/api/mp'

/** 请求超时（毫秒） */
export const REQUEST_TIMEOUT = 20000

/** 主题色 */
export const THEME = {
  primary: '#1a73e8',
  success: '#34a853',
  warning: '#f29c1f',
  danger: '#ea4335',
  textMuted: '#909399'
}

/** 订阅消息模板（需在微信公众平台申请后填入真实模板 ID） */
export const SUBSCRIBE_TEMPLATES = {
  recharge: '',
  couponExpire: '',
  birthday: '',
  cardExpire: ''
}
