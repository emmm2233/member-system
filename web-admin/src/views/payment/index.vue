<template>
  <div class="page-container">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="mb-12"
      title="支付中心为预留能力：本期未接入真实支付渠道（无商户资质）。下方可查看支付单、渠道就绪状态与支付对账；接入微信支付后本页无需改动。"
    />

    <!-- 渠道状态 -->
    <div class="page-card">
      <div class="page-title"><span>支付渠道状态</span></div>
      <el-descriptions :column="4" border size="small">
        <el-descriptions-item label="在线支付开关">
          <el-tag :type="status.payEnabled ? 'success' : 'info'" size="small">
            {{ status.payEnabled ? '已启用' : '未启用' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="模拟支付">
          <el-tag :type="status.mockEnabled ? 'warning' : 'info'" size="small">
            {{ status.mockEnabled ? '开启（开发用）' : '关闭' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="默认渠道">{{ status.defaultChannel || '-' }}</el-descriptions-item>
        <el-descriptions-item label="超时关单">{{ status.timeoutMinutes ?? 15 }} 分钟</el-descriptions-item>
      </el-descriptions>

      <el-table :data="channels" size="small" border class="mt-12">
        <el-table-column prop="channel" label="渠道" width="120" />
        <el-table-column prop="name" label="名称" width="180" />
        <el-table-column label="就绪" width="90">
          <template #default="{ row }">
            <el-tag :type="row.ready ? 'success' : 'info'" size="small">
              {{ row.ready ? '就绪' : '未就绪' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="缺失配置" min-width="240">
          <template #default="{ row }">
            <span v-if="!row.missing || !row.missing.length" class="text-muted">-</span>
            <el-tag v-for="m in row.missing" :key="m" size="small" type="danger" effect="plain" style="margin-right: 4px">
              {{ m }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 支付对账 -->
    <div class="page-card">
      <div class="page-title">
        <span>支付对账</span>
        <el-button @click="loadReconcile"><el-icon><Refresh /></el-icon>刷新</el-button>
      </div>
      <el-alert
        :type="reconcile?.paidNotCreditedCount ? 'error' : 'success'"
        :closable="false"
        show-icon
        :title="String(reconcile?.conclusion || '')"
        :description="`已支付未入账支付单：${reconcile?.paidNotCreditedCount ?? 0} 笔`"
      />
    </div>

    <!-- 支付单列表 -->
    <div class="page-card">
      <div class="page-title">
        <span>支付单 <el-tag size="small" type="info" effect="plain">{{ total }} 笔</el-tag></span>
        <el-space>
          <el-select v-model="query.channel" placeholder="渠道" clearable size="small" style="width: 120px" @change="load">
            <el-option label="MOCK 模拟" value="MOCK" />
            <el-option label="WECHAT 微信" value="WECHAT" />
          </el-select>
          <el-select v-model="query.status" placeholder="状态" clearable size="small" style="width: 130px" @change="load">
            <el-option label="待支付" :value="1" />
            <el-option label="支付中" :value="2" />
            <el-option label="已支付" :value="3" />
            <el-option label="已关闭" :value="4" />
          </el-select>
        </el-space>
      </div>

      <el-table :data="rows" v-loading="loading" stripe border size="small">
        <el-table-column prop="paymentNo" label="支付单号" width="200" />
        <el-table-column prop="bizNo" label="业务单号" width="200" />
        <el-table-column prop="channel" label="渠道" width="90" />
        <el-table-column label="金额" width="110" align="right">
          <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="statusText" label="状态" width="100" />
        <el-table-column label="已入账" width="90">
          <template #default="{ row }">
            <el-tag :type="row.accountCredited ? 'success' : 'info'" size="small">
              {{ row.accountCredited ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="notifyCount" label="回调次数" width="90" align="center" />
        <el-table-column prop="createdAt" label="创建时间" width="170" />
      </el-table>
      <el-empty v-if="!rows.length && !loading" description="暂无支付单（本期未接入真实支付渠道）" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { paymentApi } from '@/api'

const loading = ref(false)
const rows = ref<Array<Record<string, unknown>>>([])
const total = ref(0)
const status = ref<Record<string, unknown>>({})
const channels = ref<Array<Record<string, unknown>>>([])
const reconcile = ref<Record<string, unknown> | null>(null)
const query = reactive({ channel: undefined as string | undefined, status: undefined as number | undefined })

function fmt(v?: number | string | null) {
  if (v === undefined || v === null) return '0.00'
  return Number(v).toFixed(2)
}

async function load() {
  loading.value = true
  try {
    const res = await paymentApi.page({ ...query, pageNum: 1, pageSize: 20 })
    rows.value = res.list
    total.value = res.total
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function loadReconcile() {
  reconcile.value = await paymentApi.reconcile()
}

onMounted(async () => {
  const s = await paymentApi.channels()
  status.value = s
  channels.value = (s.channels as Array<Record<string, unknown>>) || []
  await Promise.all([load(), loadReconcile()])
})
</script>
