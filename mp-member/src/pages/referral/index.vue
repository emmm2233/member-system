<template>
  <view class="container">
    <!-- 我的邀请码 -->
    <view class="hero">
      <view class="hero-title">邀请好友成为会员</view>
      <view class="hero-desc">{{ center?.ruleDesc || '好友完成指定条件后，双方均可获得奖励' }}</view>
      <view class="code-box" @tap="copyCode">
        <text class="code-label">我的邀请码</text>
        <text class="code-value">{{ center?.inviteCode || '——' }}</text>
        <text class="code-copy">点击复制</text>
      </view>
      <view class="hero-actions">
        <button class="btn-primary" @tap="copyCode">复制邀请码</button>
        <button class="btn-ghost" open-type="share" v-if="canShare">分享给好友</button>
      </view>
      <view class="hero-tip" v-if="center && center.bindWindowDays > 0 && !center.bound">
        好友注册后 {{ center.bindWindowDays }} 天内填写邀请码有效
      </view>
    </view>

    <!-- 我的战绩 -->
    <view class="stats">
      <view class="stat">
        <text class="stat-num">{{ center?.inviteCount || 0 }}</text>
        <text class="stat-label">已邀请</text>
      </view>
      <view class="stat">
        <text class="stat-num">{{ center?.settledCount || 0 }}</text>
        <text class="stat-label">已结算</text>
      </view>
      <view class="stat">
        <text class="stat-num">{{ fmtInt(center?.totalPoints) }}</text>
        <text class="stat-label">累计积分</text>
      </view>
      <view class="stat">
        <text class="stat-num">{{ fmtMoney(center?.totalBalance) }}</text>
        <text class="stat-label">累计余额</text>
      </view>
    </view>

    <!-- 填写邀请码 -->
    <view class="card" v-if="center && !center.bound">
      <view class="card-title">我也被人邀请过？</view>
      <view class="bind-row">
        <input v-model="inviteCode" class="bind-input" placeholder="填写好友的邀请码" maxlength="16" />
        <button class="btn-primary small" :disabled="binding" @tap="submitBind">
          {{ binding ? '提交中' : '绑定' }}
        </button>
      </view>
      <view class="card-tip">绑定后不可修改，且需在注册后 {{ center.bindWindowDays || 0 }} 天内完成</view>
    </view>
    <view class="card bound-card" v-else-if="center && center.bound">
      <view class="card-title">已绑定邀请人</view>
      <view class="card-tip">您已成功绑定邀请人，奖励将在达成条件后自动发放</view>
    </view>

    <!-- 奖励明细 -->
    <view class="card">
      <view class="card-title">奖励明细</view>
      <view v-if="center && center.rewards.length">
        <view v-for="r in center.rewards" :key="r.id" class="row">
          <view class="row-left">
            <view class="row-title">{{ r.targetText }}奖励</view>
            <view class="row-sub">{{ shortTime(r.createdAt) }}</view>
          </view>
          <view class="row-right">
            <text class="reward">{{ rewardText(r) }}</text>
            <text class="row-status" :class="r.status === 2 ? 'ok' : 'warn'">{{ r.statusText }}</text>
          </view>
        </view>
      </view>
      <view v-else class="empty-inline">暂无奖励记录</view>
    </view>

    <!-- 我邀请的人 -->
    <view class="card">
      <view class="card-title">我邀请的好友</view>
      <view v-if="center && center.invitees.length">
        <view v-for="(it, idx) in center.invitees" :key="idx" class="row">
          <view class="row-left">
            <view class="row-title">{{ it.nickname }}</view>
            <view class="row-sub">{{ it.phone }} · {{ shortTime(it.bindAt) }}</view>
          </view>
          <view class="row-right">
            <text class="row-status" :class="it.status === 2 ? 'ok' : it.status === 3 ? 'muted' : 'warn'">
              {{ it.statusText }}
            </text>
          </view>
        </view>
      </view>
      <view v-else class="empty-inline">还没有邀请记录，快去分享吧</view>
    </view>

    <view v-if="center && !center.enabled" class="disabled-mask">
      <text>推荐有礼活动暂未开启</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onPullDownRefresh, onShareAppMessage, onShow } from '@dcloudio/uni-app'
import { mpApi, type ReferralCenter, type ReferralRewardRow } from '@/api'
import { fmtInt, fmtMoney, shortTime } from '@/utils/format'

const center = ref<ReferralCenter | null>(null)
const inviteCode = ref('')
const binding = ref(false)
const canShare = ref(true)

async function load() {
  try {
    center.value = await mpApi.referralCenter()
  } catch (e) {
    /* 错误提示由 request 统一处理 */
  }
}

