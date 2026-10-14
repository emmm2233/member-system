<template>
  <view class="container">
    <!-- 筛选 -->
    <view class="tabs">
      <view
        v-for="t in tabs"
        :key="t.label"
        class="tab"
        :class="{ active: currentType === t.value }"
        @tap="switchType(t.value)"
      >
        {{ t.label }}
      </view>
    </view>

    <!-- 月份分组列表 -->
    <block v-if="groupedList.length">
      <view v-for="group in groupedList" :key="group.month" class="month-group">
        <view class="month-head">
          <text class="month-title">{{ group.month }}</text>
          <text class="month-sum">
            支出 ¥{{ fmtMoney(group.expense) }}<text v-if="group.income > 0"> · 收入 ¥{{ fmtMoney(group.income) }}</text>
          </text>
        </view>

        <view
          v-for="row in group.rows"
          :key="row.orderNo"
          class="bill-item"
          @tap="openDetail(row)"
        >
          <view class="bill-icon" :class="iconClass(row.orderType)">
            {{ iconText(row.orderType) }}
          </view>
          <view class="bill-main">
            <view class="bill-title">
              <text>{{ row.itemSummary || row.orderTypeText }}</text>
            </view>
            <view class="bill-meta">
              <text>{{ row.storeName || '门店' }}</text>
              <text class="bill-time">{{ shortTime(row.createdAt) }}</text>
            </view>
            <view class="bill-extra" v-if="row.pointsGained > 0 || row.discountAmount > 0">
              <text v-if="row.discountAmount > 0">已优惠 ¥{{ fmtMoney(row.discountAmount) }}</text>
              <text v-if="row.pointsGained > 0"> · 获得 {{ row.pointsGained }} 积分</text>
            </view>
          </view>
          <view class="bill-amount">
            <text :class="amountClass(row)">{{ amountText(row) }}</text>
            <text class="bill-status" v-if="row.statusText !== '已支付'">{{ row.statusText }}</text>
          </view>
        </view>
      </view>

      <view class="list-end" v-if="!hasMore">已加载全部 {{ total }} 笔</view>
      <view class="list-end" v-else @tap="loadMore">加载更多</view>
    </block>

    <view v-else class="empty">
      <text>暂无账单记录</text>
      <view class="empty-tip">到店消费后可在此查看明细</view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import { mpApi, type BillRow } from '@/api'
import { fmtMoney, shortTime } from '@/utils/format'

const tabs: Array<{ label: string; value?: number }> = [
  { label: '全部', value: undefined },
  { label: '消费', value: 1 },
  { label: '充值', value: 2 },
  { label: '售卡', value: 3 },
  { label: '兑换', value: 4 }
]

