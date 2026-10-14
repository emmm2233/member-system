import { mpApi, type LoginResult } from '@/api'
import { tokenStore } from './request'

const MEMBER_KEY = 'mp_member_info'

/**
 * 登录编排
 *
 * 小程序端标准流程：
 *  1. wx.login 拿 code → 后端换 openid → 建档/查询会员 → 拿 token
 *  2. needBindPhone=true 时提示绑定手机号（不强制，涉及资金/核销的操作会再拦截）
 */
export const auth = {
  /** 静默登录（App.onLaunch 调用）；已登录则直接返回缓存 */
  async silentLogin(force = false): Promise<LoginResult | null> {
    if (!force && tokenStore.get()) {
      return null
    }
    const code = await wxLoginCode()
    if (!code) {
      return null
    }
    const inviteCode = uni.getStorageSync('mp_invite_code') || undefined
    try {
      const res = await mpApi.login(code, inviteCode)
      tokenStore.set(res.accessToken, res.refreshToken)
      uni.setStorageSync(MEMBER_KEY, res.member)
      // 邀请码只使用一次，登录后清除
      if (inviteCode) {
        uni.removeStorageSync('mp_invite_code')
      }
      return res
    } catch (e) {
      console.error('[auth] 登录失败', e)
      return null
    }
  },

  /** 缓存的会员信息 */
  cachedMember() {
    return uni.getStorageSync(MEMBER_KEY) || null
  },

  cacheMember(member: unknown) {
    uni.setStorageSync(MEMBER_KEY, member)
  },

  isLoggedIn(): boolean {
    return !!tokenStore.get()
  },

  /** 需要资金/核销等敏感操作前的登录+绑手机校验 */
  async ensureReady(options: { requirePhone?: boolean } = {}): Promise<boolean> {
    if (!this.isLoggedIn()) {
      await this.silentLogin(true)
    }
    if (!this.isLoggedIn()) {
      uni.showToast({ title: '登录失败，请重进小程序', icon: 'none' })
      return false
    }
    if (options.requirePhone) {
      const member = this.cachedMember() as { phoneMasked?: string } | null
      const profile = await mpApi.profile().catch(() => null)
      if (profile && profile.hasPhone === false) {
        const go = await confirmModal('需要先绑定手机号', '绑定手机号后才能使用该功能，是否现在绑定？')
        if (go) {
          uni.navigateTo({ url: '/pages/bind-phone/index' })
        }
        return false
      }
    }
    return true
  },

  async logout() {
    await mpApi.logout()
    tokenStore.clear()
    uni.removeStorageSync(MEMBER_KEY)
    uni.showToast({ title: '已退出登录', icon: 'none' })
  }
}

/** 封装 wx.login 为 Promise */
function wxLoginCode(): Promise<string> {
  return new Promise((resolve) => {
    uni.login({
      provider: 'weixin',
      success: (res) => resolve(res.code || ''),
      fail: () => {
        // H5 或非微信环境：用设备随机码代替，仍然走 Mock 登录链路
        let devCode = uni.getStorageSync('mp_dev_code')
        if (!devCode) {
          devCode = 'h5-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)
          uni.setStorageSync('mp_dev_code', devCode)
        }
        resolve(devCode)
      }
    })
  })
}

function confirmModal(title: string, content: string): Promise<boolean> {
  return new Promise((resolve) => {
    uni.showModal({
      title,
      content,
      confirmText: '去绑定',
      cancelText: '暂不',
      success: (res) => resolve(!!res.confirm),
      fail: () => resolve(false)
    })
  })
}
