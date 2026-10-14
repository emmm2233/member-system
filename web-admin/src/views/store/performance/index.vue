<template>
  <div class="page-container">
    <div class="page-card">
      <el-form inline @submit.prevent>
        <el-form-item label="门店">
          <el-select v-model="storeId" clearable placeholder="全部门店" style="width: 170px" @change="load">
            <el-option v-for="s in stores" :key="s.id" :label="s.storeName" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="统计区间">
          <el-date-picker
            v-model="range"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            @change="load"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load"><el-icon><Search /></el-icon>统计</el-button>
          <el-button plain @click="quick(0)">本月</el-button>
          <el-button plain @click="quick(1)">上月</el-button>
          <el-button plain @click="quick(6)">近 7 天</el-button>
          <el-button plain @click="quick(29)">近 30 天</el-button>
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="业绩按员工归属，不按登录账号"
        description="代班、交接时多人共用一个收银账号也不会串业绩；员工离职后其历史业绩仍可查询。退款金额单独列出，不从业绩里做净额扣减——「卖出多少」和「退了多少」是两个可分别核对的数。"
      />
    </div>

    <div class="page-card">
      <div class="stat-row">
        <div class="stat-box">
          <div class="stat-label">有业绩员工</div>
          <div class="stat-value">{{ num(summary.activeStaff) }}</div>
        </div>
        <div class="stat-box">
          <div class="stat-label">在职总人数</div>
          <div class="stat-value muted">{{ num(summary.onDutyStaff) }}</div>
        </div>
        <div class="stat-box">
          <div class="stat-label">本区间订单数</div>
          <div class="stat-value">{{ num(summary.orderCount) }}</div>
        </div>
        <div class="stat-box">
          <div class="stat-label">本区间业绩</div>
          <div class="stat-value success">{{ money(summary.amount) }}</div>
        </div>
        <div class="stat-box">
          <div class="stat-label">客单价</div>
          <div class="stat-value">{{ money(summary.avgOrderAmount) }}</div>
        </div>
        <div class="stat-box">
          <div class="stat-label">暂无业绩员工</div>
          <div class="stat-value" :class="num(summary.noPerformanceStaff) > 0 ? 'warning' : 'muted'">
            {{ num(summary.noPerformanceStaff) }}
          </div>
        </div>
      </div>
    </div>

    <div class="page-card">
      <div class="page-title">
        <span>业绩排行</span>
        <span class="text-muted">{{ range?.[0] }} ~ {{ range?.[1] }}</span>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border size="small">
        <el-table-column label="排名" width="70" align="center">
          <template #default="{ row }">
            <span class="rank" :class="rankClass(row.rank)">{{ row.rank }}</span>
          </template>
        </el-table-column>
        <el-table-column label="员工" min-width="160">
          <template #default="{ row }">
            <div>
              {{ row.staffName }}
              <el-tag v-if="row.staffStatus === 0" size="small" type="info" effect="plain">已离职</el-tag>
            </div>
            <div class="text-muted">{{ row.staffNo }}{{ row.position ? ' · ' + row.position : '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="门店" width="140">
          <template #default="{ row }">{{ storeName(row.storeId) }}</template>
        </el-table-column>
        <el-table-column label="订单数" width="90" align="right">
          <template #default="{ row }">{{ row.orderCount }}</template>
        </el-table-column>
        <el-table-column label="业绩金额" width="130" align="right">
          <template #default="{ row }">
            <span class="amount">{{ money(row.amount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="客单价" width="110" align="right">
          <template #default="{ row }">{{ money(row.avgOrderAmount) }}</template>
        </el-table-column>
        <el-table-column label="退款金额" width="110" align="right">
          <template #default="{ row }">
            <span :class="num(row.refundAmount) > 0 ? 'danger-text' : 'text-muted'">{{ money(row.refundAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="赠送抵扣" width="110" align="right">
          <template #default="{ row }">{{ money(row.giftAmount) }}</template>
        </el-table-column>
        <el-table-column label="最近成交" width="150">
          <template #default="{ row }">{{ fmtTime(row.lastOrderAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">明细</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !rows.length" description="该区间没有业绩数据" />
    </div>

    <el-drawer v-model="detailVisible" :title="`${detail?.staffName || ''} · 业绩明细`" size="760px">
      <div v-if="detail" v-loading="detailLoading">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="工号">{{ detail.staffNo }}</el-descriptions-item>
          <el-descriptions-item label="岗位">{{ detail.position || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.statusText }}</el-descriptions-item>
          <el-descriptions-item label="订单数">{{ detail.orderCount }}</el-descriptions-item>
          <el-descriptions-item label="业绩金额">{{ money(detail.amount) }}</el-descriptions-item>
          <el-descriptions-item label="客单价">{{ money(detail.avgOrderAmount) }}</el-descriptions-item>
          <el-descriptions-item label="原价合计">{{ money(detail.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item label="优惠合计">{{ money(detail.discountAmount) }}</el-descriptions-item>
          <el-descriptions-item label="退款金额">{{ money(detail.refundAmount) }}</el-descriptions-item>
        </el-descriptions>

        <div class="section-title">日趋势</div>
        <el-table :data="detail.dailyTrend || []" size="small" border stripe max-height="220">
          <el-table-column label="日期" prop="stat_date" width="130" />
          <el-table-column label="订单数" prop="order_count" width="100" align="right" />
          <el-table-column label="金额" align="right">
            <template #default="{ row }">{{ money(row.amount) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!(detail.dailyTrend || []).length" description="无数据" :image-size="60" />

        <div class="section-title">支付方式构成</div>
        <el-table :data="detail.payMethods || []" size="small" border stripe>
          <el-table-column label="支付方式" width="130">
            <template #default="{ row }">{{ payMethodText(row.pay_method) }}</template>
          </el-table-column>
          <el-table-column label="订单数" prop="order_count" width="100" align="right" />
          <el-table-column label="金额" align="right">
            <template #default="{ row }">{{ money(row.amount) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!(detail.payMethods || []).length" description="无数据" :image-size="60" />

        <div class="section-title">最近订单</div>
        <el-table :data="detail.recentOrders || []" size="small" border stripe max-height="260">
          <el-table-column label="单号" prop="order_no" min-width="180" />
          <el-table-column label="件数" prop="item_count" width="70" align="right" />
          <el-table-column label="实付" width="100" align="right">
            <template #default="{ row }">{{ money(row.paid_amount) }}</template>
          </el-table-column>
          <el-table-column label="时间" width="150">
            <template #default="{ row }">{{ fmtTime(row.paid_at) }}</template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { staffApi } from '@/api/admin'

const loading = ref(false)
const detailLoading = ref(false)
const rows = ref<Array<Record<string, any>>>([])
const stores = ref<Array<{ id: number; storeName: string }>>([])
const storeId = ref<number | undefined>(undefined)
const summary = ref<Record<string, unknown>>({})

const detailVisible = ref(false)
const detail = ref<Record<string, any> | null>(null)

function pad(n: number): string {
  return String(n).padStart(2, '0')
}
function fmt(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
const today = new Date()
const monthStart = new Date(today.getFullYear(), today.getMonth(), 1)
const range = ref<[string, string]>([fmt(monthStart), fmt(today)])

function num(v: unknown): number {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}
function money(v: unknown): string {
  return num(v).toFixed(2)
}
function fmtTime(v?: string): string {
  return v ? String(v).replace('T', ' ').slice(0, 16) : '-'
}
function storeName(id?: number): string {
  return stores.value.find((s) => s.id === id)?.storeName || '-'
}
function rankClass(rank: number): string {
  return rank === 1 ? 'gold' : rank === 2 ? 'silver' : rank === 3 ? 'bronze' : ''
}
function payMethodText(code: number): string {
  return { 1: '会员余额', 2: '现金', 3: '微信', 4: '支付宝', 5: '银行卡', 6: '积分抵扣' }[code] || `方式${code}`
}

function quick(daysAgo: number) {
  const end = new Date()
  const start = new Date()
  start.setDate(start.getDate() - daysAgo)
  range.value = [fmt(start), fmt(end)]
  load()
}

async function load() {
  if (!range.value || range.value.length !== 2) return
  loading.value = true
  try {
    const params = {
      storeId: storeId.value,
      startDate: range.value[0],
      endDate: range.value[1]
    }
    // 汇总口径由后端统一计算，避免前端各算一套导致两个数字对不上
    const [list, sum] = await Promise.all([
      staffApi.performance({ ...params, limit: 50 }),
      staffApi.performanceSummary(params)
    ])
    rows.value = list
    summary.value = sum
  } finally {
    loading.value = false
  }
}

async function openDetail(row: Record<string, any>) {
  detailVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await staffApi.staffPerformance(row.staffId, {
      startDate: range.value[0],
      endDate: range.value[1]
    })
  } finally {
    detailLoading.value = false
  }
}

onMounted(async () => {
  stores.value = await staffApi.storeOptions()
  await load()
})
</script>

<style scoped>
.stat-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.stat-box {
  flex: 1 1 130px;
  padding: 12px 16px;
  background: #f7f9fc;
  border-radius: 6px;
}
.stat-label {
  font-size: 12px;
  color: #909399;
}
.stat-value {
  margin-top: 6px;
  font-size: 22px;
  font-weight: 600;
  color: #1a73e8;
}
.stat-value.success {
  color: #67c23a;
}
.stat-value.warning {
  color: #e6a23c;
}
.stat-value.muted {
  color: #909399;
}
.page-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  font-weight: 600;
}
.rank {
  display: inline-block;
  width: 24px;
  height: 24px;
  line-height: 24px;
  border-radius: 50%;
  background: #f0f2f5;
  color: #909399;
  font-weight: 600;
}
.rank.gold {
  background: #ffd666;
  color: #7c5c00;
}
.rank.silver {
  background: #d9dee5;
  color: #4a5568;
}
.rank.bronze {
  background: #e8b98a;
  color: #6b3f14;
}
.amount {
  font-weight: 600;
  color: #1a73e8;
}
.danger-text {
  color: #f56c6c;
}
.section-title {
  margin: 18px 0 8px;
  font-weight: 600;
  color: #303133;
}
</style>
