<template>
  <view class="container">
    <view class="card" v-if="detail">
      <view class="amount-head">
        <text class="amount-big">¥{{ fmt(detail.paidAmount) }}</text>
        <text class="amount-status">{{ detail.statusText }}</text>
      </view>

      <view class="kv"><text class="k">订单号</text><text class="v">{{ detail.orderNo }}</text></view>
      <view class="kv"><text class="k">类型</text><text class="v">{{ detail.orderTypeText }}</text></view>
      <view class="kv"><text class="k">门店</text><text class="v">{{ detail.storeName || '-' }}</text></view>
      <view class="kv"><text class="k">下单时间</text><text class="v">{{ detail.createdAt }}</text></view>
      <view class="kv" v-if="detail.paidAt"><text class="k">支付时间</text><text class="v">{{ detail.paidAt }}</text></view>
      <view class="kv" v-if="Number(detail.refundAmount) > 0">
        <text class="k">已退款</text><text class="v text-danger">¥{{ fmt(detail.refundAmount) }}</text>
      </view>
      <view class="kv" v-if="detail.remark"><text class="k">备注</text><text class="v">{{ detail.remark }}</text></view>
    </view>

    <!-- 商品明细 -->
    <view class="card" v-if="items.length">
      <view class="card-title"><text>商品明细</text></view>
      <view v-for="(it, i) in items" :key="i" class="item-row">
        <text class="item-name">{{ it.productName }} × {{ it.qty }}</text>
        <text class="amount">¥{{ fmt(it.totalAmount) }}</text>
      </view>
    </view>

    <!-- 金额明细 -->
    <view class="card" v-if="detail">
      <view class="card-title"><text>金额明细</text></view>
      <view class="kv"><text class="k">商品原价</text><text class="v">¥{{ fmt(detail.totalAmount) }}</text></view>
      <view class="kv" v-if="Number(detail.discountAmount) > 0">
        <text class="k">会员折扣</text><text class="v text-danger">-¥{{ fmt(detail.discountAmount) }}</text>
      </view>
      <view class="kv" v-if="Number(detail.couponAmount) > 0">
        <text class="k">优惠券</text><text class="v text-danger">-¥{{ fmt(detail.couponAmount) }}</text>
      </view>
      <view class="kv" v-if="Number(detail.pointDeductAmount) > 0">
        <text class="k">积分抵扣</text>
        <text class="v text-danger">-¥{{ fmt(detail.pointDeductAmount) }}（{{ detail.pointDeductPoints }} 分）</text>
      </view>
      <view class="kv total"><text class="k">实付</text><text class="v">¥{{ fmt(detail.paidAmount) }}</text></view>
      <view class="kv" v-if="Number(detail.giftAmount) > 0">
        <text class="k">充值赠送</text><text class="v text-warning">+¥{{ fmt(detail.giftAmount) }}</text>
      </view>
      <view class="kv" v-if="Number(detail.pointsGained) > 0">
        <text class="k">获得积分</text><text class="v text-success">+{{ detail.pointsGained }}</text>
      </view>
    </view>

    <!-- 支付方式 -->
    <view class="card" v-if="payments.length">
      <view class="card-title"><text>支付方式</text></view>
      <view v-for="(p, i) in payments" :key="i" class="kv">
        <text class="k">{{ p.payMethodText }}{{ p.accountTypeText ? ' · ' + p.accountTypeText : '' }}</text>
        <text class="v">¥{{ fmt(p.amount) }}</text>
      </view>
    </view>

    <view class="card tip-card">
      <text class="text-muted text-small">如对订单有疑问，请携带会员号到门店前台咨询</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { mpApi } from '@/api'
import { fmtMoney } from '@/utils/format'

const detail = ref<Record<string, unknown> | null>(null)

const items = computed(() => (detail.value?.items as Array<Record<string, unknown>>) || [])
const payments = computed(() => (detail.value?.payments as Array<Record<string, unknown>>) || [])

function fmt(v?: number | string | null) {
  return fmtMoney(v)
}

async function load(orderNo: string) {
  try {
    detail.value = await mpApi.orderDetail(orderNo)
  } catch (e) {
    console.warn('[bill detail] 加载失败', e)
  }
}

onLoad((options) => {
  const orderNo = (options?.orderNo as string) || ''
  if (orderNo) {
    load(orderNo)
  }
})
</script>

<style scoped>
.amount-head {
  display: flex;
  align-items: baseline;
  justify-content: center;
  flex-direction: column;
  padding-bottom: 24rpx;
  border-bottom: 2rpx solid #f5f7fa;
  margin-bottom: 16rpx;
}

.amount-big {
  font-size: 60rpx;
  font-weight: 700;
  color: #1f2d3d;
  font-variant-numeric: tabular-nums;
}

.amount-status {
  font-size: 24rpx;
  color: #34a853;
  margin-top: 8rpx;
}

.kv {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 14rpx 0;
}

.kv .k {
  font-size: 26rpx;
  color: #909399;
  flex-shrink: 0;
}

.kv .v {
  font-size: 26rpx;
  color: #303133;
  text-align: right;
  margin-left: 24rpx;
  word-break: break-all;
}

.kv.total .k,
.kv.total .v {
  font-size: 30rpx;
  font-weight: 700;
  color: #ea4335;
}

.item-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14rpx 0;
  border-bottom: 2rpx solid #f5f7fa;
}

.item-row:last-child {
  border-bottom: none;
}

.item-name {
  font-size: 28rpx;
}

.tip-card {
  background: #f5f7fa;
}
</style>
