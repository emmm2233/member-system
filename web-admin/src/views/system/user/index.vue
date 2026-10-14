<template>
  <div class="page-container">
    <el-tabs v-model="tab">
      <!-- 用户 -->
      <el-tab-pane label="用户管理" name="user">
        <div class="page-card">
          <div class="page-title">
            <span>系统用户</span>
            <el-button type="primary" plain @click="openUser()" v-if="userStore.has('system:user:add')">
              <el-icon><Plus /></el-icon>新增用户
            </el-button>
          </div>
          <el-table :data="users" v-loading="userLoading" stripe border size="small">
            <el-table-column prop="username" label="登录账号" width="140" />
            <el-table-column prop="realName" label="姓名" width="110" />
            <el-table-column label="角色" min-width="180">
              <template #default="{ row }">
                <el-tag v-for="r in row.roleCodes" :key="r" size="small" effect="plain" style="margin-right: 4px">
                  {{ r }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="数据范围" width="110">
              <template #default="{ row }">{{ scopeText(row.dataScope) }}</template>
            </el-table-column>
            <el-table-column prop="lastLoginAt" label="最后登录" width="170" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 1 ? 'success' : 'danger'">
                  {{ row.status === 1 ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="230" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openUser(row)" v-if="userStore.has('system:user:edit')">
                  编辑
                </el-button>
                <el-button link type="warning" size="small" @click="resetPwd(row)" v-if="userStore.has('system:user:reset')">
                  重置密码
                </el-button>
                <el-button
                  link
                  :type="row.status === 1 ? 'danger' : 'success'"
                  size="small"
                  @click="toggleUser(row)"
                  v-if="userStore.has('system:user:edit')"
                >
                  {{ row.status === 1 ? '停用' : '启用' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- 角色 -->
      <el-tab-pane label="角色权限" name="role">
        <div class="page-card">
          <div class="page-title">
            <span>角色列表</span>
            <el-button type="primary" plain @click="openRole()" v-if="userStore.has('system:role:add')">
              <el-icon><Plus /></el-icon>新增角色
            </el-button>
          </div>
          <el-table :data="roles" v-loading="roleLoading" stripe border size="small">
            <el-table-column prop="roleCode" label="角色编码" width="160" />
            <el-table-column prop="roleName" label="角色名称" width="140" />
            <el-table-column label="数据范围" width="110">
              <template #default="{ row }">{{ scopeText(row.dataScope) }}</template>
            </el-table-column>
            <el-table-column prop="userCount" label="用户数" width="90" align="right" />
            <el-table-column prop="menuCount" label="权限项" width="90" align="right" />
            <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
                  {{ row.status === 1 ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openPerm(row)" v-if="userStore.has('system:role:edit')">
                  分配权限
                </el-button>
                <el-button link type="primary" size="small" @click="openRole(row)" v-if="userStore.has('system:role:edit')">
                  编辑
                </el-button>
                <el-button link type="danger" size="small" @click="removeRole(row)" v-if="userStore.has('system:role:edit')">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- 操作日志 -->
      <el-tab-pane label="操作日志" name="oplog">
        <div class="page-card">
          <el-form inline @submit.prevent>
            <el-form-item label="模块">
              <el-input v-model="opQuery.module" placeholder="如 member / order" clearable style="width: 160px" />
            </el-form-item>
            <el-form-item label="操作类型">
              <el-input v-model="opQuery.opType" placeholder="如 CONSUME" clearable style="width: 140px" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadOpLogs"><el-icon><Search /></el-icon>查询</el-button>
            </el-form-item>
          </el-form>
          <el-table :data="opLogs" v-loading="opLoading" border size="small" max-height="480">
            <el-table-column prop="createdAt" label="时间" width="170" />
            <el-table-column prop="username" label="操作人" width="110" />
            <el-table-column prop="module" label="模块" width="90" />
            <el-table-column prop="opType" label="类型" width="110" />
            <el-table-column prop="opDesc" label="描述" min-width="200" show-overflow-tooltip />
            <el-table-column prop="bizNo" label="业务单号" width="180" />
            <el-table-column label="金额" width="100" align="right">
              <template #default="{ row }">
                {{ row.amount === null || row.amount === undefined ? '-' : '¥' + Number(row.amount).toFixed(2) }}
              </template>
            </el-table-column>
            <el-table-column label="结果" width="70">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 1 ? 'success' : 'danger'">{{ row.statusText }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            class="mt-12"
            background
            layout="total, prev, pager, next"
            :total="opTotal"
            :current-page="opQuery.pageNum"
            :page-size="opQuery.pageSize"
            @current-change="(p: number) => { opQuery.pageNum = p; loadOpLogs() }"
          />
        </div>
      </el-tab-pane>

      <!-- 登录日志 -->
      <el-tab-pane label="登录日志" name="loginlog">
        <div class="page-card">
          <el-form inline @submit.prevent>
            <el-form-item label="账号">
              <el-input v-model="loginQuery.username" placeholder="登录账号" clearable style="width: 160px" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadLoginLogs"><el-icon><Search /></el-icon>查询</el-button>
            </el-form-item>
          </el-form>
          <el-table :data="loginLogs" v-loading="loginLoading" border size="small" max-height="480">
            <el-table-column prop="createdAt" label="时间" width="170" />
            <el-table-column prop="loginTypeText" label="端" width="80" />
            <el-table-column prop="username" label="账号" width="120" />
            <el-table-column prop="ip" label="IP" width="140" />
            <el-table-column prop="userAgent" label="UA" min-width="220" show-overflow-tooltip />
            <el-table-column label="结果" width="70">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 1 ? 'success' : 'danger'">{{ row.statusText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="msg" label="说明" width="140" />
          </el-table>
          <el-pagination
            class="mt-12"
            background
            layout="total, prev, pager, next"
            :total="loginTotal"
            :current-page="loginQuery.pageNum"
            :page-size="loginQuery.pageSize"
            @current-change="(p: number) => { loginQuery.pageNum = p; loadLoginLogs() }"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 用户编辑 -->
    <el-dialog v-model="userVisible" :title="userForm.id ? '编辑用户' : '新增用户'" width="560px">
      <el-form :model="userForm" label-width="110px">
        <el-form-item label="登录账号" required>
          <el-input v-model="userForm.username" :disabled="!!userForm.id" placeholder="登录唯一标识" />
        </el-form-item>
        <el-form-item :label="userForm.id ? '新密码（留空不改）' : '初始密码'" :required="!userForm.id">
          <el-input v-model="userForm.password" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="userForm.realName" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="userForm.phone" maxlength="11" />
        </el-form-item>
        <el-form-item label="所属门店">
          <el-select v-model="userForm.storeId" placeholder="选择门店" style="width: 100%">
            <el-option v-for="s in stores" :key="s.id" :label="s.storeName" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据范围">
          <el-select v-model="userForm.dataScope" style="width: 100%">
            <el-option label="全部数据" :value="1" />
            <el-option label="本店数据" :value="2" />
            <el-option label="仅本人" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="userForm.roleIds" multiple placeholder="选择角色" style="width: 100%">
            <el-option v-for="r in roles" :key="r.id" :label="r.roleName" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="userForm.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitUser">保存</el-button>
      </template>
    </el-dialog>

    <!-- 角色编辑 -->
    <el-dialog v-model="roleVisible" :title="roleForm.id ? '编辑角色' : '新增角色'" width="480px">
      <el-form :model="roleForm" label-width="100px">
        <el-form-item label="角色编码" required>
          <el-input v-model="roleForm.roleCode" :disabled="!!roleForm.id" placeholder="大写字母与下划线" />
        </el-form-item>
        <el-form-item label="角色名称" required>
          <el-input v-model="roleForm.roleName" />
        </el-form-item>
        <el-form-item label="数据范围">
          <el-select v-model="roleForm.dataScope" style="width: 100%">
            <el-option label="全部数据" :value="1" />
            <el-option label="本店数据" :value="2" />
            <el-option label="仅本人" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="roleForm.sortNo" :min="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="roleForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRole">保存</el-button>
      </template>
    </el-dialog>

    <!-- 权限分配 -->
    <el-dialog v-model="permVisible" :title="`分配权限：${currentRole?.roleName || ''}`" width="600px">
      <el-alert type="info" :closable="false" show-icon class="mb-12"
        title="勾选后保存即生效；菜单权限决定可见页面，按钮权限决定可执行操作" />
      <el-tree
        ref="treeRef"
        :data="menuTree"
        show-checkbox
        node-key="id"
        :props="{ label: 'menuName', children: 'children' }"
        default-expand-all
        style="max-height: 460px; overflow: auto"
      />
      <template #footer>
        <el-button @click="permVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitPerm">保存权限</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type ElTree } from 'element-plus'
import { levelApi, systemApi, type MenuTreeNode, type SysRoleRow, type SysUserRow } from '@/api/admin'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const tab = ref('user')
const submitting = ref(false)

const users = ref<SysUserRow[]>([])
const roles = ref<SysRoleRow[]>([])
const stores = ref<Array<{ id: number; storeName: string }>>([])
const userLoading = ref(false)
const roleLoading = ref(false)

const userVisible = ref(false)
const roleVisible = ref(false)
const permVisible = ref(false)
const currentRole = ref<SysRoleRow | null>(null)
const treeRef = ref<InstanceType<typeof ElTree>>()
const menuTree = ref<MenuTreeNode[]>([])

const userForm = reactive({
  id: undefined as number | undefined,
  username: '',
  password: '',
  realName: '',
  phone: '',
  storeId: undefined as number | undefined,
  dataScope: 2,
  status: 1,
  roleIds: [] as number[]
})

const roleForm = reactive({
  id: undefined as number | undefined,
  roleCode: '',
  roleName: '',
  dataScope: 2,
  sortNo: 0,
  status: 1,
  remark: ''
})

const opLogs = ref<Array<Record<string, unknown>>>([])
const opTotal = ref(0)
const opLoading = ref(false)
const opQuery = reactive({ module: '', opType: '', pageNum: 1, pageSize: 20 })

const loginLogs = ref<Array<Record<string, unknown>>>([])
const loginTotal = ref(0)
const loginLoading = ref(false)
const loginQuery = reactive({ username: '', pageNum: 1, pageSize: 20 })

function scopeText(scope: number) {
  return ({ 1: '全部数据', 2: '本店数据', 3: '仅本人', 4: '自定义' } as Record<number, string>)[scope] || '-'
}

async function loadUsers() {
  userLoading.value = true
  try {
    const res = await systemApi.users({ pageNum: 1, pageSize: 50 })
    users.value = res.list
  } finally {
    userLoading.value = false
  }
}

async function loadRoles() {
  roleLoading.value = true
  try {
    roles.value = await systemApi.roles()
  } finally {
    roleLoading.value = false
  }
}

async function loadOpLogs() {
  opLoading.value = true
  try {
    const res = await systemApi.opLogs({ ...opQuery })
    opLogs.value = res.list
    opTotal.value = res.total
  } finally {
    opLoading.value = false
  }
}

async function loadLoginLogs() {
  loginLoading.value = true
  try {
    const res = await systemApi.loginLogs({ ...loginQuery })
    loginLogs.value = res.list
    loginTotal.value = res.total
  } finally {
    loginLoading.value = false
  }
}

function openUser(row?: any) {
  Object.assign(userForm, {
    id: row?.id,
    username: row?.username || '',
    password: '',
    realName: row?.realName || '',
    phone: row?.phone || '',
    storeId: row?.storeId,
    dataScope: row?.dataScope ?? 2,
    status: row?.status ?? 1,
    roleIds: row ? roles.value.filter((r) => row.roleCodes.includes(r.roleCode)).map((r) => r.id) : []
  })
  userVisible.value = true
}

async function submitUser() {
  if (!userForm.id && (!userForm.username.trim() || userForm.password.length < 8)) {
    ElMessage.warning('请填写账号与至少 8 位密码')
    return
  }
  submitting.value = true
  try {
    const payload: Record<string, unknown> = {
      realName: userForm.realName,
      phone: userForm.phone,
      storeId: userForm.storeId,
      dataScope: userForm.dataScope,
      status: userForm.status,
      roleIds: userForm.roleIds
    }
    if (userForm.password) {
      payload.password = userForm.password
    }
    if (userForm.id) {
      await systemApi.updateUser(userForm.id, payload)
    } else {
      payload.username = userForm.username
      await systemApi.createUser(payload)
    }
    ElMessage.success('已保存')
    userVisible.value = false
    loadUsers()
  } finally {
    submitting.value = false
  }
}

async function toggleUser(row: any) {
  await systemApi.changeUserStatus(row.id, row.status === 1 ? 0 : 1)
  ElMessage.success(row.status === 1 ? '已停用（该账号将立即下线）' : '已启用')
  loadUsers()
}

async function resetPwd(row: any) {
  await ElMessageBox.confirm(`重置「${row.username}」的密码？系统将生成随机密码。`, '重置密码', { type: 'warning' })
  const pwd = await systemApi.resetPassword(row.id)
  await ElMessageBox.alert(`新密码：${pwd}\n请立即转告用户并让其修改。`, '重置成功', { confirmButtonText: '我已记录' })
}

function openRole(row?: any) {
  Object.assign(roleForm, {
    id: row?.id,
    roleCode: row?.roleCode || '',
    roleName: row?.roleName || '',
    dataScope: row?.dataScope ?? 2,
    sortNo: row?.sortNo ?? 0,
    status: row?.status ?? 1,
    remark: row?.remark || ''
  })
  roleVisible.value = true
}

async function submitRole() {
  if (!roleForm.roleCode.trim() || !roleForm.roleName.trim()) {
    ElMessage.warning('请填写角色编码与名称')
    return
  }
  submitting.value = true
  try {
    const payload: Record<string, unknown> = {
      roleCode: roleForm.roleCode,
      roleName: roleForm.roleName,
      dataScope: roleForm.dataScope,
      sortNo: roleForm.sortNo,
      status: roleForm.status,
      remark: roleForm.remark
    }
    if (roleForm.id) {
      await systemApi.updateRole(roleForm.id, payload)
    } else {
      await systemApi.createRole(payload)
    }
    ElMessage.success('已保存')
    roleVisible.value = false
    loadRoles()
  } finally {
    submitting.value = false
  }
}

async function removeRole(row: any) {
  await ElMessageBox.confirm(`确认删除角色「${row.roleName}」？`, '删除确认', { type: 'warning' })
  await systemApi.removeRole(row.id)
  ElMessage.success('已删除')
  loadRoles()
}

async function openPerm(row: any) {
  currentRole.value = row
  if (!menuTree.value.length) {
    menuTree.value = await systemApi.menuTree()
  }
  const checked = await systemApi.roleMenus(row.id)
  permVisible.value = true
  // 等树渲染后再回显选中项
  setTimeout(() => {
    treeRef.value?.setCheckedKeys(checked, false)
  }, 50)
}

async function submitPerm() {
  if (!currentRole.value) return
  const checked = treeRef.value?.getCheckedKeys(false) as number[] | undefined
  const half = treeRef.value?.getHalfCheckedKeys() as number[] | undefined
  const menuIds = Array.from(new Set([...(checked || []), ...(half || [])]))
  submitting.value = true
  try {
    await systemApi.saveRoleMenus(currentRole.value.id, menuIds)
    ElMessage.success('权限已保存，相关用户下次请求即生效')
    permVisible.value = false
    loadRoles()
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  stores.value = (await levelApi
    .list()
    .then(() => [])
    .catch(() => [])) as Array<{ id: number; storeName: string }>
  // 门店下拉复用报表接口
  const { reportApi } = await import('@/api/admin')
  stores.value = await reportApi.storeOptions().catch(() => [])
  await Promise.all([loadUsers(), loadRoles(), loadOpLogs(), loadLoginLogs()])
})
</script>
