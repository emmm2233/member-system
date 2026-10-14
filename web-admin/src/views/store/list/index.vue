<template>
  <div class="page-container">
    <div class="page-card">
      <el-form inline @submit.prevent>
        <el-form-item label="关键词">
          <el-input
            v-model="keyword"
            placeholder="门店名称 / 编码 / 城市"
            clearable
            style="width: 200px"
            @keyup.enter="load"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="status" clearable placeholder="全部" style="width: 130px" @change="load">
            <el-option label="营业中" :value="1" />
            <el-option label="已停业" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load"><el-icon><Search /></el-icon>查询</el-button>
          <el-button type="primary" plain @click="openEdit()" v-if="canEdit">
            <el-icon><Plus /></el-icon>新增门店
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="page-card">
      <div class="page-title">门店列表 <el-tag size="small" type="info" effect="plain">{{ rows.length }} 家</el-tag></div>
      <el-table :data="rows" v-loading="loading" stripe border size="small">
        <el-table-column label="门店" min-width="200">
          <template #default="{ row }">
            <div>{{ row.storeName }}</div>
            <div class="text-muted">{{ row.storeCode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="地址" min-width="220">
          <template #default="{ row }">
            <div>{{ [row.province, row.city, row.district].filter(Boolean).join(' ') || '-' }}</div>
            <div class="text-muted">{{ row.address || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="联系人" width="150">
          <template #default="{ row }">
            <div>{{ row.contactName || '-' }}</div>
            <div class="text-muted">{{ row.contactPhone || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="营业时间" width="130">
          <template #default="{ row }">{{ row.businessHours || '-' }}</template>
        </el-table-column>
        <el-table-column label="店长" width="110">
          <template #default="{ row }">
            <span :class="row.managerName ? '' : 'text-muted'">{{ row.managerName || '未设置' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="在职员工" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.staffCount > 0 ? 'success' : 'info'" effect="plain">
              {{ row.staffCount }} 人
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)" v-if="canEdit">编辑</el-button>
            <el-button
              v-if="canEdit"
              link
              :type="row.status === 1 ? 'warning' : 'success'"
              size="small"
              @click="toggleStatus(row)"
            >
              {{ row.status === 1 ? '停业' : '营业' }}
            </el-button>
            <el-button
              link
              type="danger"
              size="small"
              @click="doRemove(row)"
              v-if="canEdit && row.deletable !== false"
            >
              删除
            </el-button>
            <el-tooltip
              v-else-if="canEdit"
              :content="row.deleteBlockReason || '仍被员工/订单/会员/账号引用，请改用停业'"
              placement="top"
            >
              <span><el-button link type="info" size="small" disabled>删除</el-button></span>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-drawer v-model="editVisible" :title="form.id ? '编辑门店' : '新增门店'" size="580px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="门店编码">
          <el-input v-model="form.storeCode" placeholder="留空自动生成 S001 / S002…" />
        </el-form-item>
        <el-form-item label="门店名称" required>
          <el-input v-model="form.storeName" maxlength="100" />
        </el-form-item>
        <el-form-item label="所在地区">
          <div class="region-row">
            <el-input v-model="form.province" placeholder="省" />
            <el-input v-model="form.city" placeholder="市" />
            <el-input v-model="form.district" placeholder="区" />
          </div>
        </el-form-item>
        <el-form-item label="详细地址">
          <el-input v-model="form.address" maxlength="255" />
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="form.contactName" maxlength="50" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="form.contactPhone" placeholder="手机号或座机" />
        </el-form-item>
        <el-form-item label="营业时间">
          <el-input v-model="form.businessHours" placeholder="如 09:00-22:00" maxlength="100" />
        </el-form-item>
        <el-form-item label="店长">
          <el-select v-model="form.managerId" clearable filterable placeholder="从员工中选（选填）" style="width: 100%">
            <el-option
              v-for="s in staffOptions"
              :key="s.id"
              :label="`${s.staffName}（${s.staffNo}${s.position ? ' · ' + s.position : ''}）`"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">营业中</el-radio>
            <el-radio :value="0">停业</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortNo" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="submit" :loading="submitting">保存</el-button>
          <el-button @click="editVisible = false">取消</el-button>
        </el-form-item>
      </el-form>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { staffApi, storeApi, type StoreRow } from '@/api/admin'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const canEdit = computed(() => userStore.has('store:edit'))

const loading = ref(false)
const submitting = ref(false)
const rows = ref<StoreRow[]>([])
const staffOptions = ref<Array<{ id: number; staffName: string; staffNo: string; position?: string }>>([])
const keyword = ref('')
const status = ref<number | undefined>(undefined)

const editVisible = ref(false)
const form = reactive<Record<string, any>>({
  id: undefined,
  storeCode: '',
  storeName: '',
  province: '',
  city: '',
  district: '',
  address: '',
  contactName: '',
  contactPhone: '',
  businessHours: '',
  managerId: undefined,
  status: 1,
  sortNo: 0
})

async function load() {
  loading.value = true
  try {
    rows.value = await storeApi.list({ keyword: keyword.value || undefined, status: status.value })
  } finally {
    loading.value = false
  }
}

function openEdit(row?: any) {
  Object.assign(form, {
    id: row?.id,
    storeCode: row?.storeCode || '',
    storeName: row?.storeName || '',
    province: row?.province || '',
    city: row?.city || '',
    district: row?.district || '',
    address: row?.address || '',
    contactName: row?.contactName || '',
    contactPhone: row?.contactPhone || '',
    businessHours: row?.businessHours || '',
    managerId: row?.managerId,
    status: row?.status ?? 1,
    sortNo: row?.sortNo ?? 0
  })
  editVisible.value = true
}

async function submit() {
  if (!form.storeName.trim()) {
    ElMessage.warning('请填写门店名称')
    return
  }
  submitting.value = true
  try {
    const payload = {
      storeCode: form.storeCode || undefined,
      storeName: form.storeName,
      province: form.province || undefined,
      city: form.city || undefined,
      district: form.district || undefined,
      address: form.address || undefined,
      contactName: form.contactName || undefined,
      contactPhone: form.contactPhone || undefined,
      businessHours: form.businessHours || undefined,
      managerId: form.managerId,
      status: form.status,
      sortNo: form.sortNo
    }
    if (form.id) {
      await storeApi.update(form.id, payload)
      ElMessage.success('门店已更新')
    } else {
      await storeApi.create(payload)
      ElMessage.success('门店已创建')
    }
    editVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function toggleStatus(row: any) {
  const next = row.status === 1 ? 0 : 1
  await storeApi.changeStatus(row.id, next)
  ElMessage.success(next === 1 ? '门店已营业' : '门店已停业（历史数据保留）')
  await load()
}

async function doRemove(row: any) {
  await ElMessageBox.confirm(
    `确认删除门店「${row.storeName}」？门店是数据权限锚点，只要还有在职员工就会被拒绝，届时请改为「停业」。`,
    '删除确认',
    { type: 'warning' }
  )
  await storeApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(async () => {
  await load()
  staffOptions.value = await staffApi.options()
})
</script>

<style scoped>
.page-title {
  margin-bottom: 12px;
  font-weight: 600;
}
.region-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
</style>
