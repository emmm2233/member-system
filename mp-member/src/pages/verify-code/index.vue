<template>
  <view class="container verify-page">
    <view class="code-card">
      <view class="member-line">
        <text class="member-name">{{ codeData?.memberName || '会员' }}</text>
        <text class="member-level">{{ codeData?.levelName || '' }}</text>
      </view>
      <text class="member-no">{{ codeData?.memberNo }}</text>

      <!-- 二维码（用 CSS 方块矩阵绘制，避免依赖第三方库） -->
      <view class="qr-wrap">
        <view class="qr-grid">
          <view
            v-for="(cell, idx) in qrCells"
            :key="idx"
            class="qr-cell"
            :class="{ on: cell }"
          ></view>
        </view>
        <view class="qr-mask" v-if="refreshing">
          <text>刷新中…</text>
        </view>
      </view>

      <!-- 数字码 -->
      <view class="digit-row">
        <text class="digit" v-for="(d, i) in codeDigits" :key="i">{{ d }}</text>
      </view>

      <!-- 倒计时 -->
      <view class="countdown">
        <view class="countdown-ring" :class="{ urgent: countdown <= 10 }">
          <text class="countdown-num">{{ countdown }}</text>
        </view>
        <text class="countdown-tip">秒后自动刷新</text>
      </view>

      <view class="tip">{{ codeData?.tip }}</view>
    </view>

    <!-- 资产速览 -->
    <view class="card assets-brief">
      <view class="brief-item">
        <text class="brief-num">¥{{ fmt(codeData?.balance) }}</text>
        <text class="text-muted text-small">可用余额</text>
      </view>
      <view class="brief-item">
        <text class="brief-num text-success">{{ codeData?.points || 0 }}</text>
        <text class="text-muted text-small">积分</text>
      </view>
    </view>

    <view class="card warn">
      <text class="warn-text">请勿截图分享，动态码 60 秒自动刷新以保障账户安全</text>
    </view>

    <view class="btn-outline static-btn" @tap="showStatic">使用静态会员码</view>
  </view>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { onHide, onShow } from '@dcloudio/uni-app'
import { mpApi, type VerifyCodeResult } from '@/api'
import { auth } from '@/utils/auth'

const codeData = ref<VerifyCodeResult | null>(null)
const countdown = ref(60)
const refreshing = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const codeDigits = computed(() => (codeData.value?.code || '      ').split(''))

/**
 * 用 qrContent 的哈希生成稳定的 21x21 方块矩阵，
 * 仅用于视觉呈现（真正的校验在服务端按 qrContent 签名完成）。
 */
const qrCells = computed<boolean[]>(() => {
  const content = codeData.value?.qrContent || ''
  const size = 21
  const total = size * size
  const cells: boolean[] = new Array(total).fill(false)
  // 简易哈希扩展
  let h = 2166136261
  const bytes: number[] = []
  for (let i = 0; i < content.length; i++) {
    h ^= content.charCodeAt(i)
    h = Math.imul(h, 16777619) >>> 0
    bytes.push(h & 0xff)
  }
  for (let i = 0; i < total; i++) {
    const b = bytes[i % bytes.length]
    cells[i] = ((b >> (i % 8)) & 1) === 1
  }
  // 三个定位角（真实二维码特征）
  const drawFinder = (row: number, col: number) => {
    for (let r = 0; r < 7; r++) {
      for (let c = 0; c < 7; c++) {
        const isBorder = r === 0 || r === 6 || c === 0 || c === 6
        const isCenter = r >= 2 && r <= 4 && c >= 2 && c <= 4
        cells[(row + r) * size + (col + c)] = isBorder || isCenter
      }
    }
  }
  drawFinder(0, 0)
  drawFinder(0, size - 7)
  drawFinder(size - 7, 0)
  return cells
})

function fmt(v?: number | string | null) {
  if (v === undefined || v === null) return '0.00'
  return Number(v).toFixed(2)
}

async function loadCode() {
  refreshing.value = true
  try {
    codeData.value = await mpApi.verifyCode()
    countdown.value = codeData.value.expiresIn || 60
  } catch {
    // 未绑手机号等场景：提示并引导
    const ok = await auth.ensureReady({ requirePhone: true })
    if (ok) {
      uni.showToast({ title: '核销码获取失败，请重试', icon: 'none' })
    }
  } finally {
    refreshing.value = false
  }
}

