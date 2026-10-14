<template>
  <view class="container">
    <view class="tabs">
      <view
        v-for="t in tabs"
        :key="t.label"
        class="tab"
        :class="{ active: currentStatus === t.value }"
        @tap="switchTab(t.value)"
      >
        {{ t.label }}
      </view>
      <view class="tab-action" @tap="readAll" v-if="unread > 0">全部已读</view>
    </view>

    <view v-if="list.length">
      <view
        v-for="m in list"
        :key="m.id"
        class="msg"
        :class="{ unread: m.status === 2 || m.status === 1 }"
        @tap="openMessage(m)"
      >
        <view class="msg-head">
          <text class="msg-title">{{ m.title || tplText(m.tplCode) }}</text>
          <text class="msg-time">{{ shortTime(m.sentAt || m.createdAt) }}</text>
        </view>
        <view class="msg-body">{{ m.content }}</view>
        <view class="msg-foot">
          <text class="msg-channel">{{ m.channelText }}</text>
          <text class="msg-state" :class="stateClass(m.status)">{{ m.statusText }}</text>
        </view>
      </view>

      <view class="list-end" v-if="list.length >= total">共 {{ total }} 条</view>
    </view>

    <view v-else class="empty">
      <text>暂无消息</text>
      <view class="empty-tip">消费、充值、生日与活动通知都会出现在这里</view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { mpApi, type MessageRow } from '@/api'
import { shortTime } from '@/utils/format'

const tabs: Array<{ label: string; value?: number }> = [
  { label: '全部', value: undefined },
  { label: '未读', value: 2 },
  { label: '已读', value: 4 }
]

const currentStatus = ref<number | undefined>(undefined)
const list = ref<MessageRow[]>([])
const total = ref(0)
const unread = ref(0)
const pageNum = ref(1)
const pageSize = 20

async function load(reset = true) {
  if (reset) pageNum.value = 1
  try {
    const res = await mpApi.messages({
      status: currentStatus.value,
      pageNum: pageNum.value,
      pageSize
    })
    list.value = reset ? res.list : [...list.value, ...res.list]
    total.value = res.total
    unread.value = await mpApi.unreadCount().catch(() => 0)
  } catch (e) {
    /* 统一提示 */
  }
}

function switchTab(v?: number) {
  currentStatus.value = v
  load(true)
}

async function openMessage(m: MessageRow) {
  if (m.status === 4) return
  try {
    await mpApi.readMessage(m.id)
    m.status = 4
    m.statusText = '已读'
    unread.value = Math.max(0, unread.value - 1)
  } catch (e) {
    /* 统一提示 */
  }
}

async function readAll() {
  try {
    await mpApi.readAllMessages()
    uni.showToast({ title: '已全部标记为已读', icon: 'success' })
    load(true)
  } catch (e) {
    /* 统一提示 */
  }
}

function stateClass(s: number): string {
  return s === 4 ? 'muted' : s === 3 ? 'danger' : 'ok'
}

function tplText(code: string): string {
  const map: Record<string, string> = {
    CONSUME_SUCCESS: '消费成功通知',
    RECHARGE_SUCCESS: '充值到账通知',
    COUPON_EXPIRE: '优惠券到期提醒',
    BIRTHDAY_GIFT: '生日祝福',
    CARD_EXPIRE: '次卡到期提醒',
    REFERRAL_REWARD: '推荐奖励到账',
    LEVEL_UPGRADE: '等级升级通知',
    PAY_SUCCESS: '支付成功通知',
    PAY_FAIL: '支付未完成'
  }
  return map[code] || '系统通知'
}

onShow(() => load(true))
onPullDownRefresh(async () => {
  await load(true)
  uni.stopPullDownRefresh()
})
</script>

<style scoped>
.container {
  min-height: 100vh;
  padding-bottom: 40rpx;
  background: #f5f7fa;
}
.tabs {
  display: flex;
  align-items: center;
  padding: 0 24rpx;
  background: #fff;
  border-bottom: 2rpx solid #f2f3f5;
}
.tab {
  padding: 26rpx 28rpx;
  font-size: 28rpx;
  color: #606266;
}
.tab.active {
  color: #1a73e8;
  font-weight: 600;
  border-bottom: 4rpx solid #1a73e8;
}
.tab-action {
  margin-left: auto;
  font-size: 24rpx;
  color: #1a73e8;
}
.msg {
  margin: 20rpx 24rpx 0;
  padding: 26rpx;
  border-radius: 16rpx;
  background: #fff;
}
.msg.unread {
  border-left: 6rpx solid #1a73e8;
}
.msg-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.msg-title {
  font-size: 30rpx;
  font-weight: 600;
  color: #303133;
}
.msg-time {
  font-size: 22rpx;
  color: #c0c4cc;
}
.msg-body {
  margin-top: 14rpx;
  font-size: 26rpx;
  line-height: 1.7;
  color: #606266;
}
.msg-foot {
  margin-top: 16rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.msg-channel {
  font-size: 22rpx;
  color: #c0c4cc;
}
.msg-state {
  font-size: 22rpx;
}
.msg-state.ok {
  color: #67c23a;
}
.msg-state.muted {
  color: #909399;
}
.msg-state.danger {
  color: #f56c6c;
}
.list-end {
  padding: 32rpx 0;
  font-size: 24rpx;
  color: #c0c4cc;
  text-align: center;
}
.empty {
  padding-top: 200rpx;
  text-align: center;
}
.empty text {
  font-size: 30rpx;
  color: #909399;
}
.empty-tip {
  margin-top: 16rpx;
  font-size: 24rpx;
  color: #c0c4cc;
}
</style>
