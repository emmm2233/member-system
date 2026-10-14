<template>
  <div class="page-container">
    <div class="page-card">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="员工与登录账号是两件事"
        description="员工是业务主体（业绩归他、工牌是他），账号是访问凭证（能登录、有角色）。办理离职只改员工状态、不动账号；停用账号也不会动员工档案。二者不一致时列表会给出提示。"
      />
      <el-form inline @submit.prevent style="margin-top: 14px">
        <el-form-item label="关键词">
          <el-input
            v-model="query.keyword"
            placeholder="工号 / 姓名 / 手机号"
            clearable
            style="width: 190px"
            @keyup.enter="load"
          />
        </el-form-item>
        <el-form-item label="门店">
          <el-select v-model="query.storeId" clearable placeholder="全部门店" style="width: 170px" @change="load">
            <el-option v-for="s in stores" :key="s.id" :label="s.storeName" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px" @change="load">
            <el-option label="在职" :value="1" />
            <el-option label="离职" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load"><el-icon><Search /></el-icon>查询</el-button>
          <el-button type="primary" plain @click="openEdit()" v-if="canAdd">
            <el-icon><Plus /></el-icon>新增员工
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="page-card">
      <div class="page-title">
        <span>员工档案 <el-tag size="small" type="info" effect="plain">{{ total }} 人</el-tag></span>
        <span class="text-muted">在职 {{ activeCount }} · 离职 {{ total - activeCount }}</span>
      </div>

      <el-table :data="rows" v-loading="loading" stripe border size="small">
        <el-table-column label="工号" width="90" prop="staffNo" />
        <el-table-column label="员工" min-width="150">
          <template #default="{ row }">
            <div>{{ row.staffName }}</div>
            <div class="text-muted">{{ row.phone || '未填手机号' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="门店 / 岗位" min-width="160">
          <template #default="{ row }">
            <div>{{ row.storeName || '未分配门店' }}</div>
            <div class="text-muted">{{ row.position || '未设岗位' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="入职 / 离职" width="180">
          <template #default="{ row }">
            <div>入职 {{ row.entryDate || '-' }}</div>
            <div v-if="row.leaveDate" class="text-muted">离职 {{ row.leaveDate }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="关联登录账号" min-width="210">
          <template #default="{ row }">
            <div v-if="row.account">
              <span>{{ row.account.username }}</span>
              <el-tag
                size="small"
                :type="row.account.status === 1 ? 'success' : 'danger'"
                effect="plain"
                style="margin-left: 6px"
              >
                {{ row.account.statusText }}
              </el-tag>
            </div>
            <span v-else class="text-muted">未关联</span>
            <div v-if="row.accountWarning" class="warn-line">
              <el-icon><WarningFilled /></el-icon>{{ row.accountWarning }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)" v-if="canEdit">编辑</el-button>
            <el-button link type="primary" size="small" @click="openAccount(row)" v-if="canAccount">
              {{ row.account ? '换账号' : '关联账号' }}
            </el-button>
            <el-button
              v-if="canLeave && row.status === 1"
              link
              type="warning"
              size="small"
              @click="doLeave(row)"
            >
              离职
            </el-button>
            <el-button v-if="canLeave && row.status === 0" link type="success" size="small" @click="doReinstate(row)">
              复职
            </el-button>
            <el-button link type="danger" size="small" @click="doRemove(row)" v-if="canEdit">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pager"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="query.pageSize"
        :current-page="query.pageNum"
        @current-change="onPage"
      />
    </div>

    <!-- 新增/编辑 -->
    <el-drawer v-model="editVisible" :title="form.id ? '编辑员工' : '新增员工'" size="560px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="工号">
          <el-input v-model="form.staffNo" placeholder="留空自动生成 E001 / E002…" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="form.staffName" maxlength="50" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" maxlength="11" placeholder="选填，用于联系" />
        </el-form-item>
        <el-form-item label="所属门店">
          <el-select v-model="form.storeId" clearable placeholder="请选择" style="width: 100%">
            <el-option v-for="s in stores" :key="s.id" :label="s.storeName" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="岗位">
          <el-select v-model="form.position" filterable allow-create clearable placeholder="请选择或输入" style="width: 100%">
            <el-option v-for="p in positions" :key="p" :label="p" :value="p" />
          </el-select>
        </el-form-item>
        <el-form-item label="入职日期">
          <el-date-picker v-model="form.entryDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">在职</el-radio>
            <el-radio :value="0">离职</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="关联账号" v-if="canAccount">
          <el-select v-model="form.userId" clearable filterable placeholder="选填；只列出未绑定员工的账号" style="width: 100%">
            <el-option
              v-for="a in candidates"
              :key="a.id"
              :label="`${a.username}${a.realName ? '（' + a.realName + '）' : ''}`"
              :value="a.id"
            />
          </el-select>
          <div class="text-muted hint">关联后该账号结算的订单业绩会归到这名员工</div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="submit" :loading="submitting">保存</el-button>
          <el-button @click="editVisible = false">取消</el-button>
        </el-form-item>
      </el-form>
    </el-drawer>

    <!-- 关联账号 -->
    <el-dialog v-model="accountVisible" title="关联登录账号" width="480px">
      <el-alert
        v-if="current?.account"
        type="warning"
        :closable="false"
        show-icon
        :title="`当前已关联账号 ${current.account.username}`"
        description="保存后将改为新账号，原账号会自动解绑（账号本身不受影响）。"
      />
      <el-select
        v-model="selectedUserId"
        filterable
        placeholder="选择未绑定员工的登录账号"
        style="width: 100%; margin-top: 12px"
      >
        <el-option
          v-for="a in candidates"
          :key="a.id"
          :label="`${a.username}${a.realName ? '（' + a.realName + '）' : ''} · ${a.status === 1 ? '启用' : '停用'}`"
          :value="a.id"
        />
      </el-select>
      <div v-if="!candidates.length" class="text-muted" style="margin-top: 10px">
        没有可关联的账号。可先到「系统管理 → 用户与权限」创建账号。
      </div>
      <template #footer>
        <el-button v-if="current?.account" type="danger" plain @click="doUnbind">解绑当前账号</el-button>
        <el-button @click="accountVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!selectedUserId" @click="doBind">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { staffApi, type StaffRow, type StaffSave } from '@/api/admin'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const canAdd = computed(() => userStore.has('staff:add'))
const canEdit = computed(() => userStore.has('staff:edit'))
const canLeave = computed(() => userStore.has('staff:leave'))
const canAccount = computed(() => userStore.has('staff:account'))

const loading = ref(false)
const submitting = ref(false)
const rows = ref<StaffRow[]>([])
const total = ref(0)
const stores = ref<Array<{ id: number; storeName: string }>>([])
const positions = ref<string[]>([])
const candidates = ref<Array<{ id: number; username: string; realName?: string; status: number }>>([])
const query = reactive({
  keyword: '',
  storeId: undefined as number | undefined,
  status: undefined as number | undefined,
  pageNum: 1,
  pageSize: 20
})

const editVisible = ref(false)
const accountVisible = ref(false)
const current = ref<StaffRow | null>(null)
const selectedUserId = ref<number | undefined>(undefined)

const form = reactive<StaffSave & { id?: number }>({
  id: undefined,
  staffNo: '',
  staffName: '',
  phone: '',
  storeId: undefined,
  position: '',
  entryDate: '',
  status: 1,
  userId: undefined
})

const activeCount = computed(() => rows.value.filter((r) => r.status === 1).length)

async function load() {
  loading.value = true
  try {
    const res = await staffApi.list({
      keyword: query.keyword || undefined,
      storeId: query.storeId,
      status: query.status,
      pageNum: query.pageNum,
      pageSize: query.pageSize
    })
    rows.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}
function onPage(p: number) {
  query.pageNum = p
  load()
}

async function loadMeta() {
  stores.value = await staffApi.storeOptions()
  if (canEdit.value) {
    positions.value = await staffApi.positions()
  }
}

async function loadCandidates() {
  if (!canAccount.value) return
  candidates.value = await staffApi.accountCandidates()
}

// 参数用 any：Element Plus 表格插槽给的是 DefaultRow，直接声明业务类型会报 TS2345。
// 这是项目既有页面的统一写法，进入函数后立即收窄为 StaffRow。
function openEdit(row?: any) {
  const r = row as StaffRow | undefined
  Object.assign(form, {
    id: r?.id,
    staffNo: r?.staffNo || '',
    staffName: r?.staffName || '',
    phone: r?.phone || '',
    storeId: r?.storeId,
    position: r?.position || '',
    entryDate: r?.entryDate || '',
    status: r?.status ?? 1,
    userId: undefined
  })
  loadCandidates()
  editVisible.value = true
}

async function submit() {
  if (!form.staffName.trim()) {
    ElMessage.warning('请填写员工姓名')
    return
  }
  submitting.value = true
  try {
    const payload: StaffSave = {
      staffNo: form.staffNo || undefined,
      staffName: form.staffName,
      phone: form.phone || undefined,
      storeId: form.storeId,
      position: form.position || undefined,
      entryDate: form.entryDate || undefined,
      status: form.status,
      userId: form.userId
    }
    if (form.id) {
      await staffApi.update(form.id, payload)
      ElMessage.success('员工档案已更新')
    } else {
      await staffApi.create(payload)
      ElMessage.success('员工已创建')
    }
    editVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function doLeave(row: any) {
  const { value } = await ElMessageBox.prompt(
    `为「${row.staffName}」办理离职。此操作只改员工状态，不会停用其登录账号。可填写离职原因（选填）`,
    '办理离职',
    { inputPlaceholder: '如：个人原因', confirmButtonText: '确认离职', inputValue: '' }
  )
  const res = await staffApi.leave(row.id, undefined, value)
  if (res.accountStillActive) {
    await ElMessageBox.alert(res.tip, '离职完成，账号仍需处理', { type: 'warning' })
  } else {
    ElMessage.success(res.tip)
  }
  await load()
}

async function doReinstate(row: any) {
  await ElMessageBox.confirm(`确认让「${row.staffName}」复职？`, '复职确认', { type: 'info' })
  const res = await staffApi.reinstate(row.id)
  ElMessage.success(res.tip)
  await load()
}

async function doRemove(row: any) {
  await ElMessageBox.confirm(
    `确认删除员工「${row.staffName}」？有历史业绩的员工无法删除，系统会拦下并建议改办离职。`,
    '删除确认',
    { type: 'warning' }
  )
  await staffApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

async function openAccount(row: any) {
  current.value = row
  selectedUserId.value = undefined
  await loadCandidates()
  accountVisible.value = true
}

async function doBind() {
  if (!current.value || !selectedUserId.value) return
  const res = await staffApi.bindAccount(current.value.id, selectedUserId.value)
  ElMessage.success(res.tip)
  accountVisible.value = false
  await load()
}

async function doUnbind() {
  if (!current.value) return
  await ElMessageBox.confirm('解绑后该账号不再代表这名员工，账号本身不受影响。确认解绑？', '解绑确认', {
    type: 'warning'
  })
  await staffApi.unbindAccount(current.value.id)
  ElMessage.success('已解绑')
  accountVisible.value = false
  await load()
}

onMounted(async () => {
  await Promise.all([loadMeta(), load()])
})
</script>

<style scoped>
.page-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  font-weight: 600;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.warn-line {
  margin-top: 3px;
  font-size: 12px;
  line-height: 1.5;
  color: #e6a23c;
  display: flex;
  align-items: center;
  gap: 4px;
}
.hint {
  width: 100%;
  font-size: 12px;
  line-height: 1.5;
}
</style>
