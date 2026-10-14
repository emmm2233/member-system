<template>
  <div class="page-container">
    <!-- 资产概览 -->
    <el-row :gutter="12" class="mb-12">
      <el-col :span="6" v-for="card in statCards" :key="card.label">
        <div class="stat-card">
          <div class="stat-label">{{ card.label }}</div>
          <div class="stat-value" :style="{ color: card.color }">
            {{ card.prefix }}{{ card.value }}
          </div>
          <div class="stat-extra">{{ card.extra }}</div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="12">
      <!-- 今日概览 -->
      <el-col :span="12">
        <div class="page-card">
          <div class="page-title">
            <span>经营数据</span>
            <el-button link type="primary" :loading="loading" @click="loadData">
              <el-icon><Refresh /></el-icon>刷新
            </el-button>
          </div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="今日新增会员">{{ today.newMemberCount ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="今日活跃会员">{{ today.activeMemberCount ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="今日消费额">
              <span class="amount">¥{{ fmt(today.consumeAmount) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="今日充值额">
              <span class="amount">¥{{ fmt(today.rechargeAmount) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="今日订单数">{{ today.consumeCount ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="今日退款额">
              <span class="amount">¥{{ fmt(today.refundAmount) }}</span>
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </el-col>

      <!-- 对账与负债 -->
      <el-col :span="12">
        <div class="page-card">
          <div class="page-title">
            <span>账务健康度</span>
            <el-tag :type="reconcile?.diffCount ? 'danger' : 'success'" size="small">
              {{ reconcile?.diffCount ? '存在差异' : '账实相符' }}
            </el-tag>
          </div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="储值负债(本金)">
              <span class="amount">¥{{ fmt(liability.balanceLiability) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="赠送余额负债">
              <span class="amount">¥{{ fmt(liability.giftBalanceLiability) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="积分负债">{{ liability.pointsLiability ?? 0 }} 分</el-descriptions-item>
            <el-descriptions-item label="账户总数">{{ liability.memberCount ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="对账时间" :span="2">
              {{ reconcile?.checkTime || '-' }}
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </el-col>
    </el-row>

    <!-- 快捷入口 -->
    <div class="page-card">
      <div class="page-title"><span>快捷操作</span></div>
      <el-space wrap :size="12">
        <el-button type="primary" size="large" @click="router.push('/cashier')" v-if="userStore.has('order:add')">
          <el-icon><ShoppingCart /></el-icon>进入收银台
        </el-button>
        <el-button size="large" @click="router.push('/member/list')" v-if="userStore.has('member:list')">
          <el-icon><User /></el-icon>会员管理
        </el-button>
        <el-button size="large" @click="router.push('/order/consume')" v-if="userStore.has('order:list')">
          <el-icon><List /></el-icon>订单查询
        </el-button>
        <el-button size="large" @click="router.push('/account/reconcile')" v-if="userStore.has('account:reconcile')">
          <el-icon><Wallet /></el-icon>对账中心
        </el-button>
        <el-button size="large" @click="router.push('/payment/index')" v-if="userStore.has('payment:list')">
          <el-icon><CreditCard /></el-icon>支付单(预留)
        </el-button>
      </el-space>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { accountApi } from '@/api'
import { useUserStore } from '@/store/user'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const today = ref<Record<string, number>>({})
const liability = ref<Record<string, number>>({})
const reconcile = ref<{ diffCount: number; checkTime: string; conclusion: string } | null>(null)

const statCards = computed(() => [
  {
    label: '储值负债（本金）',
    value: fmt(liability.value.balanceLiability),
    prefix: '¥',
    color: '#1a73e8',
    extra: '可退本金余额合计'
  },
  {
    label: '赠送余额负债',
    value: fmt(liability.value.giftBalanceLiability),
    prefix: '¥',
    color: '#f29c1f',
    extra: '不可退，仅用于消费抵扣'
  },
  {
    label: '积分负债',
    value: liability.value.pointsLiability ?? 0,
    prefix: '',
    color: '#34a853',
    extra: '含即将过期积分'
  },
  {
    label: '今日消费额',
    value: fmt(today.value.consumeAmount),
    prefix: '¥',
    color: '#ea4335',
    extra: `订单数 ${today.value.consumeCount ?? 0}`
  }
])

function fmt(v?: number | string) {
  if (v === undefined || v === null) return '0.00'
  return Number(v).toFixed(2)
}

async function loadData() {
  loading.value = true
  try {
    const [rec, lia] = await Promise.all([accountApi.reconcile(), accountApi.liability()])
    reconcile.value = rec
    liability.value = lia as unknown as Record<string, number>
    // 经营数据取自门店日结（工作台简化版：用当日门店汇总）
    const storeId = userStore.userInfo?.storeId
    if (storeId) {
      const { orderApi } = await import('@/api')
      const sum = (await orderApi.shiftSummary(storeId)) as Record<string, number>
      today.value = {
        newMemberCount: Number(sum.newMemberCount ?? 0),
        activeMemberCount: Number(sum.orderCount ?? 0),
        consumeAmount: Number(sum.paidAmount ?? 0),
        rechargeAmount: Number(sum.rechargeAmount ?? 0),
        consumeCount: Number(sum.orderCount ?? 0),
        refundAmount: Number(sum.refundAmount ?? 0)
      }
    }
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.stat-card {
  background: #fff;
  border-radius: var(--card-radius);
  padding: 16px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

.stat-label {
  font-size: 13px;
  color: #909399;
}

.stat-value {
  font-size: 26px;
  font-weight: 700;
  margin: 6px 0 4px;
  font-variant-numeric: tabular-nums;
}

.stat-extra {
  font-size: 12px;
  color: #c0c4cc;
}
</style>
