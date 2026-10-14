<template>
  <view class="container">
    <!-- 当前等级进度 -->
    <view class="card current-card">
      <view class="row-between">
        <view class="col">
          <text class="text-muted text-small">当前等级</text>
          <text class="cur-level">{{ account?.levelName || '普通会员' }}</text>
        </view>
        <view class="col" style="align-items: flex-end">
          <text class="text-muted text-small">成长值</text>
          <text class="cur-growth">{{ account?.growth || 0 }}</text>
        </view>
      </view>
      <view class="progress-bar" v-if="account?.nextLevelName">
        <view class="progress-fill" :style="{ width: progressPercent + '%' }"></view>
      </view>
      <text class="progress-text" v-if="account?.nextLevelName">
        距「{{ account.nextLevelName }}」还需 {{ account.nextLevelNeedGrowth }} 成长值
      </text>
      <text class="progress-text" v-else>已达最高等级，尊享全部会员权益</text>
    </view>

    <!-- 等级列表 -->
    <view
      v-for="l in levels"
      :key="String(l.id)"
      class="card level-card"
      :class="{ current: l.current, achieved: l.achieved }"
    >
      <view class="level-head">
        <view class="row">
          <text class="level-name">{{ l.levelName }}</text>
          <text class="level-badge current-badge" v-if="l.current">当前</text>
          <text class="level-badge" v-else-if="l.achieved">已达成</text>
        </view>
        <text class="level-threshold" v-if="l.growthThreshold && Number(l.growthThreshold) < 999999">
          成长值 ≥ {{ l.growthThreshold }}
        </text>
        <text class="level-threshold" v-else>注册即享</text>
      </view>

      <view class="benefits">
        <view class="benefit-item">
          <text class="benefit-value">{{ l.discountText || '无折扣' }}</text>
          <text class="benefit-label">消费折扣</text>
        </view>
        <view class="benefit-item">
          <text class="benefit-value">{{ l.pointsRate || 1 }} 倍</text>
          <text class="benefit-label">积分倍率</text>
        </view>
        <view class="benefit-item" v-if="Number(l.upgradeGiftPoints) > 0">
          <text class="benefit-value">{{ l.upgradeGiftPoints }}</text>
          <text class="benefit-label">升级礼积分</text>
        </view>
      </view>

      <text class="level-remark" v-if="l.remark">{{ l.remark }}</text>
    </view>

    <view class="card tip-card">
      <text class="text-muted text-small">
        成长值按实付消费金额累计（赠送余额抵扣部分不计），等级升级后立即生效。
      </text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { mpApi, type AccountInfo } from '@/api'

const levels = ref<Array<Record<string, unknown>>>([])
const account = ref<AccountInfo | null>(null)

const progressPercent = computed(() => {
  const p = account.value?.growthProgress
  return p ? Math.round(p * 100) : 0
})

async function load() {
  try {
    const [ls, acc] = await Promise.all([mpApi.levels(), mpApi.account()])
    levels.value = ls
    account.value = acc
  } catch (e) {
    console.warn('[level] 加载失败', e)
  }
}

onShow(() => {
  load()
})
</script>

<style scoped>
.current-card {
  background: linear-gradient(135deg, #1a73e8 0%, #4a90e2 100%);
  color: #fff;
}

.current-card .text-muted {
  color: rgba(255, 255, 255, 0.82);
}

.cur-level {
  font-size: 40rpx;
  font-weight: 700;
  margin-top: 8rpx;
}

.cur-growth {
  font-size: 36rpx;
  font-weight: 700;
  margin-top: 8rpx;
}

.progress-bar {
  height: 12rpx;
  background: rgba(255, 255, 255, 0.3);
  border-radius: 6rpx;
  margin-top: 24rpx;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: #fff;
  border-radius: 6rpx;
}

.progress-text {
  font-size: 22rpx;
  opacity: 0.9;
  margin-top: 12rpx;
  display: block;
}

/* 等级卡 */
.level-card {
  border: 3rpx solid transparent;
}

.level-card.current {
  border-color: #1a73e8;
  background: #f7faff;
}

.level-card.achieved:not(.current) {
  border-color: #d4e4fc;
}

.level-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.level-name {
  font-size: 32rpx;
  font-weight: 600;
}

.level-badge {
  font-size: 20rpx;
  color: #34a853;
  background: #e6f4ea;
  padding: 4rpx 14rpx;
  border-radius: 20rpx;
  margin-left: 12rpx;
}

.level-badge.current-badge {
  color: #fff;
  background: #1a73e8;
}

.level-threshold {
  font-size: 22rpx;
  color: #909399;
}

.benefits {
  display: flex;
  margin-top: 20rpx;
  padding-top: 20rpx;
  border-top: 2rpx solid #f5f7fa;
}

.benefit-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.benefit-value {
  font-size: 30rpx;
  font-weight: 600;
  color: #1a73e8;
}

.benefit-label {
  font-size: 22rpx;
  color: #909399;
  margin-top: 6rpx;
}

.level-remark {
  font-size: 22rpx;
  color: #909399;
  margin-top: 16rpx;
  display: block;
  line-height: 1.6;
}

.tip-card {
  background: #f5f7fa;
}
</style>
