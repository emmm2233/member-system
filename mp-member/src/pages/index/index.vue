<template>
  <view class="container">
    <!-- 等级卡 -->
    <view class="hero">
      <view class="hero-top">
        <view class="hero-member">
          <image class="hero-avatar" :src="summary?.member?.avatar || defaultAvatar" mode="aspectFill" />
          <view class="hero-info">
            <view class="hero-name">
              {{ summary?.member?.nickname || '会员' }}
              <text class="hero-level">{{ summary?.member?.levelName || '普通会员' }}</text>
            </view>
            <view class="hero-no">会员号 {{ summary?.member?.memberNo || '--' }}</view>
          </view>
        </view>
      </view>

      <view class="growth" v-if="summary?.member?.nextLevelName">
        <view class="growth-bar">
          <view class="growth-fill" :style="{ width: growthPercent + '%' }"></view>
        </view>
        <view class="growth-text">
          成长值 {{ summary?.member?.growth || 0 }}
          <text class="growth-need">
            距 {{ summary?.member?.nextLevelName }} 还需 {{ summary?.member?.nextLevelNeedGrowth || 0 }}
          </text>
        </view>
      </view>
      <view class="growth" v-else>
        <view class="growth-text">已是最高等级，尊享全部权益</view>
      </view>

      <!-- 三项资产 -->
      <view class="assets">
        <view class="asset-item" @tap="goWallet">
          <view class="asset-num">¥{{ fmt(summary?.account?.balance) }}</view>
          <view class="asset-label">本金余额</view>
        </view>
        <view class="asset-divider"></view>
        <view class="asset-item" @tap="goWallet">
          <view class="asset-num text-warning">¥{{ fmt(summary?.account?.giftBalance) }}</view>
          <view class="asset-label">赠送余额</view>
        </view>
        <view class="asset-divider"></view>
        <view class="asset-item" @tap="goWallet">
          <view class="asset-num text-success">{{ summary?.account?.points || 0 }}</view>
          <view class="asset-label">积分</view>
        </view>
      </view>
    </view>

    <!-- 主 CTA：核销码 -->
    <view class="cta" @tap="goVerifyCode">
      <view class="cta-icon">▣</view>
      <view class="cta-text">出示核销码</view>
      <view class="cta-tip">到店消费请向店员出示</view>
    </view>

    <!-- 功能宫格 -->
    <view class="grid">
      <view class="grid-item" @tap="goRecharge">
        <view class="grid-icon" style="background: #e8f0fe; color: #1a73e8">充</view>
        <view class="grid-text">在线充值</view>
      </view>
      <view class="grid-item" @tap="goCoupon">
        <view class="grid-icon" style="background: #fef3e2; color: #f29c1f">券</view>
        <view class="grid-text">我的卡券</view>
        <view class="grid-badge" v-if="summary?.couponCount?.unused">{{ summary?.couponCount?.unused }}</view>
      </view>
      <view class="grid-item" @tap="goBill">
        <view class="grid-icon" style="background: #e6f4ea; color: #34a853">单</view>
        <view class="grid-text">消费账单</view>
      </view>
      <view class="grid-item" @tap="goLevel">
        <view class="grid-icon" style="background: #f3e8fd; color: #a142f4">级</view>
        <view class="grid-text">等级权益</view>
      </view>
      <view class="grid-item" @tap="goStore">
        <view class="grid-icon" style="background: #e8f0fe; color: #1a73e8">店</view>
        <view class="grid-text">门店导航</view>
      </view>
      <view class="grid-item" @tap="onShare">
        <view class="grid-icon" style="background: #fde8e8; color: #ea4335">荐</view>
        <view class="grid-text">推荐有礼</view>
      </view>
    </view>

    <!-- 待支付订单 -->
    <view class="card" v-if="summary?.pendingOrders?.length">
      <view class="card-title">
        <text>待完成充值</text>
        <text class="text-muted text-small">超时自动关闭</text>
      </view>
      <view class="pending-item" v-for="o in summary?.pendingOrders" :key="o.orderNo" @tap="continuePay(o)">
        <view class="col">
          <text class="pending-amount">¥{{ fmt(o.amount) }}</text>
          <text class="text-muted text-small">{{ o.orderNo }}</text>
        </view>
        <view class="btn-outline pending-btn">继续支付</view>
      </view>
    </view>

    <!-- 到期提醒 -->
    <view class="card warn-card" v-if="showExpireWarn">
      <view class="warn-row">
        <text class="warn-title">即将过期提醒</text>
      </view>
      <view class="warn-content">
        <text v-if="summary?.couponCount?.expiringIn7d">
          有 {{ summary?.couponCount?.expiringIn7d }} 张优惠券将在 7 天内过期
        </text>
        <text v-if="summary?.couponCount?.expiringPoints">
          <text v-if="summary?.couponCount?.expiringIn7d">；</text>
          {{ summary?.couponCount?.expiringPoints }} 积分将在 30 天内过期
        </text>
      </view>
      <view class="warn-action" @tap="goCoupon">去看看 ›</view>
    </view>

    <!-- 最近消费 -->
    <view class="card" v-if="summary?.lastOrder">
      <view class="card-title">
        <text>最近消费</text>
        <text class="text-muted text-small" @tap="goBill">全部账单 ›</text>
      </view>
      <view class="row-between">
        <view class="col">
          <text class="last-store">{{ summary?.lastOrder?.storeName || '门店消费' }}</text>
          <text class="text-muted text-small">{{ summary?.lastOrder?.createdAt }}</text>
        </view>
        <text class="amount last-amount">¥{{ fmt(summary?.lastOrder?.amount) }}</text>
      </view>
    </view>

    <!-- 无手机号提示 -->
    <view class="card bind-tip" v-if="needBindPhone">
      <view class="bind-row">
        <view class="col">
          <text class="bind-title">绑定手机号</text>
          <text class="text-muted text-small">绑定后可在线充值、查账单、享会员权益</text>
        </view>
        <view class="btn-primary bind-btn" @tap="goBindPhone">去绑定</view>
      </view>
    </view>

    <view class="footer-tip">XX 会员中心 · 储值卡 / 等级 / 积分</view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { mpApi, type HomeSummary } from '@/api'
