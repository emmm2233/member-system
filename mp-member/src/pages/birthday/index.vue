<template>
  <view class="container">
    <!-- 生日卡 -->
    <view class="hero">
      <view class="hero-emoji">🎂</view>
      <view class="hero-title" v-if="info?.hasBirthday">
        {{ info.daysUntil === 0 ? '今天生日快乐！' : `距离您的生日还有 ${info.daysUntil} 天` }}
      </view>
      <view class="hero-title" v-else>完善生日资料，领取生日礼</view>
      <view class="hero-date" v-if="info?.hasBirthday">生日 {{ info.birthday }}</view>
      <view class="hero-desc">{{ info?.wishText || '生日当天可获得专属生日礼' }}</view>
    </view>

    <!-- 礼包内容 -->
    <view class="card">
      <view class="card-title">生日礼包</view>
      <view class="gift">{{ info?.giftDesc || '生日专属权益' }}</view>
      <view class="gift-state" :class="info?.giftedThisYear ? 'ok' : 'pending'">
        {{ info?.giftedThisYear ? '本年度已发放，请到卡包查收' : '尚未发放，生日当天自动到账' }}
      </view>
      <view class="card-tip" v-if="info && info.daysBefore > 0">生日礼将于生日前 {{ info.daysBefore }} 天发放</view>
    </view>

    <!-- 未填生日 -->
    <view class="card" v-if="info && !info.hasBirthday">
      <view class="card-title">填写生日</view>
      <view class="bind-row">
        <picker mode="date" :value="birthday" start="1900-01-01" :end="today" @change="onDateChange">
          <view class="picker">{{ birthday || '请选择生日' }}</view>
        </picker>
        <button class="btn-primary small" :disabled="saving" @tap="saveBirthday">
          {{ saving ? '保存中' : '保存' }}
        </button>
      </view>
      <view class="card-tip">只需月份和日期，年份可填 1900（不影响发放）</view>
    </view>

    <!-- 历史生日礼 -->
    <view class="card" v-if="info?.lastGift">
      <view class="card-title">最近一次生日礼</view>
      <view class="row">
        <view class="row-left">
          <view class="row-title">{{ info.lastGift.giftYear }} 年度</view>
          <view class="row-sub">{{ info.lastGift.giftDate }} · {{ info.lastGift.statusText }}</view>
        </view>
        <view class="row-right">
          <text class="reward">{{ lastGiftText }}</text>
        </view>
      </view>
    </view>

    <view class="tips">
      <view class="tips-title">温馨提示</view>
      <view class="tips-line">· 每个会员每年度只能领取一次生日礼</view>
      <view class="tips-line">· 生日礼会自动发放到您的账户与卡包</view>
      <view class="tips-line">· 生日日期以门店建档或小程序填写为准</view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { mpApi, type BirthdayInfo } from '@/api'
import { fmtInt, fmtMoney } from '@/utils/format'

const info = ref<BirthdayInfo | null>(null)
const birthday = ref('')
const saving = ref(false)
const today = new Date().toISOString().slice(0, 10)

async function load() {
  try {
    info.value = await mpApi.birthday()
    if (info.value?.birthday) {
      birthday.value = info.value.birthday
    }
  } catch (e) {
    /* 统一提示 */
  }
}

function onDateChange(e: { detail: { value: string } }) {
  birthday.value = e.detail.value
}

async function saveBirthday() {
  if (!birthday.value) {
    uni.showToast({ title: '请选择生日', icon: 'none' })
    return
  }
  saving.value = true
  try {
    await mpApi.updateProfile({ birthday: birthday.value })
    uni.showToast({ title: '已保存', icon: 'success' })
    await load()
  } catch (e) {
    /* 统一提示 */
  } finally {
    saving.value = false
  }
}

const lastGiftText = computed(() => {
  const g = info.value?.lastGift
  if (!g) return ''
  const parts: string[] = []
  if (g.points > 0) parts.push(`${fmtInt(g.points)} 积分`)
  if (Number(g.balance) > 0) parts.push(`${fmtMoney(g.balance)} 元`)
  if (g.growth > 0) parts.push(`${fmtInt(g.growth)} 成长值`)
  if (g.couponId) parts.push('优惠券 1 张')
  return parts.length ? parts.join(' + ') : '祝福'
})

onShow(load)
</script>

<style scoped>
.container {
  min-height: 100vh;
  padding-bottom: 40rpx;
  background: #f5f7fa;
}
.hero {
  padding: 48rpx 32rpx 40rpx;
  background: linear-gradient(135deg, #ff9a76, #ff6b81);
  color: #fff;
  text-align: center;
}
.hero-emoji {
  font-size: 80rpx;
}
.hero-title {
  margin-top: 12rpx;
  font-size: 38rpx;
  font-weight: 600;
}
.hero-date {
  margin-top: 10rpx;
  font-size: 26rpx;
  opacity: 0.9;
}
.hero-desc {
  margin-top: 18rpx;
  font-size: 24rpx;
  line-height: 1.7;
  opacity: 0.92;
}
.card {
  margin: 24rpx;
  padding: 28rpx;
  border-radius: 16rpx;
  background: #fff;
}
.card-title {
  font-size: 30rpx;
  font-weight: 600;
  color: #303133;
}
.gift {
  margin-top: 18rpx;
  font-size: 32rpx;
  font-weight: 600;
  color: #ff6b81;
  line-height: 1.6;
}
.gift-state {
  margin-top: 12rpx;
  font-size: 24rpx;
}
.gift-state.ok {
  color: #67c23a;
}
.gift-state.pending {
  color: #909399;
}
.card-tip {
  margin-top: 12rpx;
  font-size: 22rpx;
  line-height: 1.6;
  color: #909399;
}
.bind-row {
  margin-top: 20rpx;
  display: flex;
  align-items: center;
  gap: 16rpx;
}
.picker {
  height: 72rpx;
  line-height: 72rpx;
  padding: 0 24rpx;
  font-size: 28rpx;
  color: #303133;
  background: #f5f7fa;
  border-radius: 36rpx;
}
.btn-primary {
  flex: 1;
  height: 72rpx;
  line-height: 72rpx;
  font-size: 28rpx;
  color: #fff;
  background: #1a73e8;
  border-radius: 36rpx;
  text-align: center;
}
.btn-primary.small {
  flex: none;
  width: 150rpx;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 22rpx 0 0;
}
.row-title {
  font-size: 28rpx;
  color: #303133;
}
.row-sub {
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #909399;
}
.reward {
  font-size: 26rpx;
  font-weight: 600;
  color: #e6a23c;
}
.tips {
  margin: 24rpx;
  padding: 24rpx;
  border-radius: 16rpx;
  background: #fff;
}
.tips-title {
  font-size: 26rpx;
  font-weight: 600;
  color: #606266;
}
.tips-line {
  margin-top: 12rpx;
  font-size: 22rpx;
  line-height: 1.7;
  color: #909399;
}
</style>
