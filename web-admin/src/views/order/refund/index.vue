<template>
  <div class="page-container">
    <div class="page-card">
      <div class="page-title">
        <span>退单管理</span>
        <el-radio-group v-model="status" size="small" @change="load">
          <el-radio-button :value="undefined">全部</el-radio-button>
          <el-radio-button :value="1">待审核</el-radio-button>
          <el-radio-button :value="2">已通过</el-radio-button>
          <el-radio-button :value="3">已驳回</el-radio-button>
        </el-radio-group>
      </div>

      <el-table :data="rows" v-loading="loading" stripe border size="small">
        <el-table-column prop="refundNo" label="退款单号" width="190" />
        <el-table-column prop="orderNo" label="原订单号" width="200" />
        <el-table-column label="退款金额" width="110" align="right">
          <template #default="{ row }">
            <span class="amount">¥{{ fmt(row.refundAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="扣回积分" width="100" align="right">
          <template #default="{ row }">{{ row.deductPoints ?? 0 }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="退单原因" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'warning' : row.status === 2 ? 'success' : 'danger'">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="申请时间" width="170" />
        <el-table-column prop="approvedAt" label="审批时间" width="170" />
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 1">
              <el-button link type="success" size="small" @click="approve(row, true)">通过</el-button>
              <el-button link type="danger" size="small" @click="approve(row, false)">驳回</el-button>
            </template>
            <span v-else class="text-muted">已处理</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="mt-12"
        background
        layout="total, prev, pager, next"
        :total="total"
        :current-page="pageNum"
        :page-size="pageSize"
        @current-change="(p: number) => { pageNum = p; load() }"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orderApi } from '@/api'

defineOptions({ name: 'OrderRefund' })

const loading = ref(false)
const rows = ref<Array<Record<string, unknown>>>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 20
const status = ref<number | undefined>(1)

function fmt(v?: number | string | null) {
  if (v === undefined || v === null) return '0.00'
  return Number(v).toFixed(2)
}

function statusText(s: number) {
  return ({ 1: '待审核', 2: '已通过', 3: '已驳回', 4: '已撤销' } as Record<number, string>)[s] || '-'
}

async function load() {
  loading.value = true
  try {
    const res = await orderApi.refunds({ status: status.value, pageNum: pageNum.value, pageSize })
    rows.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}

async function approve(row: Record<string, unknown>, pass: boolean) {
  const refundNo = String(row.refundNo)
  const { value } = await ElMessageBox.prompt(
    pass
      ? '确认通过该退单？通过后将执行资产冲正（退回余额、扣回积分/成长值、恢复券）。'
      : '请填写驳回原因',
    pass ? '退单审批' : '驳回退单',
    {
      inputValue: pass ? '同意退款' : '',
      inputValidator: (v) => (pass ? true : !!v?.trim() || '请填写驳回原因')
    }
  )
  await orderApi.approveRefund(refundNo, pass, value)
  ElMessage.success(pass ? '退单已通过并完成冲正' : '已驳回')
  load()
}

onMounted(load)
</script>
