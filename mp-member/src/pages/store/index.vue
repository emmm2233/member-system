<template>
  <view class="container">
    <view v-for="s in stores" :key="String(s.id)" class="card store-card">
      <view class="store-head">
        <text class="store-name">{{ s.storeName }}</text>
      </view>
      <view class="store-line">
        <text class="store-label">地址</text>
        <text class="store-value">{{ s.address || '暂未填写' }}</text>
      </view>
      <view class="store-line" v-if="s.businessHours">
        <text class="store-label">营业时间</text>
        <text class="store-value">{{ s.businessHours }}</text>
      </view>
      <view class="store-line" v-if="s.contactPhone">
        <text class="store-label">联系电话</text>
        <text class="store-value" @tap="callPhone(String(s.contactPhone))">{{ s.contactPhone }}</text>
      </view>
      <view class="store-actions">
        <view class="btn-outline store-btn" @tap="callPhone(String(s.contactPhone || ''))">拨打电话</view>
        <view class="btn-primary store-btn" @tap="openLocation(s)">导航到店</view>
      </view>
    </view>

    <view v-if="!stores.length" class="empty">暂无门店信息</view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { mpApi } from '@/api'

const stores = ref<Array<Record<string, unknown>>>([])

async function load() {
  try {
    stores.value = await mpApi.stores()
  } catch (e) {
    console.warn('[store] 加载失败', e)
  }
}

function callPhone(phone: string) {
  if (!phone) {
    uni.showToast({ title: '该门店未提供电话', icon: 'none' })
    return
  }
  uni.makePhoneCall({ phoneNumber: phone }).catch(() => undefined)
}

function openLocation(store: Record<string, unknown>) {
  const lat = Number(store.latitude)
  const lng = Number(store.longitude)
  if (!lat || !lng) {
    uni.showToast({ title: '该门店未设置定位', icon: 'none' })
    return
  }
  uni.openLocation({
    latitude: lat,
    longitude: lng,
    name: String(store.storeName || ''),
    address: String(store.address || ''),
    fail: () => uni.showToast({ title: '打开地图失败', icon: 'none' })
  })
}

onShow(() => {
  load()
})
</script>

<style scoped>
.store-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 18rpx;
  border-bottom: 2rpx solid #f5f7fa;
  margin-bottom: 18rpx;
}

.store-name {
  font-size: 32rpx;
  font-weight: 600;
}

.store-line {
  display: flex;
  padding: 8rpx 0;
}

.store-label {
  width: 140rpx;
  font-size: 26rpx;
  color: #909399;
  flex-shrink: 0;
}

.store-value {
  flex: 1;
  font-size: 26rpx;
  color: #303133;
  line-height: 1.6;
}

.store-actions {
  display: flex;
  gap: 20rpx;
  margin-top: 24rpx;
}

.store-btn {
  flex: 1;
}
</style>
