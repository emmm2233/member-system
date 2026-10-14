<template>
  <view class="container">
    <!-- 当前资产 -->
    <view class="card balance-card">
      <view class="col">
        <text class="text-muted text-small">当前可用余额</text>
        <text class="balance-num">¥{{ fmt(account?.available) }}</text>
        <text class="text-muted text-small">
          本金 ¥{{ fmt(account?.balance) }} ＋ 赠送 ¥{{ fmt(account?.giftBalance) }}
        </text>
      </view>
      <view class="col points-col">
        <text class="text-muted text-small">积分</text>
        <text class="points-num">{{ account?.points || 0 }}</text>
      </view>
    </view>

    <!-- 充值方案 -->
    <view class="card">
      <view class="card-title">
        <text>选择充值方案</text>
        <text class="text-muted text-small" v-if="config?.mockEnabled">模拟支付环境</text>
      </view>

      <view class="plan-grid">
        <view
          v-for="p in plans"
          :key="p.planId"
          class="plan-card"
          :class="{ active: selectedPlanId === p.planId }"
          @tap="selectPlan(p)"
        >
          <view class="plan-amount">¥{{ fmt(p.rechargeAmount) }}</view>
          <view class="plan-gift">{{ p.giftText || '无赠送' }}</view>
          <view class="plan-flag" v-if="p.rechargeAmount >= 1000">超值</view>
        </view>
      </view>

      <!-- 自定义金额 -->
      <view class="custom-row">
        <text class="custom-label">自定义金额</text>
        <input
          class="custom-input"
          type="digit"
          placeholder="输入充值金额"
          :value="customAmount"
          @input="onCustomInput"
        />
      </view>
    </view>

    <!-- 支付方式 -->
    <view class="card">
      <view class="card-title"><text>支付方式</text></view>
      <view class="pay-item">
        <view class="row">
          <view class="pay-icon" style="background: #e6f4ea; color: #34a853">微</view>
          <text class="pay-name">微信支付</text>
        </view>
        <text class="pay-check">✓</text>
      </view>
      <view class="pay-tip" v-if="config?.mockEnabled">
        当前为模拟支付环境，点击"立即支付"后走完整下单/回调/入账链路，无需真实付款
      </view>
      <view class="pay-tip" v-else>
        将调起微信支付完成付款，支付成功后余额自动到账
      </view>
    </view>

    <!-- 说明 -->
    <view class="card notice">
      <text class="notice-title">充值说明</text>
      <view class="notice-item">· 赠送余额不可提现、不可退款，仅用于消费抵扣</view>
      <view class="notice-item">· 消费时优先扣减赠送余额</view>
      <view class="notice-item">· 充值金额可用于全门店消费（以门店公告为准）</view>
      <view class="notice-item">· 订单 15 分钟内未支付将自动关闭</view>
    </view>

    <!-- 底部支付栏 -->
    <view class="pay-bar">
      <view class="col">
        <text class="pay-bar-label">应付金额</text>
        <text class="pay-bar-amount">¥{{ fmt(payAmount) }}</text>
      </view>
      <view class="btn-primary pay-bar-btn" :class="{ disabled: paying }" @tap="submit">
        {{ paying ? '处理中…' : '立即支付' }}
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { mpApi, type AccountInfo, type RechargePlan } from '@/api'
import { auth } from '@/utils/auth'

const plans = ref<RechargePlan[]>([])
const account = ref<AccountInfo | null>(null)
const config = ref<Record<string, unknown> | null>(null)
const selectedPlanId = ref<number | undefined>(undefined)
const customAmount = ref('')
const paying = ref(false)

const payAmount = computed(() => {
  if (customAmount.value && Number(customAmount.value) > 0) {
    return Number(customAmount.value)
  }
  const p = plans.value.find((x) => x.planId === selectedPlanId.value)
  return p ? Number(p.rechargeAmount) : 0
})

function fmt(v?: number | string | null) {
  if (v === undefined || v === null) return '0.00'
  return Number(v).toFixed(2)
}

function selectPlan(p: RechargePlan) {
  selectedPlanId.value = p.planId
  customAmount.value = ''
}

function onCustomInput(e: { detail: { value: string } }) {
  customAmount.value = e.detail.value
  if (e.detail.value) {
    selectedPlanId.value = undefined
  }
}

