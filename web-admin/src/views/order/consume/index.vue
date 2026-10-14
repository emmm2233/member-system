<template>
  <div class="page-container">
    <div class="page-card">
      <el-form :model="query" inline @submit.prevent>
        <el-form-item label="订单号">
          <el-input v-model="query.orderNo" placeholder="精确匹配" clearable style="width: 200px" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="query.orderType" placeholder="全部" clearable style="width: 120px">
            <el-option label="消费" :value="1" />
            <el-option label="充值" :value="2" />
            <el-option label="售卡" :value="3" />
            <el-option label="积分兑换" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 130px">
            <el-option label="待支付" :value="1" />
            <el-option label="支付中" :value="2" />
            <el-option label="已支付" :value="3" />
            <el-option label="部分退款" :value="4" />
            <el-option label="已全额退" :value="5" />
            <el-option label="已取消" :value="6" />
            <el-option label="挂单" :value="7" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 240px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onSearch"><el-icon><Search /></el-icon>查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="page-card">
      <div class="page-title">
        <span>消费订单 <el-tag size="small" type="info" effect="plain">{{ total }} 单</el-tag></span>
        <el-button @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" stripe border size="small">
        <el-table-column prop="orderNo" label="订单号" width="200" />
        <el-table-column prop="orderTypeText" label="类型" width="90" />
        <el-table-column prop="storeName" label="门店" width="120" />
        <el-table-column label="原价" width="100" align="right">
          <template #default="{ row }">¥{{ fmt(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column label="优惠" width="120" align="right">
          <template #default="{ row }">
            <span class="amount-negative">
              -¥{{ fmt(Number(row.discountAmount) + Number(row.couponAmount) + Number(row.pointDeductAmount)) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="应收" width="100" align="right">
          <template #default="{ row }">
            <span class="amount">¥{{ fmt(row.payableAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="实收" width="100" align="right">
          <template #default="{ row }">¥{{ fmt(row.paidAmount) }}</template>
        </el-table-column>
        <el-table-column label="获得积分" width="90" align="right">
          <template #default="{ row }">{{ row.pointsGained ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="下单时间" width="170" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button
              link
              type="danger"
              size="small"
              v-if="row.status === 3 || row.status === 4"
              @click="openRefund(row)"
            >
              退单
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="mt-12"
        background
        layout="total, sizes, prev, pager, next"
        :total="total"
        :current-page="query.pageNum"
        :page-size="query.pageSize"
        @current-change="(p: number) => { query.pageNum = p; load() }"
        @size-change="(s: number) => { query.pageSize = s; query.pageNum = 1; load() }"
      />
    </div>

    <!-- 详情 -->
    <el-drawer v-model="detailVisible" title="订单详情" size="520px">
      <template v-if="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="订单号">{{ (detail.order as Record<string, unknown>).orderNo }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ detail.orderTypeText }}</el-descriptions-item>
          <el-descriptions-item label="门店">{{ detail.storeName }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.statusText }}</el-descriptions-item>
          <el-descriptions-item label="实收">¥{{ fmt((detail.order as Record<string, unknown>).paidAmount as number) }}</el-descriptions-item>
          <el-descriptions-item label="可退金额">¥{{ fmt(detail.refundableAmount as number) }}</el-descriptions-item>
          <el-descriptions-item label="下单时间">{{ (detail.order as Record<string, unknown>).createdAt }}</el-descriptions-item>
        </el-descriptions>

        <div class="panel-subtitle mt-12">商品明细</div>
        <el-table :data="(detail.items as Array<Record<string, unknown>>)" size="small" border>
          <el-table-column prop="productName" label="商品" min-width="130" />
          <el-table-column prop="price" label="单价" width="80" align="right" />
          <el-table-column prop="qty" label="数量" width="60" align="right" />
          <el-table-column prop="actualAmount" label="实付" width="90" align="right" />
        </el-table>

        <div class="panel-subtitle mt-12">支付明细</div>
        <el-table :data="(detail.payments as Array<Record<string, unknown>>)" size="small" border>
          <el-table-column label="方式" width="120">
            <template #default="{ row }">
              {{ payMethodText(Number(row.payMethod)) }}
              <el-tag v-if="row.accountType" size="small" effect="plain">
                {{ Number(row.accountType) === 1 ? '本金' : '赠送' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="金额" align="right">
            <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
          </el-table-column>
          <el-table-column prop="outTradeNo" label="外部流水号" min-width="120" />
        </el-table>

        <div class="panel-subtitle mt-12" v-if="(detail.refunds as Array<unknown>).length">退款记录</div>
        <el-table :data="(detail.refunds as Array<Record<string, unknown>>)" size="small" border>
          <el-table-column prop="refundNo" label="退款单号" width="180" />
          <el-table-column prop="refundAmount" label="金额" width="90" align="right" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">{{ refundStatusText(Number(row.status)) }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" min-width="120" />
        </el-table>
      </template>
    </el-drawer>

    <!-- 退单 -->
    <el-dialog v-model="refundVisible" title="申请退单" width="480px">
      <el-alert type="warning" :closable="false" show-icon class="mb-12">
        <template #title>
          退款按资金来源原路退回：余额支付退回余额（本金/赠送分别回退），现金/扫码支付仅生成退款记录，由门店线下退现。
        </template>
      </el-alert>
      <el-form label-width="100px">
        <el-form-item label="退款金额">
          <el-input-number v-model="refundForm.amount" :min="0.01" :max="refundForm.maxAmount" :precision="2" />
          <span class="text-muted" style="margin-left: 8px">最多 ¥{{ fmt(refundForm.maxAmount) }}</span>
        </el-form-item>
        <el-form-item label="退单原因" required>
          <el-input v-model="refundForm.reason" type="textarea" :rows="2" placeholder="必填，将写入操作日志" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="refundVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submitRefund">提交退单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { orderApi, type OrderRow } from '@/api'

defineOptions({ name: 'OrderConsume' })

const route = useRoute()
const loading = ref(false)
const submitting = ref(false)
const rows = ref<OrderRow[]>([])
const total = ref(0)
const dateRange = ref<[string, string] | null>(null)

const query = reactive({
  orderNo: '',
  orderType: undefined as number | undefined,
  status: undefined as number | undefined,
  memberId: route.query.memberId ? Number(route.query.memberId) : undefined,
  pageNum: 1,
  pageSize: 20
})

const detailVisible = ref(false)
const detail = ref<Record<string, unknown> | null>(null)

const refundVisible = ref(false)
const refundForm = reactive({ orderNo: '', amount: 0, maxAmount: 0, reason: '' })

function fmt(v?: number | string | null) {
  if (v === undefined || v === null) return '0.00'
  return Number(v).toFixed(2)
}

function statusType(status: number) {
  if (status === 3) return 'success'
  if (status === 4 || status === 5) return 'warning'
  if (status === 1 || status === 2) return 'info'
  return 'info'
}

function payMethodText(m: number) {
  return ({ 1: '储值余额', 2: '现金', 3: '线下扫码', 4: '积分抵扣', 5: '微信在线支付', 9: '其他' } as Record<number, string>)[m] || '其他'
}

function refundStatusText(s: number) {
  return ({ 1: '待审核', 2: '已通过', 3: '已驳回', 4: '已撤销' } as Record<number, string>)[s] || '-'
}

async function load() {
  loading.value = true
  try {
    const params: Record<string, unknown> = { pageNum: query.pageNum, pageSize: query.pageSize }
    if (query.orderNo) params.orderNo = query.orderNo
    if (query.orderType) params.orderType = query.orderType
    if (query.status) params.status = query.status
    if (query.memberId) params.memberId = query.memberId
    if (dateRange.value) {
      params.startDate = dateRange.value[0]
      params.endDate = dateRange.value[1]
    }
    const res = await orderApi.page(params)
    rows.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function onSearch() {
  query.pageNum = 1
  load()
}

function onReset() {
  query.orderNo = ''
  query.orderType = undefined
  query.status = undefined
  dateRange.value = null
  query.pageNum = 1
  load()
}

async function openDetail(row: any) {
  detail.value = await orderApi.detail(row.orderNo)
  detailVisible.value = true
}

function openRefund(row: any) {
  refundForm.orderNo = row.orderNo
  refundForm.maxAmount = Number(row.paidAmount) - Number(row.refundAmount || 0)
  refundForm.amount = refundForm.maxAmount
  refundForm.reason = ''
  refundVisible.value = true
}

async function submitRefund() {
  if (!refundForm.reason.trim()) {
    ElMessage.warning('退单原因必填')
    return
  }
  submitting.value = true
  try {
    const res = await orderApi.applyRefund(refundForm.orderNo, refundForm.amount, refundForm.reason)
    ElMessage.success(
      res.status === 1 ? `退单已提交待审：${res.refundNo}` : `退单已完成：${res.refundNo}`
    )
    refundVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>
