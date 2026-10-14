<template>
  <div class="page-container">
    <!-- 指标卡 -->
    <el-row :gutter="12" class="mb-12">
      <el-col :span="6" v-for="card in cards" :key="card.label">
        <div class="metric-card">
          <div class="metric-label">{{ card.label }}</div>
          <div class="metric-value" :style="{ color: card.color }">
            {{ card.prefix }}{{ card.value }}
          </div>
          <div class="metric-extra">{{ card.extra }}</div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="12" class="mb-12">
      <!-- 会员增长与消费趋势 -->
      <el-col :span="16">
        <div class="page-card">
          <div class="page-title">
            <span>会员增长与消费趋势</span>
            <el-radio-group v-model="trendDays" size="small" @change="loadTrend">
              <el-radio-button :value="7">近 7 天</el-radio-button>
              <el-radio-button :value="14">近 14 天</el-radio-button>
              <el-radio-button :value="30">近 30 天</el-radio-button>
            </el-radio-group>
          </div>
          <div ref="trendRef" class="chart chart-lg"></div>
        </div>
      </el-col>

      <!-- 等级分布 -->
      <el-col :span="8">
        <div class="page-card">
          <div class="page-title"><span>会员等级分布</span></div>
          <div ref="levelRef" class="chart chart-lg"></div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="12" class="mb-12">
      <!-- 支付方式 -->
      <el-col :span="8">
        <div class="page-card">
          <div class="page-title"><span>支付方式占比</span></div>
          <div ref="payRef" class="chart"></div>
        </div>
      </el-col>

      <!-- 留存 -->
      <el-col :span="8">
        <div class="page-card">
          <div class="page-title"><span>新客留存（按入会月份）</span></div>
          <div ref="retentionRef" class="chart"></div>
        </div>
      </el-col>

      <!-- 排行 -->
      <el-col :span="8">
        <div class="page-card">
          <div class="page-title">
            <span>商品销量排行</span>
            <el-radio-group v-model="rankType" size="small" @change="loadRank">
              <el-radio-button value="product">商品</el-radio-button>
              <el-radio-button value="store">门店</el-radio-button>
              <el-radio-button value="staff">员工</el-radio-button>
            </el-radio-group>
          </div>
          <el-table :data="rankRows" size="small" :show-header="false" v-loading="rankLoading">
            <el-table-column type="index" width="40" />
            <el-table-column prop="name" label="名称" min-width="120" show-overflow-tooltip />
            <el-table-column label="金额" width="100" align="right">
              <template #default="{ row }">¥{{ Number(row.amount).toFixed(2) }}</template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>
    </el-row>

    <!-- RFM 与沉睡会员 -->
    <el-row :gutter="12">
      <el-col :span="10">
        <div class="page-card">
          <div class="page-title"><span>RFM 客户分层</span></div>
          <el-table :data="rfmSegments" size="small" border v-loading="rfmLoading">
            <el-table-column prop="segment" label="分层" min-width="130" />
            <el-table-column prop="count" label="人数" width="80" align="right" />
            <el-table-column label="占比" width="90" align="right">
              <template #default="{ row }">{{ row.ratio }}%</template>
            </el-table-column>
          </el-table>
          <div class="text-muted mt-8" v-if="rfmMeta.total">
            共 {{ rfmMeta.total }} 位有消费记录的会员 · 频次中位数 {{ rfmMeta.freqMedian }} ·
            金额中位数 ¥{{ Number(rfmMeta.amountMedian || 0).toFixed(2) }}
          </div>
        </div>
      </el-col>

      <el-col :span="14">
        <div class="page-card">
          <div class="page-title">
            <span>沉睡会员（可发券唤醒）</span>
            <el-space>
              <el-select v-model="sleepDays" size="small" style="width: 130px" @change="loadSleeping">
                <el-option label="60 天未消费" :value="60" />
                <el-option label="90 天未消费" :value="90" />
                <el-option label="180 天未消费" :value="180" />
              </el-select>
              <el-button size="small" type="primary" plain @click="router.push('/marketing/grant')">
                去发券
              </el-button>
            </el-space>
          </div>
          <el-table :data="sleeping" size="small" border v-loading="sleepLoading" max-height="260">
            <el-table-column prop="member_no" label="会员号" width="150" />
            <el-table-column prop="nickname" label="昵称" min-width="100" />
            <el-table-column label="余额" width="100" align="right">
              <template #default="{ row }">¥{{ (Number(row.balance) + Number(row.gift_balance)).toFixed(2) }}</template>
            </el-table-column>
            <el-table-column prop="points" label="积分" width="80" align="right" />
            <el-table-column prop="last_consume_at" label="最近消费" width="160" />
          </el-table>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, shallowRef, nextTick } from 'vue'