function copyCode() {
  const code = center.value?.inviteCode
  if (!code) {
    uni.showToast({ title: '邀请码加载中', icon: 'none' })
    return
  }
  uni.setClipboardData({
    data: code,
    success: () => uni.showToast({ title: '邀请码已复制', icon: 'success' })
  })
}

function rewardText(r: ReferralRewardRow): string {
  if (r.rewardType === 3) return '优惠券 1 张'
  if (r.rewardType === 1 || r.rewardType === 4) return `${r.rewardTypeText} ${fmtInt(r.rewardValue)}`
  return `${r.rewardTypeText} ${fmtMoney(r.rewardValue)} 元`
}

async function submitBind() {
  const code = inviteCode.value.trim()
  if (!code) {
    uni.showToast({ title: '请填写邀请码', icon: 'none' })
    return
  }
  binding.value = true
  try {
    await mpApi.bindReferral(code, 'LINK')
    uni.showToast({ title: '绑定成功', icon: 'success' })
    inviteCode.value = ''
    await load()
  } catch (e) {
    /* 失败原因（已绑定/无效/超期/不能自荐）由统一提示给出 */
  } finally {
    binding.value = false
  }
}

onShow(load)
onPullDownRefresh(async () => {
  await load()
  uni.stopPullDownRefresh()
})

onShareAppMessage(() => ({
  title: '加入 XX 会员中心，注册即享专属权益',
  path: `/pages/index/index?inviteCode=${center.value?.inviteCode || ''}`
}))
</script>

<style scoped>
.container {
  min-height: 100vh;
  padding-bottom: 40rpx;
  background: #f5f7fa;
}
.hero {
  padding: 40rpx 32rpx 32rpx;
  background: linear-gradient(135deg, #1a73e8, #4a9bf5);
  color: #fff;
}
.hero-title {
  font-size: 40rpx;
  font-weight: 600;
}
.hero-desc {
  margin-top: 12rpx;
  font-size: 24rpx;
  line-height: 1.6;
  opacity: 0.9;
}
.code-box {
  margin-top: 32rpx;
  padding: 28rpx;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.18);
  display: flex;
  flex-direction: column;
  align-items: center;
}
.code-label {
  font-size: 24rpx;
  opacity: 0.85;
}
.code-value {
  margin-top: 10rpx;
  font-size: 60rpx;
  font-weight: 700;
  letter-spacing: 8rpx;
}
.code-copy {
  margin-top: 8rpx;
  font-size: 22rpx;
  opacity: 0.85;
}
.hero-actions {
  margin-top: 28rpx;
  display: flex;
  gap: 20rpx;
}
.btn-primary {
  flex: 1;
  height: 80rpx;
  line-height: 80rpx;
  font-size: 28rpx;
  color: #1a73e8;
  background: #fff;
  border-radius: 40rpx;
  text-align: center;
}
.btn-primary.small {
  flex: none;
  width: 160rpx;
  height: 72rpx;
  line-height: 72rpx;
  color: #fff;
  background: #1a73e8;
}
.btn-ghost {
  flex: 1;
  height: 80rpx;
  line-height: 80rpx;
  font-size: 28rpx;
  color: #fff;
  background: transparent;
  border: 2rpx solid rgba(255, 255, 255, 0.7);
  border-radius: 40rpx;
  text-align: center;
}
.hero-tip {
  margin-top: 20rpx;
  font-size: 22rpx;
  opacity: 0.85;
}
.stats {
  display: flex;
  margin: -24rpx 24rpx 0;
  padding: 24rpx 0;
  border-radius: 16rpx;
  background: #fff;
  box-shadow: 0 4rpx 20rpx rgba(26, 115, 232, 0.08);
}
.stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.stat-num {
  font-size: 34rpx;
  font-weight: 600;
  color: #1a73e8;
}
.stat-label {
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #909399;
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
.bind-input {
  flex: 1;
  height: 72rpx;
  padding: 0 24rpx;
  font-size: 28rpx;
  background: #f5f7fa;
  border-radius: 36rpx;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 22rpx 0;
  border-bottom: 2rpx solid #f2f3f5;
}
.row:last-child {
  border-bottom: none;
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
.row-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}
.reward {
  font-size: 28rpx;
  font-weight: 600;
  color: #e6a23c;
}
.row-status {
  margin-top: 6rpx;
  font-size: 22rpx;
}
.row-status.ok {
  color: #67c23a;
}
.row-status.warn {
  color: #e6a23c;
}
.row-status.muted {
  color: #909399;
}
.empty-inline {
  padding: 40rpx 0;
  font-size: 26rpx;
  color: #c0c4cc;
  text-align: center;
}
.disabled-mask {
  margin: 24rpx;
  padding: 24rpx;
  border-radius: 16rpx;
  font-size: 26rpx;
  color: #e6a23c;
  background: #fdf6ec;
  text-align: center;
}
</style>
