<template>
  <div class="page-container">
    <div class="page-card">
      <el-form inline @submit.prevent>
        <el-form-item label="券名称">
          <el-input v-model="keyword" placeholder="搜索券名称" clearable style="width: 200px" @keyup.enter="load" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="onlyEnabled" @change="load">仅启用</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load"><el-icon><Search /></el-icon>查询</el-button>
          <el-button type="primary" plain @click="openEdit()" v-if="userStore.has('coupon:tpl:add')">
            <el-icon><Plus /></el-icon>新建券模板
          </el-button>
          <el-button @click="router.push('/marketing/grant')">发券向导</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="page-card">
      <div class="page-title">
        <span>优惠券模板 <el-tag size="small" type="info" effect="plain">{{ rows.length }} 个</el-tag></span>
      </div>

      <el-table :data="rows" v-loading="loading" stripe border size="small">
        <el-table-column label="券" min-width="200">
          <template #default="{ row }">
            <div class="tpl-cell">
              <div class="tpl-face" :class="faceClass(row.couponType)">
                {{ faceText(row) }}
              </div>
              <div>
                <div class="tpl-name">{{ row.tplName }}</div>
                <div class="text-muted">{{ typeText(row.couponType) }} · {{ row.tplCode }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="使用条件" min-width="150">
          <template #default="{ row }">
            <div>{{ Number(row.minAmount) > 0 ? `满 ${fmt(row.minAmount)} 元可用` : '无门槛' }}</div>
            <div class="text-muted">{{ row.useRuleDesc || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="有效期" width="170">
          <template #default="{ row }">
            <span v-if="row.validType === 2">领取后 {{ row.validDays }} 天</span>
            <span v-else>{{ (row.validStart || '').slice(0, 10) }} ~ {{ (row.validEnd || '').slice(0, 10) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="发行/已发/已用" width="130" align="right">
          <template #default="{ row }">
            <div>{{ row.totalQty < 0 ? '不限' : row.totalQty }} / {{ row.issuedQty }} / {{ row.usedQty }}</div>
            <el-progress
              v-if="row.issuedQty > 0"
              :percentage="Math.round((row.usedQty / row.issuedQty) * 100)"
              :stroke-width="4"
              :show-text="false"
            />
          </template>
        </el-table-column>
        <el-table-column label="限领" width="70" align="center">
          <template #default="{ row }">{{ row.limitPerMember }} 张</template>
        </el-table-column>
        <el-table-column label="属性" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.allowStack" size="small" type="success" effect="plain">可叠加</el-tag>
            <el-tag v-if="row.claimable" size="small" type="warning" effect="plain">可自领</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)" v-if="userStore.has('coupon:tpl:edit')">
              编辑
            </el-button>
            <el-button link type="primary" size="small" @click="viewStat(row)">效果</el-button>
            <el-button
              link
              :type="row.status === 1 ? 'warning' : 'success'"
              size="small"
              @click="toggleStatus(row)"
              v-if="userStore.has('coupon:tpl:edit')"
            >
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" size="small" @click="remove(row)" v-if="userStore.has('coupon:tpl:edit')">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 编辑抽屉 -->
    <el-drawer v-model="editVisible" :title="form.tplName ? '编辑券模板' : '新建券模板'" size="640px">
      <el-form :model="form" label-width="120px">
        <el-divider content-position="left">基本信息</el-divider>
        <el-form-item label="券名称" required>
          <el-input v-model="form.tplName" placeholder="如：满 100 减 20" maxlength="50" show-word-limit />
        </el-form-item>
        <el-form-item label="券类型" required>
          <el-radio-group v-model="form.couponType" @change="onTypeChange">
            <el-radio-button :value="1">满减券</el-radio-button>
            <el-radio-button :value="2">折扣券</el-radio-button>
            <el-radio-button :value="3">代金券</el-radio-button>
            <el-radio-button :value="4">兑换券</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="form.couponType === 2 ? '折扣值' : '面额'" required>
          <el-input-number
            v-model="form.faceValue"
            :precision="form.couponType === 2 ? 2 : 2"
            :step="form.couponType === 2 ? 0.05 : 10"
            :min="0.01"
            :max="form.couponType === 2 ? 1 : 100000"
          />
          <span class="text-muted" style="margin-left: 8px">
            {{ form.couponType === 2 ? '如 0.88 表示 88 折' : '单位：元' }}
          </span>
        </el-form-item>
        <el-form-item label="最低消费">
          <el-input-number v-model="form.minAmount" :precision="2" :min="0" :step="10" />
          <span class="text-muted" style="margin-left: 8px">0 表示无门槛</span>
        </el-form-item>
        <el-form-item label="折扣封顶" v-if="form.couponType === 2">
          <el-input-number v-model="form.maxDiscount" :precision="2" :min="0" :step="10" />
          <span class="text-muted" style="margin-left: 8px">折扣券最高优惠金额</span>
        </el-form-item>

        <el-divider content-position="left">有效期</el-divider>
        <el-form-item label="有效期类型">
          <el-radio-group v-model="form.validType">
            <el-radio :value="2">领取后 N 天</el-radio>
            <el-radio :value="1">固定区间</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="有效天数" v-if="form.validType === 2">
          <el-input-number v-model="form.validDays" :min="1" :max="3650" />
        </el-form-item>
        <template v-else>
          <el-form-item label="开始时间">
            <el-date-picker v-model="form.validStart" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" />
          </el-form-item>
          <el-form-item label="结束时间">
            <el-date-picker v-model="form.validEnd" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" />
          </el-form-item>
        </template>

        <el-divider content-position="left">发行与限领</el-divider>
        <el-form-item label="发行总量">
          <el-input-number v-model="form.totalQty" :min="-1" :step="100" />
          <span class="text-muted" style="margin-left: 8px">-1 表示不限量</span>
        </el-form-item>
        <el-form-item label="每人限领">
          <el-input-number v-model="form.limitPerMember" :min="1" :max="999" />
        </el-form-item>

        <el-divider content-position="left">适用范围</el-divider>
        <el-form-item label="适用门店">
          <el-select v-model="form.scopeStoreIds" multiple placeholder="不选表示全部门店" style="width: 100%">
            <el-option v-for="s in stores" :key="s.id" :label="s.storeName" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="适用商品">
          <el-select v-model="form.scopeProductIds" multiple placeholder="不选表示全部商品" style="width: 100%">
            <el-option v-for="p in products" :key="p.id" :label="p.productName" :value="p.id" />
          </el-select>
        </el-form-item>

        <el-divider content-position="left">叠加与领取</el-divider>
        <el-form-item label="可叠加">
          <el-switch v-model="form.allowStack" />
          <span class="text-muted" style="margin-left: 8px">开启后可与同单其他券共用</span>
        </el-form-item>
        <el-form-item label="互斥组" v-if="!form.allowStack">
          <el-input v-model="form.stackGroup" placeholder="同组券不可共用，可留空" style="width: 200px" />
        </el-form-item>
        <el-form-item label="小程序自助领取">
          <el-switch v-model="form.claimable" />
        </el-form-item>
        <el-form-item label="使用说明">
          <el-input v-model="form.useRuleDesc" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-drawer>

    <!-- 效果统计 -->
    <el-dialog v-model="statVisible" title="券效果统计" width="460px">
      <el-descriptions v-if="stat" :column="1" border size="small">
        <el-descriptions-item label="券名称">{{ stat.tplName }}</el-descriptions-item>
        <el-descriptions-item label="累计发放">{{ stat.issuedQty }} 张</el-descriptions-item>
        <el-descriptions-item label="券实例数">{{ stat.couponCount }} 张</el-descriptions-item>
        <el-descriptions-item label="已核销">
          {{ stat.usedCount }} 张（核销率 {{ stat.useRate }}%）
        </el-descriptions-item>
        <el-descriptions-item label="未使用">{{ stat.unusedCount }} 张</el-descriptions-item>
        <el-descriptions-item label="已过期">{{ stat.expiredCount }} 张</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { couponApi, type CouponTplRow, type CouponTplSave } from '@/api/admin'
import { productApi, type Product } from '@/api'
import { http } from '@/api/request'
import { useUserStore } from '@/store/user'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const submitting = ref(false)
const rows = ref<CouponTplRow[]>([])
const keyword = ref('')
const onlyEnabled = ref(false)
const products = ref<Product[]>([])
const stores = ref<Array<{ id: number; storeName: string }>>([])

const editVisible = ref(false)
const statVisible = ref(false)
const stat = ref<Record<string, number | string> | null>(null)

const emptyForm = (): CouponTplSave => ({
  tplName: '',
  couponType: 1,
  faceValue: 20,
  minAmount: 0,
  validType: 2,
  validDays: 30,
  totalQty: -1,
  limitPerMember: 1,
  scopeStoreIds: [],
  scopeProductIds: [],
  excludeProductIds: [],
  allowStack: false,
  claimable: false,
  status: 1,
  sortNo: 0
})
const form = reactive<CouponTplSave>(emptyForm())

function fmt(v?: number | string | null) {
  if (v === undefined || v === null) return '0.00'
  return Number(v).toFixed(2)
}

function faceText(row: any) {
  if (row.couponType === 2) {
    const rate = Number(row.faceValue) > 1 ? Number(row.faceValue) / 100 : Number(row.faceValue)
    return (rate * 10).toFixed(1).replace(/\.0$/, '') + '折'
  }
  return '¥' + fmt(row.faceValue)
}

function faceClass(type: number) {
  return ({ 1: 'face-cut', 2: 'face-discount', 3: 'face-cash', 4: 'face-exchange' } as Record<number, string>)[type] || 'face-cash'
}

function typeText(type: number) {
  return ({ 1: '满减券', 2: '折扣券', 3: '代金券', 4: '兑换券', 5: '次卡券' } as Record<number, string>)[type] || '优惠券'
}

function onTypeChange() {
  if (form.couponType === 2) {
    form.faceValue = 0.88
    form.maxDiscount = 50
  } else {
    form.faceValue = 20
    form.maxDiscount = undefined
  }
}

async function load() {
  loading.value = true
  try {
    rows.value = await couponApi.list({ keyword: keyword.value || undefined, onlyEnabled: onlyEnabled.value })
  } finally {
    loading.value = false
  }
}

function openEdit(row?: any) {
  Object.assign(form, emptyForm())
  if (row) {
    Object.assign(form, {
      id: row.id,
      tplName: row.tplName,
      tplCode: row.tplCode,
      couponType: row.couponType,
      faceValue: Number(row.faceValue),
      minAmount: Number(row.minAmount),
      maxDiscount: row.maxDiscount ? Number(row.maxDiscount) : undefined,
      validType: row.validType,
      validStart: row.validStart,
      validEnd: row.validEnd,
      validDays: row.validDays,
      totalQty: row.totalQty,
      limitPerMember: row.limitPerMember,
      scopeStoreIds: parseIds(row.scopeStore),
      scopeProductIds: parseIds(row.scopeProduct),
      allowStack: row.allowStack === 1,
      stackGroup: row.stackGroup,
      claimable: row.claimable === 1,
      useRuleDesc: row.useRuleDesc,
      status: row.status,
      sortNo: row.sortNo
    })
  }
  editVisible.value = true
}

function parseIds(csv?: string): number[] {
  if (!csv || csv === '-1') return []
  return csv.split(',').map((s) => Number(s.trim())).filter((n) => Number.isFinite(n) && n > 0)
}

async function submit() {
  if (!form.tplName.trim()) {
    ElMessage.warning('请填写券名称')
    return
  }
  submitting.value = true
  try {
    if (form.id) {
      await couponApi.update(form.id, { ...form })
      ElMessage.success('券模板已更新（已发放的券不受影响）')
    } else {
      await couponApi.create({ ...form })
      ElMessage.success('券模板已创建')
    }
    editVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function toggleStatus(row: any) {
  await couponApi.changeStatus(row.id, row.status === 1 ? 0 : 1)
  ElMessage.success(row.status === 1 ? '已停用' : '已启用')
  load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确认删除券模板「${row.tplName}」？`, '删除确认', { type: 'warning' })
  await couponApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

async function viewStat(row: any) {
  stat.value = (await couponApi.statistics(row.id)) as Record<string, number | string>
  statVisible.value = true
}

onMounted(async () => {
  const [p, s] = await Promise.all([
    productApi.all().catch(() => [] as Product[]),
    http.get<Array<{ id: number; storeName: string }>>('/admin/report/report/store-options').catch(() => [])
  ])
  products.value = p
  stores.value = s
  load()
})
</script>

<style scoped>
.tpl-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.tpl-face {
  width: 76px;
  height: 44px;
  border-radius: 6px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 15px;
  flex-shrink: 0;
}

.face-cut {
  background: linear-gradient(135deg, #1a73e8, #4a90e2);
}

.face-discount {
  background: linear-gradient(135deg, #a142f4, #c77dff);
}

.face-cash {
  background: linear-gradient(135deg, #f29c1f, #ffb74d);
}

.face-exchange {
  background: linear-gradient(135deg, #34a853, #66bb6a);
}

.tpl-name {
  font-weight: 600;
}
</style>