import { useRouter } from 'vue-router'
// ECharts 按需引入：整包 `import * as echarts from 'echarts'` 约 1 MB，
// 而本页只用了 bar / line / pie 三种图 + 少量组件。
// 按需后该 chunk 从 ~1.2 MB 降到 ~400 KB，且仍随路由懒加载。
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TitleComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { reportApi } from '@/api/admin'

echarts.use([
  BarChart,
  LineChart,
  PieChart,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
  CanvasRenderer
])

const router = useRouter()
const trendDays = ref(30)
const rankType = ref<'product' | 'store' | 'staff'>('product')
const rankRows = ref<Array<Record<string, unknown>>>([])
const rankLoading = ref(false)
const rfmSegments = ref<Array<Record<string, unknown>>>([])
const rfmMeta = ref<Record<string, number>>({})
const rfmLoading = ref(false)
const sleeping = ref<Array<Record<string, unknown>>>([])
const sleepDays = ref(60)
const sleepLoading = ref(false)

const overview = ref<Record<string, Record<string, number>>>({})
const liability = ref<Record<string, number>>({})

const trendRef = ref<HTMLElement>()
const levelRef = ref<HTMLElement>()
const payRef = ref<HTMLElement>()
const retentionRef = ref<HTMLElement>()
const charts = shallowRef<echarts.ECharts[]>([])

const cards = computed(() => [
  {
    label: '会员总数',
    value: overview.value.memberOverview?.memberCount ?? 0,
    prefix: '',
    color: '#1a73e8',
    extra: `今日新增 ${overview.value.memberOverview?.newToday ?? 0} · 本月 ${overview.value.memberOverview?.newThisMonth ?? 0}`
  },
  {
    label: '近 30 天活跃率',
    value: (overview.value.memberOverview?.activeRate ?? 0) + '%',
    prefix: '',
    color: '#34a853',
    extra: `活跃 ${overview.value.memberOverview?.activeCount30d ?? 0} 人`
  },
  {
    label: '本月消费额',
    value: Number(overview.value.month?.consumeAmount ?? 0).toFixed(2),
    prefix: '¥',
    color: '#ea4335',
    extra: `本月充值 ¥${Number(overview.value.month?.rechargeAmount ?? 0).toFixed(2)}`
  },
  {
    label: '储值负债（本金）',
    value: Number(liability.value.balanceLiability ?? 0).toFixed(2),
    prefix: '¥',
    color: '#f29c1f',
    extra: `赠送负债 ¥${Number(liability.value.giftBalanceLiability ?? 0).toFixed(2)} · 积分 ${liability.value.pointsLiability ?? 0}`
  }
])

function initChart(el?: HTMLElement): echarts.ECharts | null {
  if (!el) return null
  const chart = echarts.init(el)
  charts.value.push(chart)
  return chart
}

async function loadOverview() {
  const [ov, lia] = await Promise.all([reportApi.overview({ days: 30 }), reportApi.liability()])
  overview.value = ov as Record<string, Record<string, number>>
  liability.value = lia
}

async function loadTrend() {
  const data = (await reportApi.memberTrend(trendDays.value)) as Array<Record<string, number | string>>
  const chart = echarts.getInstanceByDom(trendRef.value as HTMLElement) || initChart(trendRef.value)
  chart?.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['新增会员', '活跃会员', '消费额'] },
    grid: { left: 50, right: 60, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: data.map((d) => String(d.date).slice(5)) },
    yAxis: [
      { type: 'value', name: '人数' },
      { type: 'value', name: '金额', position: 'right' }
    ],
    series: [
      { name: '新增会员', type: 'bar', data: data.map((d) => d.newMemberCount), itemStyle: { color: '#1a73e8' } },
      { name: '活跃会员', type: 'line', smooth: true, data: data.map((d) => d.activeMemberCount), itemStyle: { color: '#34a853' } },
      {
        name: '消费额',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        data: data.map((d) => Number(d.consumeAmount)),
        itemStyle: { color: '#ea4335' }
      }
    ]
  })
}

