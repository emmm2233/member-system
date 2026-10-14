<template>
  <div class="page-container">
    <!-- 对账结果 -->
    <div class="page-card">
      <div class="page-title">
        <span>资产对账</span>
        <el-button type="primary" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon>重新对账
        </el-button>
      </div>

      <el-result
        v-if="result"
        :icon="result.diffCount === 0 ? 'success' : 'error'"
        :title="result.conclusion"
        :sub-title="`校验口径：账户本金/赠送余额 = 流水合计；账户积分 = 积分批次余量合计`"
      >
        <template #extra>
          <el-space>
            <el-statistic title="本金差异账户数" :value="result.balanceDiffCount" />
            <el-statistic title="积分差异账户数" :value="result.pointDiffCount" />
            <el-statistic title="差异合计" :value="result.diffCount" />
          </el-space>
        </template>
      </el-result>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        :title="`对账时间：${result?.checkTime || '-'}`"
        class="mt-12"
      />
    </div>

    <!-- 负债汇总 -->
    <div class="page-card">
      <div class="page-title"><span>储值与积分负债</span></div>
      <el-row :gutter="12">
        <el-col :span="6">
          <el-statistic title="本金负债（可退）" :value="Number(liability.balanceLiability ?? 0)" :precision="2" prefix="¥" />
        </el-col>
        <el-col :span="6">
          <el-statistic title="赠送负债（不可退）" :value="Number(liability.giftBalanceLiability ?? 0)" :precision="2" prefix="¥" />
        </el-col>
        <el-col :span="6">
          <el-statistic title="积分负债" :value="Number(liability.pointsLiability ?? 0)" />
        </el-col>
        <el-col :span="6">
          <el-statistic title="账户总数" :value="Number(liability.memberCount ?? 0)" />
        </el-col>
      </el-row>
    </div>

    <!-- 单会员核对 -->
    <div class="page-card">
      <div class="page-title"><span>单会员账实核对（排障）</span></div>
      <el-input
        v-model="memberId"
        placeholder="输入会员 ID"
        style="width: 220px"
        @keyup.enter="checkOne"
      >
        <template #append>
          <el-button @click="checkOne">核对</el-button>
        </template>
      </el-input>

      <el-descriptions v-if="one" :column="3" border size="small" class="mt-12">
        <el-descriptions-item label="账户本金">¥{{ fmt(Number(one.balance)) }}</el-descriptions-item>
        <el-descriptions-item label="本金流水合计">¥{{ fmt(Number(one.balanceFlowSum)) }}</el-descriptions-item>
        <el-descriptions-item label="结果">
          <el-tag :type="one.balanceMatch ? 'success' : 'danger'" size="small">
            {{ one.balanceMatch ? '一致' : '不一致' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="账户赠送">¥{{ fmt(Number(one.giftBalance)) }}</el-descriptions-item>
        <el-descriptions-item label="赠送流水合计">¥{{ fmt(Number(one.giftFlowSum)) }}</el-descriptions-item>
        <el-descriptions-item label="结果">
          <el-tag :type="one.giftMatch ? 'success' : 'danger'" size="small">
            {{ one.giftMatch ? '一致' : '不一致' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="账户积分">{{ one.points }}</el-descriptions-item>
        <el-descriptions-item label="积分批次余量">{{ one.pointsBatchSum }}</el-descriptions-item>
        <el-descriptions-item label="结果">
          <el-tag :type="one.pointsBatchMatch ? 'success' : 'danger'" size="small">
            {{ one.pointsBatchMatch ? '一致' : '不一致' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="账户成长值">{{ one.growth }}</el-descriptions-item>
        <el-descriptions-item label="成长值流水合计">{{ one.growthFlowSum }}</el-descriptions-item>
        <el-descriptions-item label="结果">
          <el-tag :type="one.growthMatch ? 'success' : 'danger'" size="small">
            {{ one.growthMatch ? '一致' : '不一致' }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { accountApi } from '@/api'

const loading = ref(false)
const result = ref<{
  balanceDiffCount: number
  pointDiffCount: number
  diffCount: number
  conclusion: string
  checkTime: string
} | null>(null)
const liability = ref<Record<string, number>>({})
const memberId = ref('')
const one = ref<Record<string, unknown> | null>(null)

function fmt(v: number) {
  return Number.isFinite(v) ? v.toFixed(2) : '0.00'
}

async function load() {
  loading.value = true
  try {
    const [r, l] = await Promise.all([accountApi.reconcile(), accountApi.liability()])
    result.value = r
    liability.value = l as unknown as Record<string, number>
  } finally {
    loading.value = false
  }
}

async function checkOne() {
  if (!memberId.value) {
    ElMessage.warning('请输入会员 ID')
    return
  }
  one.value = (await accountApi.reconcileOne(Number(memberId.value))) as Record<string, unknown>
}

onMounted(load)
</script>
