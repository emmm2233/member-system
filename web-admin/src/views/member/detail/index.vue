<template>
  <div class="page-container" v-loading="loading">
    <template v-if="detail">
      <!-- 顶部信息 -->
      <div class="page-card">
        <div class="member-head">
          <el-avatar :size="56" :src="detail.member.avatar" style="background: var(--brand-primary)">
            {{ (detail.member.nickname || detail.member.realName || 'M').charAt(0) }}
          </el-avatar>
          <div class="head-main">
            <div class="head-name">
              <span>{{ detail.member.nickname || detail.member.realName || '未命名会员' }}</span>
              <el-tag v-if="detail.member.levelName" size="small" effect="dark">
                {{ detail.member.levelName }}
              </el-tag>
              <el-tag
                size="small"
                :type="detail.member.status === 1 ? 'success' : detail.member.status === 2 ? 'warning' : 'info'"
              >
                {{ detail.member.statusText }}
              </el-tag>
            </div>
            <div class="head-sub">
              <span>会员号 {{ detail.member.memberNo }}</span>
              <el-divider direction="vertical" />
              <span>{{ detail.member.phoneMasked || '未绑定手机' }}</span>
              <el-divider direction="vertical" />
              <span>归属 {{ detail.member.storeName || '-' }}</span>
              <el-divider direction="vertical" />
              <span>入会 {{ detail.member.createdAt || '-' }}</span>
            </div>
          </div>
          <el-space>
            <el-button type="primary" @click="openRecharge" v-if="userStore.has('account:recharge')">
              <el-icon><Wallet /></el-icon>充值
            </el-button>
            <el-button @click="goOrders">
              <el-icon><List /></el-icon>订单
            </el-button>
          </el-space>
        </div>
      </div>

      <!-- 资产卡片 -->
      <el-row :gutter="12" class="mb-12">
        <el-col :span="6">
          <div class="asset-card">
            <div class="asset-label">本金余额</div>
            <div class="asset-value">¥{{ fmt(detail.account.balance) }}</div>
            <div class="text-muted">可退</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="asset-card">
            <div class="asset-label">赠送余额</div>
            <div class="asset-value" style="color: var(--brand-warning)">
              ¥{{ fmt(detail.account.giftBalance) }}
            </div>
            <div class="text-muted">不可退，消费优先扣减</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="asset-card">
            <div class="asset-label">积分</div>
            <div class="asset-value" style="color: var(--brand-success)">{{ detail.account.points ?? 0 }}</div>
            <div class="text-muted">可抵扣 / 兑换</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="asset-card">
            <div class="asset-label">成长值</div>
            <div class="asset-value">{{ detail.account.growth ?? 0 }}</div>
            <el-progress
              v-if="detail.account.nextLevelName"
              :percentage="Math.round((detail.account.levelProgress || 0) * 100)"
              :stroke-width="6"
              :show-text="false"
              class="mt-8"
            />
            <div class="text-muted">
              <template v-if="detail.account.nextLevelName">
                距 {{ detail.account.nextLevelName }} 还需 {{ detail.account.nextLevelNeedGrowth }} 成长值
              </template>
              <template v-else>已是最高等级</template>
            </div>
          </div>
        </el-col>
      </el-row>

      <!-- 统计 + 卡券 -->
      <el-row :gutter="12" class="mb-12">
        <el-col :span="16">
          <div class="page-card">
            <div class="page-title"><span>消费统计</span></div>
            <el-descriptions :column="3" border size="small">
              <el-descriptions-item label="累计充值">¥{{ fmt(detail.account.totalRecharge) }}</el-descriptions-item>
              <el-descriptions-item label="累计消费">¥{{ fmt(detail.account.totalConsume) }}</el-descriptions-item>
              <el-descriptions-item label="消费笔数">
                {{ detail.account.totalConsumeCount ?? 0 }}
              </el-descriptions-item>
              <el-descriptions-item label="近 30 天">¥{{ fmt(detail.stat.payAmount30d) }}</el-descriptions-item>
              <el-descriptions-item label="近 90 天">¥{{ fmt(detail.stat.payAmount90d) }}</el-descriptions-item>
              <el-descriptions-item label="近 12 月">¥{{ fmt(detail.stat.payAmount12m) }}</el-descriptions-item>
              <el-descriptions-item label="客单价">¥{{ fmt(detail.stat.avgOrderAmount) }}</el-descriptions-item>
              <el-descriptions-item label="首次消费">{{ detail.stat.firstConsumeAt || '-' }}</el-descriptions-item>
              <el-descriptions-item label="最近消费">{{ detail.stat.lastConsumeAt || '-' }}</el-descriptions-item>
            </el-descriptions>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="page-card">
            <div class="page-title"><span>卡券概览</span></div>
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="可用券">{{ detail.couponSummary.unused }}</el-descriptions-item>
              <el-descriptions-item label="7 天内到期">
                <el-tag v-if="detail.couponSummary.expiringIn7d" type="warning" size="small">
                  {{ detail.couponSummary.expiringIn7d }}
                </el-tag>
                <span v-else>0</span>
              </el-descriptions-item>
              <el-descriptions-item label="已用券">{{ detail.couponSummary.used }}</el-descriptions-item>
              <el-descriptions-item label="次卡剩余">
                {{ detail.cardSummary.normal }} 张 / {{ detail.cardSummary.totalRemainTimes }} 次
              </el-descriptions-item>
            </el-descriptions>
          </div>
        </el-col>
      </el-row>

      <!-- 流水页签 -->
      <div class="page-card">
        <el-tabs v-model="activeTab" @tab-change="onTabChange">
          <el-tab-pane label="余额流水" name="balance">
            <el-table :data="balanceFlows" size="small" border v-loading="flowLoading">
              <el-table-column prop="occurTime" label="时间" width="160" />
              <el-table-column label="类型" width="90">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain">{{ row.bizTypeText }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="账户" width="70">
                <template #default="{ row }">{{ row.accountTypeText }}</template>
              </el-table-column>
              <el-table-column label="变动" width="110" align="right">
                <template #default="{ row }">
                  <span class="amount" :class="Number(row.changeAmount) >= 0 ? 'amount-positive' : 'amount-negative'">
                    {{ Number(row.changeAmount) >= 0 ? '+' : '' }}{{ fmt(row.changeAmount) }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="变动后" width="110" align="right">
                <template #default="{ row }">{{ fmt(row.afterAmount) }}</template>
              </el-table-column>
              <el-table-column prop="bizNo" label="关联单号" width="200" />
              <el-table-column prop="remark" label="备注" min-width="150" />
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="积分明细" name="point">
            <el-table :data="pointFlows" size="small" border v-loading="flowLoading">
              <el-table-column prop="occurTime" label="时间" width="160" />
              <el-table-column label="类型" width="100">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain">{{ row.bizTypeText }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="变动" width="100" align="right">
                <template #default="{ row }">
                  <span class="amount" :class="Number(row.changePoints) >= 0 ? 'amount-positive' : 'amount-negative'">
                    {{ Number(row.changePoints) >= 0 ? '+' : '' }}{{ row.changePoints }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="变动后" width="100" align="right">
                <template #default="{ row }">{{ row.afterPoints }}</template>
              </el-table-column>
              <el-table-column prop="expireTime" label="到期时间" width="160" />
              <el-table-column prop="bizNo" label="关联单号" width="200" />
              <el-table-column prop="remark" label="备注" min-width="150" />
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="最近订单" name="order">
            <el-table :data="detail.recentOrders" size="small" border>
              <el-table-column prop="orderNo" label="订单号" width="200" />
              <el-table-column prop="orderTypeText" label="类型" width="100" />
              <el-table-column label="应付" width="110" align="right">
                <template #default="{ row }">¥{{ fmt(row.payableAmount) }}</template>
              </el-table-column>
              <el-table-column label="实收" width="110" align="right">
                <template #default="{ row }">¥{{ fmt(row.paidAmount) }}</template>
              </el-table-column>
              <el-table-column prop="statusText" label="状态" width="100" />
              <el-table-column prop="createdAt" label="下单时间" width="170" />
            </el-table>
            <el-empty v-if="!detail.recentOrders.length" description="暂无订单" />
          </el-tab-pane>

          <el-tab-pane label="账实核对" name="reconcile">
            <el-alert
              :type="reconcileData?.pointsBatchMatch && reconcileData?.balanceMatch ? 'success' : 'error'"
              :closable="false"
              show-icon
              :title="
                reconcileData?.pointsBatchMatch && reconcileData?.balanceMatch
                  ? '该会员账实相符（账户余额 = 流水合计，积分 = 批次余量）'
                  : '发现差异，请核查'
              "
            />
            <el-descriptions :column="3" border size="small" class="mt-12" v-if="reconcileData">
              <el-descriptions-item label="账户本金">¥{{ fmt(reconcileData.balance) }}</el-descriptions-item>
              <el-descriptions-item label="本金流水合计">¥{{ fmt(reconcileData.balanceFlowSum) }}</el-descriptions-item>
              <el-descriptions-item label="结果">
                <el-tag :type="reconcileData.balanceMatch ? 'success' : 'danger'" size="small">
                  {{ reconcileData.balanceMatch ? '一致' : '不一致' }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="账户赠送">¥{{ fmt(reconcileData.giftBalance) }}</el-descriptions-item>
              <el-descriptions-item label="赠送流水合计">¥{{ fmt(reconcileData.giftFlowSum) }}</el-descriptions-item>
              <el-descriptions-item label="结果">
                <el-tag :type="reconcileData.giftMatch ? 'success' : 'danger'" size="small">
                  {{ reconcileData.giftMatch ? '一致' : '不一致' }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="账户积分">{{ reconcileData.points }}</el-descriptions-item>
              <el-descriptions-item label="批次余量合计">{{ reconcileData.pointsBatchSum }}</el-descriptions-item>
              <el-descriptions-item label="结果">
                <el-tag :type="reconcileData.pointsBatchMatch ? 'success' : 'danger'" size="small">
                  {{ reconcileData.pointsBatchMatch ? '一致' : '不一致' }}
                </el-tag>
              </el-descriptions-item>
            </el-descriptions>
          </el-tab-pane>
        </el-tabs>
      </div>
    </template>

    <!-- 充值弹窗（与会员列表共用逻辑） -->
    <el-dialog v-model="rechargeVisible" title="会员充值" width="560px">
      <div class="plan-grid">
        <div
          v-for="plan in plans"
          :key="plan.id"
          class="plan-card"
          :class="{ active: rechargeForm.planId === plan.id }"
          @click="selectPlan(plan)"
        >
          <div class="plan-amount">¥{{ fmt(plan.rechargeAmount) }}</div>
          <div class="plan-gift">送 ¥{{ fmt(plan.giftAmount) }}<span v-if="plan.giftPoints"> + {{ plan.giftPoints }} 分</span></div>
        </div>
      </div>
      <el-form label-width="90px" class="mt-12">
        <el-form-item label="充值金额">
          <el-input-number v-model="rechargeForm.rechargeAmount" :min="0.01" :precision="2" :step="100" />
        </el-form-item>
        <el-form-item label="支付方式">
          <el-radio-group v-model="rechargeForm.payMethod">
            <el-radio :value="2">现金</el-radio>
            <el-radio :value="3">线下扫码</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rechargeVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRecharge">确认充值</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { accountApi, memberApi, rechargeApi, type FlowRow, type MemberDetail, type RechargePlan } from '@/api'
import { useUserStore } from '@/store/user'

defineOptions({ name: 'MemberDetail' })

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const memberId = Number(route.params.id)

const loading = ref(false)
const flowLoading = ref(false)
const submitting = ref(false)
const detail = ref<MemberDetail | null>(null)
const balanceFlows = ref<FlowRow[]>([])
const pointFlows = ref<FlowRow[]>([])
const reconcileData = ref<Record<string, number | boolean> | null>(null)
const activeTab = ref('balance')

const plans = ref<RechargePlan[]>([])
const rechargeVisible = ref(false)
const rechargeForm = reactive({
  planId: undefined as number | undefined,
  rechargeAmount: 100,
  payMethod: 2
})

function fmt(v?: number | string | boolean | null) {
  if (v === undefined || v === null) return '0.00'
  return Number(v).toFixed(2)
}

async function loadDetail() {
  loading.value = true
  try {
    detail.value = await memberApi.overview(memberId)
  } finally {
    loading.value = false
  }
}

async function loadFlows() {
  flowLoading.value = true
  try {
    const [b, p, rec] = await Promise.all([
      accountApi.balanceFlows(memberId, { pageNum: 1, pageSize: 50 }),
      accountApi.pointFlows(memberId, { pageNum: 1, pageSize: 50 }),
      accountApi.reconcileOne(memberId)
    ])
    balanceFlows.value = b.list
    pointFlows.value = p.list
    reconcileData.value = rec as unknown as Record<string, number | boolean>
  } finally {
    flowLoading.value = false
  }
}

function onTabChange() {
  // 数据已一次性加载，无需重复请求
}

function goOrders() {
  router.push({ path: '/order/consume', query: { memberId } })
}

async function openRecharge() {
  if (!plans.value.length) {
    plans.value = await rechargeApi.plans()
  }
  const first = plans.value[0]
  if (first) selectPlan(first)
  rechargeVisible.value = true
}

function selectPlan(plan: RechargePlan) {
  rechargeForm.planId = plan.id
  rechargeForm.rechargeAmount = plan.rechargeAmount
}

async function submitRecharge() {
  submitting.value = true
  try {
    await rechargeApi.recharge({
      memberId,
      planId: rechargeForm.planId,
      rechargeAmount: rechargeForm.rechargeAmount,
      payMethod: rechargeForm.payMethod
    })
    ElMessage.success('充值成功')
    rechargeVisible.value = false
    await Promise.all([loadDetail(), loadFlows()])
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  await loadDetail()
  await loadFlows()
})
</script>

<style scoped>
.member-head {
  display: flex;
  align-items: center;
  gap: 16px;
}

.head-main {
  flex: 1;
}

.head-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
  font-weight: 600;
}

.head-sub {
  margin-top: 6px;
  font-size: 13px;
  color: #909399;
}

.asset-card {
  background: #fff;
  border-radius: var(--card-radius);
  padding: 16px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

.asset-label {
  font-size: 13px;
  color: #909399;
}

.asset-value {
  font-size: 24px;
  font-weight: 700;
  margin: 6px 0 4px;
  color: var(--brand-primary);
  font-variant-numeric: tabular-nums;
}

.plan-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
}

.plan-card {
  border: 2px solid #e4e7ed;
  border-radius: 8px;
  padding: 12px;
  cursor: pointer;
  text-align: center;
}

.plan-card.active {
  border-color: var(--brand-primary);
  background: var(--brand-primary-light);
}

.plan-amount {
  font-size: 20px;
  font-weight: 700;
}

.plan-gift {
  font-size: 12px;
  color: var(--brand-warning);
}
</style>
