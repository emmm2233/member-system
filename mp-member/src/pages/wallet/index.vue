<template>
  <view class="container">
    <!-- 资产总览 -->
    <view class="card total-card">
      <text class="text-muted text-small">可用余额（本金 + 赠送）</text>
      <text class="total-num">¥{{ fmt(account?.available) }}</text>
      <view class="total-split">
        <text>本金 ¥{{ fmt(account?.balance) }}</text>
        <text class="split-divider">|</text>
        <text>赠送 ¥{{ fmt(account?.giftBalance) }}</text>
      </view>
    </view>

    <view class="stat-row">
      <view class="stat-cell">
        <text class="stat-num text-success">{{ account?.points || 0 }}</text>
        <text class="stat-label">积分</text>
        <text class="stat-sub" v-if="account?.expiringPoints30d">
          {{ account.expiringPoints30d }} 分 30 天内到期
        </text>
        <text class="stat-sub" v-else>兑换比例 {{ account?.pointsExchangeRate || 100 }}:1</text>
      </view>
      <view class="stat-cell">
        <text class="stat-num">{{ account?.growth || 0 }}</text>
        <text class="stat-label">成长值</text>
        <text class="stat-sub">{{ account?.levelName || '普通会员' }}</text>
      </view>
    </view>

    <view class="card">
      <view class="row-between">
        <view class="col">
          <text class="text-small text-muted">累计充值</text>
          <text class="sum-num">¥{{ fmt(account?.totalRecharge) }}</text>
        </view>
        <view class="col" style="align-items: flex-end">
          <text class="text-small text-muted">累计消费（{{ account?.totalConsumeCount || 0 }} 笔）</text>
          <text class="sum-num">¥{{ fmt(account?.totalConsume) }}</text>
        </view>
      </view>
    </view>

    <!-- 充值入口 -->
    <view class="btn-primary recharge-btn" @tap="goRecharge">在线充值</view>

    <!-- 流水 -->
    <view class="card">
      <view class="card-title">
        <text>余额流水</text>
        <view class="filter-row">
          <text
            v-for="f in filters"
            :key="f.label"
            class="filter-item"
            :class="{ active: currentType === f.value }"
            @tap="switchType(f.value)"
          >
            {{ f.label }}
          </text>
        </view>
      </view>

      <view v-if="flows.length">
        <view v-for="(row, idx) in flows" :key="idx" class="flow-item">
          <view class="col" style="flex: 1">
            <text class="flow-type">{{ row.bizTypeText }}{{ row.accountTypeText ? '（' + row.accountTypeText + '）' : '' }}</text>
            <text class="text-muted text-small">{{ row.occurTime }}</text>
            <text class="text-muted text-small" v-if="row.remark">{{ row.remark }}</text>
          </view>
          <view class="col" style="align-items: flex-end">
            <text class="amount" :class="Number(row.changeAmount) >= 0 ? 'text-success' : 'text-danger'">
              {{ Number(row.changeAmount) >= 0 ? '+' : '' }}{{ fmt(row.changeAmount) }}
            </text>
            <text class="text-muted text-small">余额 {{ fmt(row.afterAmount) }}</text>
          </view>
        </view>
      </view>
      <view v-else class="empty">暂无流水记录</view>
    </view>

    <!-- 积分到期日历 -->
    <view class="card" v-if="calendar.length">
      <view class="card-title"><text>积分到期日历</text></view>
      <view v-for="c in calendar" :key="c.month" class="calendar-item">
        <text class="calendar-month">{{ c.month }}</text>
        <text class="calendar-points">{{ c.points }} 分到期</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { mpApi, type AccountInfo } from '@/api'
import { fmtMoney } from '@/utils/format'

const account = ref<AccountInfo | null>(null)
const flows = ref<Array<Record<string, unknown>>>([])
const calendar = ref<Array<{ month: string; points: number }>>([])
const currentType = ref<string | undefined>(undefined)

const filters: Array<{ label: string; value?: string }> = [
  { label: '全部', value: undefined },
  { label: '充值', value: 'recharge' },
  { label: '消费', value: 'consume' },
  { label: '退款', value: 'refund' }
]

function fmt(v?: number | string | null) {
  return fmtMoney(v)
}

async function load() {
  try {
    const [acc, flow, cal] = await Promise.all([
      mpApi.account(),
      mpApi.balanceFlows({ type: currentType.value, pageNum: 1, pageSize: 30 }),
      mpApi.pointExpireCalendar().catch(() => [])
    ])
    account.value = acc
    flows.value = flow.list
    calendar.value = cal
  } catch (e) {
    console.warn('[wallet] 加载失败', e)
  }
}

function switchType(value?: string) {
  currentType.value = value
  load()
}

function goRecharge() {
  uni.navigateTo({ url: '/pages/recharge/index' })
}

onShow(() => {
  load()
})
</script>

<style scoped>
.total-card {
  background: linear-gradient(135deg, #1a73e8 0%, #4a90e2 100%);
  color: #fff;
  display: flex;
  flex-direction: column;
}

.total-card .text-muted {
  color: rgba(255, 255, 255, 0.82);
}

.total-num {
  font-size: 60rpx;
  font-weight: 700;
  margin: 10rpx 0;
  font-variant-numeric: tabular-nums;
}

.total-split {
  font-size: 24rpx;
  opacity: 0.9;
}

.split-divider {
  margin: 0 16rpx;
  opacity: 0.6;
}

.stat-row {
  display: flex;
  gap: 20rpx;
  margin: 0 24rpx;
}

.stat-cell {
  flex: 1;
  background: #fff;
  border-radius: 20rpx;
  padding: 28rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.stat-num {
  font-size: 40rpx;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.stat-label {
  font-size: 24rpx;
  color: #606266;
  margin-top: 8rpx;
}

.stat-sub {
  font-size: 20rpx;
  color: #c0c4cc;
  margin-top: 6rpx;
}

.sum-num {
  font-size: 32rpx;
  font-weight: 600;
  margin-top: 8rpx;
}

.recharge-btn {
  margin: 24rpx;
}

.filter-row {
  display: flex;
  gap: 20rpx;
}

.filter-item {
  font-size: 24rpx;
  color: #909399;
}

.filter-item.active {
  color: #1a73e8;
  font-weight: 600;
}

.flow-item {
  display: flex;
  align-items: center;
  padding: 20rpx 0;
  border-bottom: 2rpx solid #f5f7fa;
}

.flow-item:last-child {
  border-bottom: none;
}

.flow-type {
  font-size: 28rpx;
  font-weight: 500;
}

.calendar-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14rpx 0;
}

.calendar-month {
  font-size: 26rpx;
  color: #606266;
}

.calendar-points {
  font-size: 26rpx;
  color: #f29c1f;
  font-weight: 600;
}
</style>