async function load() {
  try {
    const [p, a, c] = await Promise.all([
      mpApi.rechargePlans(),
      mpApi.account(),
      mpApi.rechargeConfig().catch(() => null)
    ])
    plans.value = p
    account.value = a
    config.value = c
    if (!selectedPlanId.value && !customAmount.value && p.length) {
      // 默认选中中档方案（第 2 个或第 1 个）
      const def = p.length >= 2 ? p[1] : p[0]
      selectedPlanId.value = def.planId
    }
  } catch (e) {
    console.warn('[recharge] 加载失败', e)
  }
}

/** 完整在线充值链路：创建充值单 → 统一下单 → 调起支付 → 轮询结果 */
async function submit() {
  if (paying.value) return
  if (!payAmount.value || payAmount.value <= 0) {
    uni.showToast({ title: '请选择方案或输入金额', icon: 'none' })
    return
  }
  const ready = await auth.ensureReady({ requirePhone: true })
  if (!ready) return

  paying.value = true
  uni.showLoading({ title: '正在下单', mask: true })
  try {
    // 1. 创建充值单
    const order = await mpApi.createRechargeOrder(
      selectedPlanId.value
        ? { planId: selectedPlanId.value }
        : { customAmount: Number(customAmount.value) }
    )

    // 2. 统一下单
    const prepay = await mpApi.prepay({ bizType: 1, bizNo: order.orderNo, channel: 'MOCK' })
    uni.hideLoading()

    // 3. 调起支付
    if (prepay.payParams?.mock === 'true') {
      // 模拟渠道：确认即视为支付成功（走真实回调链路）
      const confirmed = await confirmMockPay(order.orderNo, prepay.amount)
      if (!confirmed) {
        await mpApi.closePay(prepay.paymentNo).catch(() => undefined)
        uni.showToast({ title: '已取消支付', icon: 'none' })
        return
      }
      uni.showLoading({ title: '支付处理中', mask: true })
      await mpApi.mockSuccess(prepay.paymentNo)
    } else {
      // 真实渠道：调起微信支付
      await requestWechatPayment(prepay.payParams)
    }

    // 4. 轮询支付结果（服务端回调入账）
    uni.showLoading({ title: '确认到账', mask: true })
    const result = await pollPayResult(prepay.paymentNo)
    uni.hideLoading()

    if (result.status === 3) {
      uni.showModal({
        title: '充值成功',
        content: `充值 ¥${fmt(order.rechargeAmount)}${order.giftAmount > 0 ? `，赠送 ¥${fmt(order.giftAmount)}` : ''}${
          order.giftPoints > 0 ? `，赠送 ${order.giftPoints} 积分` : ''
        }\n当前余额 ¥${fmt(result ? payAmount.value : 0)}`,
        showCancel: false,
        confirmText: '查看钱包',
        success: () => {
          uni.redirectTo({ url: '/pages/wallet/index' })
        }
      })
    } else {
      uni.showToast({ title: '支付结果确认中，请稍后在账单查看', icon: 'none', duration: 2500 })
    }
  } catch (e) {
    uni.hideLoading()
    console.warn('[recharge] 支付失败', e)
  } finally {
    paying.value = false
    load()
  }
}

/** 模拟支付确认弹窗 */
function confirmMockPay(orderNo: string, amount: number): Promise<boolean> {
  return new Promise((resolve) => {
    uni.showModal({
      title: '模拟支付',
      content: `订单 ${orderNo}\n应付 ¥${amount}\n\n当前为模拟支付环境，点击"确认支付"将走完整回调入账链路`,
      confirmText: '确认支付',
      cancelText: '取消',
      success: (res) => resolve(!!res.confirm),
      fail: () => resolve(false)
    })
  })
}

/** 真实微信支付（预留给接入后的调用路径） */
function requestWechatPayment(params: Record<string, string>): Promise<void> {
  return new Promise((resolve, reject) => {
    // #ifdef MP-WEIXIN
    uni.requestPayment({
      provider: 'wxpay',
      timeStamp: params.timeStamp,
      nonceStr: params.nonceStr,
      package: params.package,
      signType: (params.signType as 'MD5' | 'HMAC-SHA256' | 'RSA') || 'RSA',
      paySign: params.paySign,
      success: () => resolve(),
      fail: (err) => reject(err)
    })
    // #endif
    // #ifndef MP-WEIXIN
    reject(new Error('当前环境不支持微信支付'))
    // #endif
  })
}