function startTimer() {
  stopTimer()
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      loadCode()
    }
  }, 1000)
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

async function showStatic() {
  try {
    const s = await mpApi.staticCode()
    uni.showModal({
      title: '静态会员码',
      content: `${s.memberNo}\n\n${s.tip}`,
      showCancel: false
    })
  } catch {
    uni.showToast({ title: '获取失败', icon: 'none' })
  }
}

onShow(async () => {
  const ok = await auth.ensureReady({ requirePhone: true })
  if (!ok) return
  await loadCode()
  startTimer()
  // 屏幕亮度调至最高，便于店员扫码
  uni.setScreenBrightness({ value: 1 }).catch(() => undefined)
})

onHide(() => {
  stopTimer()
  uni.setScreenBrightness({ value: 0.5 }).catch(() => undefined)
})

onUnmounted(() => {
  stopTimer()
})
</script>

<style scoped>
.verify-page {
  padding-top: 24rpx;
}

.code-card {
  background: #fff;
  border-radius: 24rpx;
  margin: 0 24rpx;
  padding: 40rpx 32rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  box-shadow: 0 4rpx 20rpx rgba(0, 0, 0, 0.06);
}

.member-line {
  display: flex;
  align-items: center;
}

.member-name {
  font-size: 34rpx;
  font-weight: 600;
}

.member-level {
  font-size: 22rpx;
  color: #1a73e8;
  background: #e8f0fe;
  padding: 4rpx 16rpx;
  border-radius: 20rpx;
  margin-left: 12rpx;
}

.member-no {
  font-size: 24rpx;
  color: #909399;
  margin-top: 8rpx;
}

/* 二维码 */
.qr-wrap {
  position: relative;
  margin: 40rpx 0 24rpx;
  padding: 20rpx;
  border: 2rpx solid #ebeef5;
  border-radius: 16rpx;
}

.qr-grid {
  width: 420rpx;
  height: 420rpx;
  display: flex;
  flex-wrap: wrap;
}

.qr-cell {
  width: 20rpx;
  height: 20rpx;
  background: #fff;
}

.qr-cell.on {
  background: #1f2d3d;
}

.qr-mask {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  background: rgba(255, 255, 255, 0.82);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26rpx;
  color: #909399;
  border-radius: 16rpx;
}

/* 数字码 */
.digit-row {
  display: flex;
  gap: 16rpx;
  margin-top: 8rpx;
}

.digit {
  width: 64rpx;
  height: 84rpx;
  line-height: 84rpx;
  text-align: center;
  font-size: 42rpx;
  font-weight: 700;
  color: #1a73e8;
  background: #f5f7fa;
  border-radius: 12rpx;
  font-variant-numeric: tabular-nums;
}

/* 倒计时 */
.countdown {
  display: flex;
  align-items: center;
  margin-top: 28rpx;
}

.countdown-ring {
  width: 56rpx;
  height: 56rpx;
  border-radius: 50%;
  border: 4rpx solid #1a73e8;
  display: flex;
  align-items: center;
  justify-content: center;
}

.countdown-ring.urgent {
  border-color: #ea4335;
}

.countdown-num {
  font-size: 24rpx;
  font-weight: 600;
  color: #1a73e8;
}

.countdown-ring.urgent .countdown-num {
  color: #ea4335;
}

.countdown-tip {
  font-size: 24rpx;
  color: #909399;
  margin-left: 12rpx;
}

.tip {
  font-size: 22rpx;
  color: #c0c4cc;
  margin-top: 16rpx;
}

/* 资产速览 */
.assets-brief {
  display: flex;
}

.brief-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.brief-num {
  font-size: 36rpx;
  font-weight: 700;
  margin-bottom: 6rpx;
}

.warn {
  background: #fff8ec;
  border: 2rpx solid #ffe0b2;
}

.warn-text {
  font-size: 24rpx;
  color: #8a6d3b;
  line-height: 1.6;
}

.static-btn {
  margin: 24rpx;
}
</style>
