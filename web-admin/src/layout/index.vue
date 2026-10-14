<template>
  <el-container class="layout">
    <!-- 侧边栏 -->
    <el-aside :width="collapsed ? '64px' : '220px'" class="sidebar">
      <div class="logo">
        <el-icon :size="22" color="#fff"><Shop /></el-icon>
        <span v-show="!collapsed" class="logo-text">XX 会员中心</span>
      </div>
      <el-scrollbar>
        <el-menu
          :default-active="activeMenu"
          :collapse="collapsed"
          background-color="#1f2d3d"
          text-color="#bfcbd9"
          active-text-color="#ffffff"
          router
          unique-opened
        >
          <template v-for="item in menuTree" :key="item.path">
            <el-sub-menu v-if="item.children && item.children.length" :index="item.path">
              <template #title>
                <el-icon><component :is="item.icon || 'Menu'" /></el-icon>
                <span>{{ item.title }}</span>
              </template>
              <el-menu-item v-for="child in item.children" :key="child.path" :index="child.path">
                <el-icon><component :is="child.icon || 'Menu'" /></el-icon>
                <template #title>{{ child.title }}</template>
              </el-menu-item>
            </el-sub-menu>
            <el-menu-item v-else :index="item.path">
              <el-icon><component :is="item.icon || 'Menu'" /></el-icon>
              <template #title>{{ item.title }}</template>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>
    </el-aside>

    <el-container>
      <!-- 顶栏 -->
      <el-header class="navbar">
        <div class="navbar-left">
          <el-icon class="collapse-btn" @click="collapsed = !collapsed">
            <component :is="collapsed ? 'Expand' : 'Fold'" />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="navbar-right">
          <el-tag v-if="userStore.storeName" type="info" size="small" effect="plain">
            <el-icon><OfficeBuilding /></el-icon>
            {{ userStore.storeName }}
          </el-tag>
          <el-button type="primary" size="small" @click="goCashier" v-if="userStore.has('order:add')">
            <el-icon><ShoppingCart /></el-icon>
            收银台
          </el-button>
          <el-dropdown @command="onCommand">
            <span class="user-info">
              <el-avatar :size="28" style="background: var(--brand-primary)">
                {{ userStore.realName.charAt(0) }}
              </el-avatar>
              <span class="user-name">{{ userStore.realName }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">{{ userStore.userInfo?.username }}</el-dropdown-item>
                <el-dropdown-item command="password" divided>修改密码</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- 内容区 -->
      <el-main class="main">
        <router-view v-slot="{ Component }">
          <keep-alive :include="keepAliveNames">
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import { authApi } from '@/api'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const collapsed = ref(false)
const keepAliveNames = ['MemberList', 'OrderConsume']

interface MenuItem {
  path: string
  title: string
  icon?: string
  children?: MenuItem[]
}

/**
 * 侧边菜单由前端路由表 + 后端菜单权限共同决定：
 * 这里直接基于路由表生成，权限由路由守卫与 v-perm 控制，
 * 保证"菜单可见性"与"能否访问"始终一致。
 */
const menuTree = computed<MenuItem[]>(() => {
  const layoutRoute = router.getRoutes().find((r) => r.path === '/')
  const children = (layoutRoute?.children ?? []) as Array<{
    path: string
    meta?: Record<string, unknown>
  }>
  const items: MenuItem[] = []
  for (const child of children) {
    const meta = child.meta || {}
    if (meta.hidden) continue
    const perm = meta.perm as string | undefined
    if (perm && !userStore.has(perm)) continue
    const fullPath = '/' + child.path
    items.push({
      path: fullPath,
      title: (meta.title as string) || child.path,
      icon: meta.icon as string | undefined
    })
  }
  // 手工分组：会员 / 资产 / 营销 / 门店 / 订单 / 支付 / 系统
  const groups: MenuItem[] = [
    {
      path: '/member',
      title: '会员管理',
      icon: 'User',
      children: items.filter((i) => i.path.startsWith('/member'))
    },
    {
      path: '/account',
      title: '资产账务',
      icon: 'Wallet',
      children: items.filter((i) => i.path.startsWith('/account'))
    },
    {
      path: '/marketing',
      title: '营销中心',
      icon: 'Promotion',
      children: items.filter((i) => i.path.startsWith('/marketing'))
    },
    {
      path: '/store',
      title: '门店与员工',
      icon: 'OfficeBuilding',
      children: items.filter((i) => i.path.startsWith('/store'))
    },
    {
      path: '/order',
      title: '订单管理',
      icon: 'List',
      children: items.filter((i) => i.path.startsWith('/order'))
    },
    {
      path: '/payment',
      title: '支付中心',
      icon: 'CreditCard',
      children: items.filter((i) => i.path.startsWith('/payment'))
    },
    {
      path: '/system',
      title: '系统管理',
      icon: 'Setting',
      children: items.filter((i) => i.path.startsWith('/system'))
    }
  ]
  const grouped = groups.filter((g) => g.children && g.children.length > 0)
  const used = new Set(grouped.flatMap((g) => g.children!.map((c) => c.path)))
  const rest = items.filter((i) => !used.has(i.path))
  return [...rest, ...grouped]
})

const activeMenu = computed(() => route.path)
const currentTitle = computed(() => (route.meta.title as string) || '')

function goCashier() {
  router.push('/cashier')
}

async function onCommand(command: string) {
  if (command === 'logout') {
    await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
    await userStore.logout()
    ElMessage.success('已退出登录')
    router.push('/login')
  } else if (command === 'password') {
    const { value } = await ElMessageBox.prompt('请输入新密码（至少 8 位）', '修改密码', {
      inputType: 'password',
      inputPattern: /.{8,}/,
      inputErrorMessage: '密码至少 8 位'
    })
    const { value: oldPassword } = await ElMessageBox.prompt('请输入原密码', '修改密码', {
      inputType: 'password'
    })
    await authApi.changePassword(oldPassword, value)
    ElMessage.success('密码已修改，请重新登录')
    await userStore.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
.layout {
  height: 100vh;
}

.sidebar {
  background: #1f2d3d;
  transition: width 0.25s;
  overflow: hidden;
}

.logo {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #fff;
  font-weight: 600;
  border-bottom: 1px solid #2c3e50;
}

.logo-text {
  font-size: 15px;
  white-space: nowrap;
}

.sidebar :deep(.el-menu) {
  border-right: none;
}

.navbar {
  height: 56px;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
  padding: 0 16px;
}

.navbar-left,
.navbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.collapse-btn {
  font-size: 20px;
  cursor: pointer;
  color: #606266;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}

.user-name {
  font-size: 14px;
  color: #303133;
}

.main {
  padding: 0;
  background: var(--page-bg);
  overflow-y: auto;
}
</style>
