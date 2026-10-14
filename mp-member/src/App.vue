<script setup lang="ts">
import { onLaunch, onShow } from '@dcloudio/uni-app'
import { auth } from '@/utils/auth'

onLaunch((options) => {
  // 分享/海报带邀请码时先暂存，登录时透传给后端绑定推荐关系
  const inviteCode = (options?.query?.inviteCode as string) || ''
  if (inviteCode) {
    uni.setStorageSync('mp_invite_code', inviteCode)
  }
  // 静默登录：不阻塞启动，失败由各页面按需重试
  auth.silentLogin()
})

onShow(() => {
  // 从后台回到前台时若 token 已失效则重新静默登录
  if (!auth.isLoggedIn()) {
    auth.silentLogin(true)
  }
})
</script>

<style>
/* ==================== 全局样式 ==================== */
page {
  background-color: #f5f7fa;
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Helvetica Neue', sans-serif;
  color: #303133;
  font-size: 28rpx;
}

.container {
  min-height: 100vh;
  padding-bottom: 32rpx;
  box-sizing: border-box;
}

/* 卡片 */
.card {
  background: #fff;
  border-radius: 20rpx;
  padding: 28rpx;
  margin: 20rpx 24rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.04);
}

.card-title {
  font-size: 30rpx;
  font-weight: 600;
  margin-bottom: 20rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* 文本 */
.text-muted {
  color: #909399;
  font-size: 24rpx;
}

.text-small {
  font-size: 22rpx;
}

.amount {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.text-primary {
  color: #1a73e8;
}

.text-success {
  color: #34a853;
}

.text-warning {
  color: #f29c1f;
}

.text-danger {
  color: #ea4335;
}

/* 布局 */
.row {
  display: flex;
  align-items: center;
}

.row-between {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.col {
  display: flex;
  flex-direction: column;
}

.center {
  display: flex;
  align-items: center;
  justify-content: center;
}

.mt-8 {
  margin-top: 8rpx;
}

.mt-16 {
  margin-top: 16rpx;
}

.mt-24 {
  margin-top: 24rpx;
}

.mb-16 {
  margin-bottom: 16rpx;
}

/* 按钮 */
.btn-primary {
  background: #1a73e8;
  color: #fff;
  border-radius: 44rpx;
  height: 88rpx;
  line-height: 88rpx;
  text-align: center;
  font-size: 30rpx;
  font-weight: 600;
}

.btn-primary[disabled] {
  background: #bcd6fa;
  color: #fff;
}

.btn-outline {
  border: 2rpx solid #1a73e8;
  color: #1a73e8;
  border-radius: 44rpx;
  height: 80rpx;
  line-height: 76rpx;
  text-align: center;
  font-size: 28rpx;
}

/* 资产数字 */
.asset-num {
  font-size: 44rpx;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

/* 空状态 */
.empty {
  padding: 100rpx 0;
  text-align: center;
  color: #c0c4cc;
  font-size: 26rpx;
}
</style>
