<template>
  <view class="container">
    <!-- 用户头 -->
    <view class="head">
      <image class="avatar" :src="member?.avatar || defaultAvatar" mode="aspectFill" />
      <view class="head-info">
        <view class="head-name">
          {{ member?.nickname || '会员' }}
          <text class="head-level">{{ member?.levelName || '普通会员' }}</text>
        </view>
        <view class="head-phone">{{ member?.phoneMasked || '未绑定手机号' }}</view>
      </view>
      <view class="head-action" v-if="!member?.phoneMasked" @tap="goBindPhone">绑定</view>
    </view>

    <!-- 资产四宫格 -->
    <view class="assets">
      <view class="asset-cell" @tap="goWallet">
        <text class="asset-value">¥{{ fmt(account?.balance) }}</text>
        <text class="asset-name">本金余额</text>
      </view>
      <view class="asset-cell" @tap="goWallet">
        <text class="asset-value text-warning">¥{{ fmt(account?.giftBalance) }}</text>
        <text class="asset-name">赠送余额</text>
      </view>
      <view class="asset-cell" @tap="goWallet">
        <text class="asset-value text-success">{{ account?.points || 0 }}</text>
        <text class="asset-name">积分</text>
      </view>
      <view class="asset-cell" @tap="goLevel">
        <text class="asset-value">{{ account?.growth || 0 }}</text>
        <text class="asset-name">成长值</text>
      </view>
    </view>

    <!-- 功能列表 -->
    <view class="menu">
      <view class="menu-item" @tap="goVerifyCode">
        <text class="menu-icon">▣</text>
        <text class="menu-label">我的核销码</text>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-item" @tap="goRecharge">
        <text class="menu-icon">¥</text>
        <text class="menu-label">在线充值</text>
        <text class="menu-tag" v-if="payEnabled">可充值</text>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-item" @tap="goCoupon">
        <text class="menu-icon">券</text>
        <text class="menu-label">我的卡券</text>
        <text class="menu-tag" v-if="couponCount">{{ couponCount }} 张可用</text>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-item" @tap="goLevel">
        <text class="menu-icon">级</text>
        <text class="menu-label">等级权益</text>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-item" @tap="goStore">
        <text class="menu-icon">店</text>
        <text class="menu-label">门店列表</text>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-item" @tap="goReferral">
        <text class="menu-icon">荐</text>
        <text class="menu-label">推荐有礼</text>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-item" @tap="goBirthday">
        <text class="menu-icon">礼</text>
        <text class="menu-label">我的生日礼</text>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-item" @tap="goMessages">
        <text class="menu-icon">信</text>
        <text class="menu-label">消息中心</text>
        <text class="menu-badge" v-if="unread > 0">{{ unread > 99 ? '99+' : unread }}</text>
        <text class="menu-arrow">›</text>
      </view>
    </view>

    <view class="menu">
      <view class="menu-item" @tap="showProfileEdit">
        <text class="menu-icon">编</text>
        <text class="menu-label">完善资料</text>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-item" @tap="showAbout">
        <text class="menu-icon">i</text>
        <text class="menu-label">关于我们</text>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-item" @tap="onLogout">
        <text class="menu-icon">退</text>
        <text class="menu-label">退出登录</text>
        <text class="menu-arrow">›</text>
      </view>
    </view>

    <view class="footer-tip">XX 会员中心 v1.0.0</view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { mpApi, type AccountInfo } from '@/api'
import { auth } from '@/utils/auth'
import { fmtMoney } from '@/utils/format'

const member = ref<Record<string, unknown> | null>(null)
const account = ref<AccountInfo | null>(null)
const couponCount = ref(0)
const payEnabled = ref(false)
const unread = ref(0)
const defaultAvatar = 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSI4MCIgaGVpZ2h0PSI4MCI+PHJlY3Qgd2lkdGg9IjgwIiBoZWlnaHQ9IjgwIiBmaWxsPSIjMWF3M2U4Ii8+PHRleHQgeD0iNDAiIHk9IjUwIiBmb250LXNpemU9IjM0IiBmaWxsPSIjZmZmIiB0ZXh0LWFuY2hvcj0ibWlkZGxlIj7kvJk8L3RleHQ+PC9zdmc+'

function fmt(v?: number | string | null) {
  return fmtMoney(v)
}

async function load() {
  try {
    const [profile, acc, home] = await Promise.all([
      mpApi.profile(),
      mpApi.account(),
      mpApi.homeSummary().catch(() => null)
    ])
    member.value = profile
    account.value = acc
    if (home) {
      couponCount.value = home.couponCount?.unused || 0
      payEnabled.value = !!home.payEnabled
    }
    unread.value = await mpApi.unreadCount().catch(() => 0)
  } catch (e) {
    console.warn('[mine] 加载失败', e)
  }
}

