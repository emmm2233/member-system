<template>
  <view class="container">
    <!-- 概览 -->
    <view class="summary">
      <view class="summary-item">
        <text class="summary-num">{{ summary?.unused || 0 }}</text>
        <text class="summary-label">可用</text>
      </view>
      <view class="summary-item">
        <text class="summary-num">{{ summary?.used || 0 }}</text>
        <text class="summary-label">已用</text>
      </view>
      <view class="summary-item">
        <text class="summary-num">{{ summary?.expired || 0 }}</text>
        <text class="summary-label">过期</text>
      </view>
    </view>

    <!-- 分组 Tab -->
    <view class="tabs">
      <view
        v-for="t in tabs"
        :key="t.value ?? 'all'"
        class="tab"
        :class="{ active: currentStatus === t.value }"
        @tap="switchTab(t.value)"
      >
        {{ t.label }}
      </view>
    </view>

    <!-- 券列表 -->
    <view v-if="list.length">
      <view
        v-for="c in list"
        :key="c.couponNo"
        class="coupon"
        :class="{ disabled: c.status !== 1 }"
      >
        <view class="coupon-left">
          <view class="coupon-face">{{ faceText(c) }}</view>
          <view class="coupon-cond">{{ c.useRuleDesc }}</view>
        </view>
        <view class="coupon-body">
          <view class="coupon-name">
            {{ c.tplName }}
            <text class="coupon-tag" v-if="c.expiringSoon && c.status === 1">
              {{ c.daysLeft }} 天后过期
            </text>
          </view>
          <view class="coupon-meta">有效期至 {{ (c.validEnd || '').slice(0, 10) }}</view>
          <view class="coupon-meta text-muted">{{ typeText(c.couponType) }} · 来源 {{ sourceText(c.source) }}</view>
        </view>
        <view class="coupon-right">
          <view v-if="c.status === 1" class="btn-use" @tap="useCoupon">去使用</view>
          <text v-else class="coupon-status">{{ c.statusText }}</text>
        </view>
      </view>

      <view class="list-end" v-if="list.length >= total">共 {{ total }} 张</view>
    </view>

    <view v-else class="empty">
      <text>暂无{{ currentStatus === 1 ? '可用' : '' }}优惠券</text>
      <view class="empty-tip">到店消费或参与活动即可获得优惠券</view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { mpApi, type CouponRow } from '@/api'
import { couponFaceText, couponTypeText } from '@/utils/format'

const tabs: Array<{ label: string; value?: number }> = [
  { label: '可使用', value: 1 },
  { label: '已使用', value: 3 },
  { label: '已过期', value: 4 },
  { label: '全部', value: undefined }
]

const currentStatus = ref<number | undefined>(1)
const list = ref<CouponRow[]>([])
const total = ref(0)
const summary = ref<{ unused: number; used: number; expired: number } | null>(null)

function faceText(c: CouponRow) {
  return couponFaceText(c.couponType, c.faceValue)
}

function typeText(t: number) {
  return couponTypeText(t)
}

function sourceText(s: string) {
  const map: Record<string, string> = {
    ACTIVITY: '活动',
    GRANT: '门店发放',
    CLAIM: '自助领取',
    REFERRAL: '推荐奖励',
    BIRTHDAY: '生日礼',
    RECHARGE: '充值赠送',
    EXCHANGE: '积分兑换'
  }
  return map[s] || '系统发放'
}

async function load() {
  try {
    const res = await mpApi.coupons({ status: currentStatus.value, pageNum: 1, pageSize: 50 })
    list.value = res.list
    total.value = res.total
    summary.value = res.summary
  } catch (e) {
    console.warn('[coupon] 加载失败', e)
  }
}

function switchTab(value?: number) {
  currentStatus.value = value
  load()
}

function useCoupon() {
  uni.showModal({
    title: '使用方式',
    content: '到店消费时向店员出示核销码，店员选券后自动抵扣。\n是否立即出示核销码？',
    confirmText: '出示核销码',
    success: (res) => {
      if (res.confirm) {
        uni.navigateTo({ url: '/pages/verify-code/index' })
      }
    }
  })
}

onShow(() => {
  load()
})

onPullDownRefresh(async () => {
  await load()
  uni.stopPullDownRefresh()
})
</script>

<style scoped>
.summary {
  display: flex;
  background: #fff;
  padding: 32rpx 0;
  margin-bottom: 16rpx;
}

.summary-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.summary-num {
  font-size: 40rpx;
  font-weight: 700;
  color: #1a73e8;
}

.summary-label {
  font-size: 22rpx;
  color: #909399;
  margin-top: 6rpx;
}

/* Tabs */
.tabs {
  display: flex;
  background: #fff;
  padding: 0 24rpx;
  margin-bottom: 16rpx;
}

.tab {
  flex: 1;
  text-align: center;
  padding: 24rpx 0;
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
  width: 48rpx;
  height: 6rpx;
  background: #1a73e8;
  border-radius: 4rpx;
}

/* 券卡片 */
.coupon {
  display: flex;
  align-items: stretch;
  background: #fff;
  border-radius: 16rpx;
  margin: 16rpx 24rpx;
  overflow: hidden;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.04);
}

.coupon.disabled {
  opacity: 0.55;
}

.coupon-left {
  width: 200rpx;
  background: linear-gradient(135deg, #1a73e8 0%, #4a90e2 100%);
  color: #fff;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24rpx 12rpx;
}

.coupon.disabled .coupon-left {
  background: linear-gradient(135deg, #a8a9ac 0%, #c8c9cc 100%);
}

.coupon-face {
  font-size: 44rpx;
  font-weight: 700;
}

.coupon-cond {
  font-size: 20rpx;
  opacity: 0.9;
  margin-top: 8rpx;
  text-align: center;
}

.coupon-body {
  flex: 1;
  padding: 20rpx;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.coupon-name {
  font-size: 30rpx;
  font-weight: 600;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
}

.coupon-tag {
  font-size: 20rpx;
  color: #ea4335;
  background: #fde8e8;
  padding: 2rpx 10rpx;
  border-radius: 8rpx;
  margin-left: 10rpx;
}

.coupon-meta {
  font-size: 22rpx;
  color: #606266;
  margin-top: 8rpx;
}

.coupon-right {
  width: 130rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn-use {
  border: 2rpx solid #1a73e8;
  color: #1a73e8;
  font-size: 24rpx;
  padding: 10rpx 20rpx;
  border-radius: 30rpx;
}

.coupon-status {
  font-size: 24rpx;
  color: #c0c4cc;
}

.list-end {
  text-align: center;
  font-size: 22rpx;
  color: #c0c4cc;
  padding: 24rpx 0 40rpx;
}

.empty-tip {
  font-size: 22rpx;
  color: #c0c4cc;
  margin-top: 12rpx;
}
</style>