import { auth } from '@/utils/auth'

const summary = ref<HomeSummary | null>(null)
const needBindPhone = ref(false)
const defaultAvatar = 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSI4MCIgaGVpZ2h0PSI4MCI+PHJlY3Qgd2lkdGg9IjgwIiBoZWlnaHQ9IjgwIiBmaWxsPSIjMWF3M2U4Ii8+PHRleHQgeD0iNDAiIHk9IjUwIiBmb250LXNpemU9IjM0IiBmaWxsPSIjZmZmIiB0ZXh0LWFuY2hvcj0ibWlkZGxlIj7kvJk8L3RleHQ+PC9zdmc+'

const growthPercent = computed(() => {
  const p = summary.value?.member?.growthProgress
  return p ? Math.round(p * 100) : 0
})

const showExpireWarn = computed(() => {
  const c = summary.value?.couponCount
  return !!c && ((c.expiringIn7d || 0) > 0 || (c.expiringPoints || 0) > 0)
})

function fmt(v?: number | string | null) {
  if (v === undefined || v === null) return '0.00'
  return Number(v).toFixed(2)
}

async function load() {
  try {
    summary.value = await mpApi.homeSummary()
    auth.cacheMember(summary.value.member)
    needBindPhone.value = !auth.cachedMember()?.phoneMasked
  } catch (e) {
    console.warn('[home] 加载失败', e)
  }
}

function goVerifyCode() {
  uni.navigateTo({ url: '/pages/verify-code/index' })
}

function goRecharge() {
  uni.navigateTo({ url: '/pages/recharge/index' })
}

function goCoupon() {
  uni.switchTab({ url: '/pages/coupon/index' })
}

function goBill() {
  uni.switchTab({ url: '/pages/bill/index' })
}

function goWallet() {
  uni.navigateTo({ url: '/pages/wallet/index' })
}

function goLevel() {
  uni.navigateTo({ url: '/pages/level/index' })
}

function goStore() {
  uni.navigateTo({ url: '/pages/store/index' })
}

function goBindPhone() {
  uni.navigateTo({ url: '/pages/bind-phone/index' })
}

function onShare() {
  uni.showToast({ title: '请点击右上角分享给好友', icon: 'none' })
}