/** 退避轮询支付结果 */
async function pollPayResult(paymentNo: string) {
  const delays = [800, 1500, 2500, 4000, 6000, 8000]
  let last = await mpApi.payStatus(paymentNo)
  if (last.status === 3) return last
  for (const d of delays) {
    await sleep(d)
    last = await mpApi.payStatus(paymentNo)
    if (last.status === 3) return last
  }
  return last
}

function sleep(ms: number) {
  return new Promise((r) => setTimeout(r, ms))
}

onLoad(() => {
  load()
})

onShow(() => {
  load()
})
</script>

<style scoped>
.balance-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: linear-gradient(135deg, #1a73e8 0%, #4a90e2 100%);
  color: #fff;
}

.balance-card .text-muted {
  color: rgba(255, 255, 255, 0.82);
}

.balance-num {
  font-size: 52rpx;
  font-weight: 700;
  margin: 8rpx 0;
  font-variant-numeric: tabular-nums;
}

.points-col {
  align-items: flex-end;
}

.points-num {
  font-size: 40rpx;
  font-weight: 700;
  margin-top: 8rpx;
}

/* 方案卡片 */
.plan-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 20rpx;
}

.plan-card {
  width: calc(50% - 10rpx);
  border: 3rpx solid #ebeef5;
  border-radius: 16rpx;
  padding: 24rpx 16rpx;
  text-align: center;
  position: relative;
  box-sizing: border-box;
}

.plan-card.active {
  border-color: #1a73e8;
  background: #e8f0fe;
}

.plan-amount {
  font-size: 40rpx;
  font-weight: 700;
  color: #1f2d3d;
}

.plan-gift {
  font-size: 22rpx;
  color: #f29c1f;
  margin-top: 8rpx;
  min-height: 32rpx;
}

.plan-flag {
  position: absolute;
  top: -1rpx;
  right: -1rpx;
  background: #ea4335;
  color: #fff;
  font-size: 20rpx;
  padding: 4rpx 12rpx;
  border-radius: 0 14rpx 0 14rpx;
}

/* 自定义金额 */
.custom-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 28rpx;
  padding-top: 24rpx;
  border-top: 2rpx solid #f5f7fa;
}

.custom-label {
  font-size: 28rpx;
  color: #606266;
}

.custom-input {
  flex: 1;
  text-align: right;
  font-size: 32rpx;
  font-weight: 600;
  margin-left: 20rpx;
}

/* 支付方式 */
.pay-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 0;
}

.pay-icon {
  width: 64rpx;
  height: 64rpx;
  border-radius: 16rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28rpx;
  font-weight: 600;
  margin-right: 20rpx;
}

.pay-name {
  font-size: 30rpx;
}

.pay-check {
  color: #1a73e8;
  font-size: 32rpx;
  font-weight: 700;
}

.pay-tip {
  font-size: 22rpx;
  color: #909399;
  line-height: 1.6;
  margin-top: 12rpx;
  background: #f5f7fa;
  padding: 16rpx;
  border-radius: 12rpx;
}

/* 说明 */
.notice-title {
  font-size: 28rpx;
  font-weight: 600;
  display: block;
  margin-bottom: 12rpx;
}

.notice-item {
  font-size: 24rpx;
  color: #909399;
  line-height: 1.9;
}

/* 底部支付栏 */
.pay-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20rpx 32rpx calc(20rpx + env(safe-area-inset-bottom));
  box-shadow: 0 -4rpx 20rpx rgba(0, 0, 0, 0.06);
}

.pay-bar-label {
  font-size: 24rpx;
  color: #909399;
}

.pay-bar-amount {
  font-size: 40rpx;
  font-weight: 700;
  color: #ea4335;
  font-variant-numeric: tabular-nums;
}

.pay-bar-btn {
  width: 300rpx;
  height: 84rpx;
  line-height: 84rpx;
}

.pay-bar-btn.disabled {
  background: #bcd6fa;
}

/* 预留底部空间 */
.container {
  padding-bottom: 180rpx;
}
</style>