function goReferral() {
  uni.navigateTo({ url: '/pages/referral/index' })
}

function goBirthday() {
  uni.navigateTo({ url: '/pages/birthday/index' })
}

function goMessages() {
  uni.navigateTo({ url: '/pages/messages/index' })
}

function goBindPhone() {
  uni.navigateTo({ url: '/pages/bind-phone/index' })
}

function goWallet() {
  uni.navigateTo({ url: '/pages/wallet/index' })
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

function goLevel() {
  uni.navigateTo({ url: '/pages/level/index' })
}

function goStore() {
  uni.navigateTo({ url: '/pages/store/index' })
}

async function showProfileEdit() {
  // 昵称修改：小程序端推荐使用 chooseAvatar + nickname input
  uni.showModal({
    title: '完善资料',
    editable: true,
    placeholderText: '请输入昵称',
    success: async (res) => {
      if (res.confirm && res.content) {
        try {
          await mpApi.updateProfile({ nickname: res.content })
          uni.showToast({ title: '已更新', icon: 'success' })
          load()
        } catch {
          // 错误提示已由请求层处理
        }
      }
    }
  })
}

function showAbout() {
  uni.showModal({
    title: '关于我们',
    content: 'XX 会员中心\n储值卡 · 等级 · 积分一体化会员服务\n\n客服电话：400-000-0000',
    showCancel: false
  })
}

function onLogout() {
  uni.showModal({
    title: '退出登录',
    content: '退出后需重新进入小程序登录，确认退出？',
    success: async (res) => {
      if (res.confirm) {
        await auth.logout()
        setTimeout(() => {
          uni.reLaunch({ url: '/pages/index/index' })
        }, 600)
      }
    }
  })
}

onShow(() => {
  load()
})
</script>

<style scoped>
.head {
  display: flex;
  align-items: center;
  padding: 48rpx 32rpx 40rpx;
  background: linear-gradient(135deg, #1a73e8 0%, #4a90e2 60%, #8bb8f5 100%);
  color: #fff;
}

.avatar {
  width: 120rpx;
  height: 120rpx;
  border-radius: 50%;
  border: 4rpx solid rgba(255, 255, 255, 0.45);
  background: #fff;
}

.head-info {
  flex: 1;
  margin-left: 24rpx;
}

.head-name {
  font-size: 36rpx;
  font-weight: 600;
  display: flex;
  align-items: center;
}

.head-level {
  font-size: 20rpx;
  background: rgba(255, 255, 255, 0.25);
  padding: 4rpx 14rpx;
  border-radius: 20rpx;
  margin-left: 12rpx;
  font-weight: 400;
}

.head-phone {
  font-size: 24rpx;
  opacity: 0.88;
  margin-top: 10rpx;
}

.head-action {
  border: 2rpx solid rgba(255, 255, 255, 0.7);
  border-radius: 30rpx;
  padding: 8rpx 24rpx;
  font-size: 24rpx;
}

/* 资产 */
.assets {
  display: flex;
  background: #fff;
  margin: -24rpx 24rpx 0;
  border-radius: 20rpx;
  padding: 28rpx 0;
  box-shadow: 0 4rpx 16rpx rgba(0, 0, 0, 0.06);
  position: relative;
}

.asset-cell {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.asset-value {
  font-size: 34rpx;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.asset-name {
  font-size: 22rpx;
  color: #909399;
  margin-top: 8rpx;
}

/* 菜单 */
.menu {
  background: #fff;
  border-radius: 20rpx;
  margin: 20rpx 24rpx;
  overflow: hidden;
}

.menu-item {
  display: flex;
  align-items: center;
  padding: 30rpx 28rpx;
  border-bottom: 2rpx solid #f5f7fa;
}

.menu-item:last-child {
  border-bottom: none;
}

.menu-icon {
  width: 56rpx;
  height: 56rpx;
  border-radius: 16rpx;
  background: #e8f0fe;
  color: #1a73e8;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26rpx;
  font-weight: 600;
  margin-right: 20rpx;
}

.menu-label {
  flex: 1;
  font-size: 30rpx;
}

.menu-tag {
  font-size: 22rpx;
  color: #f29c1f;
  margin-right: 12rpx;
}

.menu-arrow {
  font-size: 36rpx;
  color: #c0c4cc;
}

.menu-badge {
  margin-left: auto;
  margin-right: 8rpx;
  min-width: 34rpx;
  height: 34rpx;
  line-height: 34rpx;
  padding: 0 10rpx;
  border-radius: 17rpx;
  font-size: 20rpx;
  color: #fff;
  background: #f56c6c;
  text-align: center;
}

.footer-tip {
  text-align: center;
  font-size: 22rpx;
  color: #c0c4cc;
  padding: 30rpx 0 50rpx;
}
</style>