async function loadLevelDistribution() {
  const data = (await reportApi.levelDistribution()) as Array<Record<string, number | string>>
  const chart = echarts.getInstanceByDom(levelRef.value as HTMLElement) || initChart(levelRef.value)
  chart?.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} 人 ({d}%)' },
    legend: { bottom: 0 },
    series: [
      {
        type: 'pie',
        radius: ['38%', '62%'],
        data: data.map((d) => ({ name: String(d.levelName), value: d.count })),
        label: { formatter: '{b}\n{c} 人' }
      }
    ]
  })
}

async function loadPayMethod() {
  const ov = await reportApi.overview({ days: 30 })
  const list = ((ov as Record<string, unknown>).payMethodDistribution as Array<Record<string, unknown>>) || []
  const chart = echarts.getInstanceByDom(payRef.value as HTMLElement) || initChart(payRef.value)
  chart?.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: ¥{c} ({d}%)' },
    legend: { bottom: 0 },
    color: ['#1a73e8', '#34a853', '#f29c1f', '#a142f4', '#ea4335'],
    series: [
      {
        type: 'pie',
        radius: '58%',
        data: list.map((d) => ({
          name: String(d.payMethodText || '其他'),
          value: Number(d.amount || 0)
        }))
      }
    ]
  })
}

async function loadRetention() {
  const data = (await reportApi.retention(6)) as Array<Record<string, number | string>>
  const chart = echarts.getInstanceByDom(retentionRef.value as HTMLElement) || initChart(retentionRef.value)
  chart?.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['7 日留存', '30 日留存'] },
    grid: { left: 50, right: 20, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: data.map((d) => String(d.cohort)) },
    yAxis: { type: 'value', name: '%', max: 100 },
    series: [
      { name: '7 日留存', type: 'bar', data: data.map((d) => d.retention7d), itemStyle: { color: '#4a90e2' } },
      { name: '30 日留存', type: 'line', smooth: true, data: data.map((d) => d.retention30d), itemStyle: { color: '#34a853' } }
    ]
  })
}

async function loadRank() {
  rankLoading.value = true
  try {
    let rows: Array<Record<string, unknown>> = []
    if (rankType.value === 'product') {
      rows = (await reportApi.productRanking(8)).map((r) => ({ name: r.product_name, amount: r.amount }))
    } else if (rankType.value === 'store') {
      rows = (await reportApi.storeRanking(8)).map((r) => ({ name: r.store_name, amount: r.amount }))
    } else {
      rows = (await reportApi.staffRanking(8)).map((r) => ({ name: r.staff_name || '未知', amount: r.amount }))
    }
    rankRows.value = rows
  } finally {
    rankLoading.value = false
  }
}

async function loadRfm() {
  rfmLoading.value = true
  try {
    const res = await reportApi.rfm()
    rfmSegments.value = (res.segments as Array<Record<string, unknown>>) || []
    rfmMeta.value = {
      total: Number(res.total || 0),
      freqMedian: Number(res.freqMedian || 0),
      amountMedian: Number(res.amountMedian || 0)
    }
  } finally {
    rfmLoading.value = false
  }
}

async function loadSleeping() {
  sleepLoading.value = true
  try {
    sleeping.value = await reportApi.sleepingMembers({ days: sleepDays.value, limit: 50 })
  } finally {
    sleepLoading.value = false
  }
}

function onResize() {
  charts.value.forEach((c) => c.resize())
}

onMounted(async () => {
  await nextTick()
  await Promise.all([
    loadOverview(),
    loadTrend(),
    loadLevelDistribution(),
    loadPayMethod(),
    loadRetention(),
    loadRank(),
    loadRfm(),
    loadSleeping()
  ])
  window.addEventListener('resize', onResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  charts.value.forEach((c) => c.dispose())
})
</script>

<style scoped>
.metric-card {
  background: #fff;
  border-radius: var(--card-radius);
  padding: 16px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

.metric-label {
  font-size: 13px;
  color: #909399;
}

.metric-value {
  font-size: 26px;
  font-weight: 700;
  margin: 6px 0 4px;
  font-variant-numeric: tabular-nums;
}

.metric-extra {
  font-size: 12px;
  color: #c0c4cc;
}

.chart {
  height: 240px;
  width: 100%;
}

.chart-lg {
  height: 300px;
}
</style>