const currentType = ref<number | undefined>(undefined)
const list = ref<BillRow[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 20
const hasMore = ref(true)

/** 按月份分组并统计收支 */
const groupedList = computed(() => {
  const map = new Map<string, { month: string; rows: BillRow[]; expense: number; income: number }>()
  for (const row of list.value) {
    const month = row.month || (row.createdAt || '').slice(0, 7)
    if (!map.has(month)) {
      map.set(month, { month, rows: [], expense: 0, income: 0 })
    }
    const g = map.get(month)!
    g.rows.push(row)
    const amt = Number(row.paidAmount || 0)
    if (row.status === 5 || row.status === 4) {
      // 退款计入收入
      g.income += Number(row.refundAmount || 0)
    } else if (row.orderType === 2) {
      g.income += amt
    } else {
      g.expense += amt
    }
  }
  return Array.from(map.values())
})

function fmt(v?: number | string | null) {
  return fmtMoney(v)
}

function iconText(type: number) {
  return ({ 1: '消', 2: '充', 3: '卡', 4: '兑' } as Record<number, string>)[type] || '单'
}

function iconClass(type: number) {
  return ({
    1: 'icon-consume',
    2: 'icon-recharge',
    3: 'icon-card',
    4: 'icon-exchange'
  } as Record<number, string>)[type] || 'icon-consume'
}

function amountText(row: BillRow) {
  if (row.orderType === 2) {
    return `+¥${fmt(row.paidAmount || row.giftAmount)}`
  }
  return `-¥${fmt(row.paidAmount)}`
}

function amountClass(row: BillRow) {
  return row.orderType === 2 ? 'amount text-success' : 'amount text-danger'
}

async function load(reset = false) {
  if (reset) {
    pageNum.value = 1
    hasMore.value = true
  }
  try {
    const res = await mpApi.orders({
      orderType: currentType.value,
      pageNum: pageNum.value,
      pageSize
    })
    list.value = reset ? res.list : [...list.value, ...res.list]
    total.value = res.total
    hasMore.value = list.value.length < res.total
  } catch (e) {
    console.warn('[bill] 加载失败', e)
  }
}

function loadMore() {
  if (!hasMore.value) return
  pageNum.value += 1
  load(false)
}

function switchType(value?: number) {
  currentType.value = value
  load(true)
}

function openDetail(row: BillRow) {
  uni.navigateTo({ url: `/pages/bill/detail?orderNo=${row.orderNo}` })
}

onShow(() => {
  load(true)
})

onPullDownRefresh(async () => {
  await load(true)
  uni.stopPullDownRefresh()
})

onReachBottom(() => {
  loadMore()
})
</script>

<style scoped>
.tabs {
  display: flex;
  background: #fff;
  padding: 0 12rpx;
  position: sticky;
  top: 0;
  z-index: 10;
}

.tab {
  flex: 1;
  text-align: center;
  padding: 26rpx 0;
  font-size: 28rpx;
  color: #606266;
  position: relative;
}

.tab.active {
  color: #1a73e8;
  font-weight: 600;
}

.tab.active::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 40rpx;
  height: 6rpx;
  background: #1a73e8;
  border-radius: 4rpx;
}

.month-group {
  margin-top: 16rpx;
}

.month-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 32rpx;
  background: #f5f7fa;
}

.month-title {
  font-size: 26rpx;
  font-weight: 600;
  color: #606266;
}

.month-sum {
  font-size: 22rpx;
  color: #909399;
}

.bill-item {
  display: flex;
  align-items: center;
  background: #fff;
  padding: 24rpx 32rpx;
  border-bottom: 2rpx solid #f5f7fa;
}

.bill-icon {
  width: 72rpx;
  height: 72rpx;
  border-radius: 20rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28rpx;
  font-weight: 600;
  margin-right: 20rpx;
  flex-shrink: 0;
}

.icon-consume {
  background: #e8f0fe;
  color: #1a73e8;
}

.icon-recharge {
  background: #e6f4ea;
  color: #34a853;
}

.icon-card {
  background: #f3e8fd;
  color: #a142f4;
}

.icon-exchange {
  background: #fef3e2;
  color: #f29c1f;
}

.bill-main {
  flex: 1;
  min-width: 0;
}

.bill-title {
  font-size: 28rpx;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bill-meta {
  display: flex;
  align-items: center;
  font-size: 22rpx;
  color: #909399;
  margin-top: 8rpx;
}

.bill-time {
  margin-left: 16rpx;
}

.bill-extra {
  font-size: 22rpx;
  color: #f29c1f;
  margin-top: 6rpx;
}

.bill-amount {
  text-align: right;
  flex-shrink: 0;
  margin-left: 16rpx;
}

.bill-amount .amount {
  font-size: 32rpx;
  font-weight: 600;
}

.bill-status {
  display: block;
  font-size: 20rpx;
  color: #c0c4cc;
  margin-top: 6rpx;
}

.list-end {
  text-align: center;
  font-size: 22rpx;
  color: #c0c4cc;
  padding: 30rpx 0 60rpx;
}

.empty-tip {
  font-size: 22rpx;
  color: #c0c4cc;
  margin-top: 12rpx;
}
</style>