function continuePay(order: { orderNo: string }) {
  uni.navigateTo({ url: `/pages/recharge/index?orderNo=${order.orderNo}` })
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
/* 等级卡 */
.hero {
  background: linear-gradient(135deg, #1a73e8 0%, #4a90e2 60%, #8bb8f5 100%);
  padding: 40rpx 32rpx 32rpx;
  border-radius: 0 0 32rpx 32rpx;
  color: #fff;
}

.hero-member {
  display: flex;
  align-items: center;
}

.hero-avatar {
  width: 96rpx;
  height: 96rpx;
  border-radius: 50%;
  border: 4rpx solid rgba(255, 255, 255, 0.4);
  background: #fff;
}

.hero-info {
  margin-left: 20rpx;
  flex: 1;
}

.hero-name {
  font-size: 34rpx;
  font-weight: 600;
  display: flex;
  align-items: center;
}

.hero-level {
  font-size: 20rpx;
  background: rgba(255, 255, 255, 0.25);
  padding: 4rpx 14rpx;
  border-radius: 20rpx;
  margin-left: 12rpx;
  font-weight: 400;
}

.hero-no {
  font-size: 22rpx;
  opacity: 0.85;
  margin-top: 6rpx;
}

.growth {
  margin-top: 28rpx;
}

.growth-bar {
  height: 10rpx;
  background: rgba(255, 255, 255, 0.28);
  border-radius: 6rpx;
  overflow: hidden;
}

.growth-fill {
  height: 100%;
  background: #fff;
  border-radius: 6rpx;
  transition: width 0.4s;
}

.growth-text {
  font-size: 22rpx;
  margin-top: 10rpx;
  opacity: 0.92;
  display: flex;
  justify-content: space-between;
}

.growth-need {
  opacity: 0.85;
}

.assets {
  display: flex;
  align-items: center;
  margin-top: 32rpx;
  background: rgba(255, 255, 255, 0.16);
  border-radius: 20rpx;
  padding: 24rpx 0;
}

.asset-item {
  flex: 1;
  text-align: center;
}

.asset-num {
  font-size: 38rpx;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.asset-label {
  font-size: 22rpx;
  opacity: 0.85;
  margin-top: 6rpx;
}

.asset-divider {
  width: 2rpx;
  height: 56rpx;
  background: rgba(255, 255, 255, 0.28);
}

/* CTA */
.cta {
  margin: -20rpx 24rpx 0;
  background: #fff;
  border-radius: 20rpx;
  padding: 30rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  box-shadow: 0 6rpx 20rpx rgba(26, 115, 232, 0.16);
}

.cta-icon {
  font-size: 48rpx;
  color: #1a73e8;
}

.cta-text {
  font-size: 32rpx;
  font-weight: 600;
  color: #1a73e8;
  margin-top: 8rpx;
}

.cta-tip {
  font-size: 22rpx;
  color: #909399;
  margin-top: 6rpx;
}

/* 宫格 */
.grid {
  display: flex;
  flex-wrap: wrap;
  background: #fff;
  border-radius: 20rpx;
  margin: 20rpx 24rpx;
  padding: 20rpx 0;
}

.grid-item {
  width: 33.33%;
  padding: 20rpx 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  position: relative;
}

.grid-icon {
  width: 80rpx;
  height: 80rpx;
  border-radius: 24rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32rpx;
  font-weight: 600;
}

.grid-text {
  font-size: 24rpx;
  color: #606266;
  margin-top: 12rpx;
}

.grid-badge {
  position: absolute;
  top: 12rpx;
  right: 44rpx;
  background: #ea4335;
  color: #fff;
  font-size: 20rpx;
  min-width: 32rpx;
  height: 32rpx;
  line-height: 32rpx;
  text-align: center;
  border-radius: 16rpx;
  padding: 0 8rpx;
}

/* 待支付 */
.pending-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 0;
  border-top: 2rpx solid #f5f7fa;
}

.pending-item:first-of-type {
  border-top: none;
}

.pending-amount {
  font-size: 32rpx;
  font-weight: 600;
  color: #ea4335;
}

.pending-btn {
  width: 180rpx;
  height: 64rpx;
  line-height: 60rpx;
  font-size: 24rpx;
}

/* 到期提醒 */
.warn-card {
  background: #fff8ec;
  border: 2rpx solid #ffe0b2;
  position: relative;
}

.warn-title {
  font-size: 28rpx;
  font-weight: 600;
  color: #f29c1f;
}

.warn-content {
  font-size: 24rpx;
  color: #8a6d3b;
  margin-top: 10rpx;
  line-height: 1.7;
}

.warn-action {
  position: absolute;
  right: 28rpx;
  top: 28rpx;
  font-size: 24rpx;
  color: #f29c1f;
}

/* 最近消费 */
.last-store {
  font-size: 28rpx;
  font-weight: 500;
}

.last-amount {
  font-size: 32rpx;
  color: #303133;
}

/* 绑定提示 */
.bind-tip {
  background: #e8f0fe;
}

.bind-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.bind-title {
  font-size: 28rpx;
  font-weight: 600;
  color: #1a73e8;
}

.bind-btn {
  width: 160rpx;
  height: 64rpx;
  line-height: 64rpx;
  font-size: 24rpx;
}

.footer-tip {
  text-align: center;
  font-size: 22rpx;
  color: #c0c4cc;
  padding: 30rpx 0 40rpx;
}
</style>
