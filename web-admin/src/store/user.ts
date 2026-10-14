import { defineStore } from 'pinia'
import { authApi, type MenuNode, type UserInfo } from '@/api'
import { tokenStore } from '@/api/request'

interface UserState {
  userInfo: UserInfo | null
  menus: MenuNode[]
  loaded: boolean
}

export const useUserStore = defineStore('user', {
  state: (): UserState => ({
    userInfo: null,
    menus: [],
    loaded: false
  }),

  getters: {
    /** 权限码集合（超管返回全部，无需逐个判断） */
    perms: (state): string[] => state.userInfo?.perms ?? [],
    isSuperAdmin: (state): boolean => (state.userInfo?.roles ?? []).includes('SUPER_ADMIN'),
    realName: (state): string => state.userInfo?.realName || state.userInfo?.username || '',
    storeName: (state): string => state.userInfo?.storeName || '未指定门店'
  },

  actions: {
    /** 登录并加载用户信息与菜单 */
    async login(username: string, password: string) {
      const result = await authApi.login(username, password)
      tokenStore.set(result.accessToken, result.refreshToken)
      this.userInfo = result.userInfo
      await this.loadMenus()
      this.loaded = true
      return result
    },

    async loadProfile() {
      this.userInfo = await authApi.me()
    },

    async loadMenus() {
      this.menus = await authApi.menus()
    },

    /** 应用启动时恢复登录态 */
    async bootstrap() {
      if (this.loaded) return true
      if (!tokenStore.get()) return false
      try {
        await this.loadProfile()
        await this.loadMenus()
        this.loaded = true
        return true
      } catch {
        this.reset()
        return false
      }
    },

    /** 按钮级权限判断 */
    has(perm?: string | string[]): boolean {
      if (!perm) return true
      if (this.isSuperAdmin) return true
      const list = Array.isArray(perm) ? perm : [perm]
      return list.some((p) => this.perms.includes(p))
    },

    async logout() {
      try {
        await authApi.logout()
      } catch {
        // 忽略登出接口异常，本地状态照常清理
      }
      this.reset()
    },

    reset() {
      this.userInfo = null
      this.menus = []
      this.loaded = false
      tokenStore.clear()
    }
  }
})
