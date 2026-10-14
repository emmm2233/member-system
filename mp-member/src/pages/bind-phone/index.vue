<template>
  <view class="container">
    <view class="card">
      <view class="card-title"><text>绑定手机号</text></view>
      <text class="tip-text">
        绑定手机号后可使用在线充值、查看账单、到店核销等完整会员服务。
        手机号同时作为会员识别依据，可与门店已建档的会员卡自动关联。
      </text>

      <view class="form-item">
        <text class="form-label">手机号</text>
        <input
          class="form-input"
          type="number"
          maxlength="11"
          placeholder="请输入手机号"
          :value="phone"
          @input="onInput"
        />
      </view>

      <view v-if="mockMode" class="mock-tip">
        当前为联调模式（未配置微信 AppID），可直接输入手机号完成绑定；
        正式环境将调用微信「手机号快速验证」能力自动获取。
      </view>

      <view class="btn-primary submit-btn" :class="{ disabled: submitting }" @tap="submit">
        {{ submitting ? '绑定中…' : '确认绑定' }}
      </view>

      <!-- 真实环境：微信手机号快速验证按钮 -->
      <button
        v-if="!mockMode"
        class="wechat-phone-btn"
        open-type="getPhoneNumber"
        @getphonenumber="onGetPhoneNumber"
      >
        使用微信手机号一键绑定
      </button>
    </view>

    <view class="card notice">
      <text class="notice-title">绑定说明</text>
      <view class="notice-item">· 一个手机号只能绑定一个会员账号</view>
      <view class="notice-item">· 若该手机号已在门店建档，绑定后会自动关联并同步资产</view>
      <view class="notice-item">· 若已被其他微信账号绑定，请联系门店前台处理</view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { mpApi } from '@/api'
import { auth } from '@/utils/auth'
import { tokenStore } from '@/utils/request'

const phone = ref('')
const submitting = ref(false)
const mockMode = ref(false)

function onInput(e: { detail: { value: string } }) {
  phone.value = e.detail.value
}

async function load() {
  try {
    const status = await mpApi.configStatus()
    mockMode.value = !!status.mockLogin
  } catch {
    mockMode.value = true
  }
}

async function submit() {
  if (submitting.value) return
  if (!/^1[3-9]\d{9}$/.test(phone.value)) {
    uni.showToast({ title: '请输入正确的手机号', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    const res = await mpApi.bindPhone({ phone: phone.value })
    if (res.conflict) {
      uni.showModal({
        title: '无法绑定',
        content: res.tip || '该手机号已绑定其他微信账号，请联系门店处理',
        showCancel: false
      })
      return
    }
    // 关联到已存在会员时，后端会返回新的 token，需要替换
    if (res.accessToken && res.refreshToken) {
      tokenStore.set(res.accessToken, res.refreshToken)
    }
    uni.showToast({ title: res.merged ? '已关联既有会员卡' : '绑定成功', icon: 'success' })
    setTimeout(() => {
      uni.navigateBack()
    }, 900)
  } catch (e) {
    console.warn('[bind-phone] 绑定失败', e)
  } finally {
    submitting.value = false
  }
}

/** 微信手机号快速验证（正式环境） */
async function onGetPhoneNumber(e: { detail: { code?: string; errMsg?: string } }) {
  const code = e.detail?.code
  if (!code) {
    uni.showToast({ title: '已取消授权', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    const res = await mpApi.bindPhone({ code })
    if (res.conflict) {
      uni.showModal({ title: '无法绑定', content: res.tip || '该手机号已绑定其他账号', showCancel: false })
      return
    }
    if (res.accessToken && res.refreshToken) {
      tokenStore.set(res.accessToken, res.refreshToken)
    }
    uni.showToast({ title: '绑定成功', icon: 'success' })
    setTimeout(() => uni.navigateBack(), 900)
  } finally {
    submitting.value = false
  }
}

onLoad(() => {
  load()
})
</script>

<style scoped>
.tip-text {
  font-size: 24rpx;
  color: #909399;
  line-height: 1.8;
  display: block;
  margin-bottom: 24rpx;
}

.form-item {
  display: flex;
  align-items: center;
  border-bottom: 2rpx solid #f5f7fa;
  padding: 24rpx 0;
}

.form-label {
  width: 150rpx;
  font-size: 28rpx;
  color: #606266;
}

.form-input {
  flex: 1;
  font-size: 32rpx;
  letter-spacing: 2rpx;
}

.mock-tip {
  font-size: 22rpx;
  color: #8a6d3b;
  background: #fff8ec;
  border-radius: 12rpx;
  padding: 16rpx;
  margin-top: 24rpx;
  line-height: 1.7;
}

.submit-btn {
  margin-top: 40rpx;
}

.submit-btn.disabled {
  background: #bcd6fa;
}

.wechat-phone-btn {
  margin-top: 24rpx;
  background: #34a853;
  color: #fff;
  border-radius: 44rpx;
  height: 88rpx;
  line-height: 88rpx;
  font-size: 30rpx;
  border: none;
}

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

.notice {
  background: #f5f7fa;
}
</style>
