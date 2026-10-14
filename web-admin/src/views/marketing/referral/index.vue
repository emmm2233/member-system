<template>
  <div class="page-container">
    <div class="page-card">
      <div class="stat-row">
        <div class="stat-box">
          <div class="stat-label">累计绑定</div>
          <div class="stat-value">{{ num(stats.bound) + num(stats.settled) + num(stats.invalid) }}</div>
        </div>
        <div class="stat-box">
          <div class="stat-label">已结算</div>
          <div class="stat-value success">{{ num(stats.settled) }}</div>
        </div>
        <div class="stat-box">
          <div class="stat-label">待首单</div>
          <div class="stat-value warning">{{ num(stats.bound) }}</div>
        </div>
        <div class="stat-box">
          <div class="stat-label">已失效</div>
          <div class="stat-value muted">{{ num(stats.invalid) }}</div>
        </div>
        <div class="stat-box">
          <div class="stat-label">结算转化率</div>
          <div class="stat-value">{{ num(stats.settleRate) }}%</div>
        </div>
        <div class="stat-box">
          <div class="stat-label">发放失败</div>
          <div class="stat-value" :class="num(stats.failedRewards) > 0 ? 'danger' : 'muted'">
            {{ num(stats.failedRewards) }}
          </div>
        </div>
      </div>
      <div class="toolbar">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="奖励发放规则"
          description="被推荐人达成条件后，推荐人与被推荐人各获得一份奖励；推荐人超出「每月上限」时只跳过推荐人侧，被推荐人奖励照发。资产入账使用 REF:{关系ID}:{对象} 幂等键，重复执行不会重复加钱。"
        />
      </div>
      <div class="toolbar">
        <el-button type="primary" plain @click="runSettleScan" :loading="busy" v-if="canSettle">
          <el-icon><Refresh /></el-icon>兜底结算（扫已绑定未结算）
        </el-button>
        <el-button plain @click="runRetryRewards" :loading="busy" v-if="canSettle">重试失败奖励</el-button>
      </div>
    </div>

    <div class="page-card">
      <el-tabs v-model="tab">
        <!-- 推荐关系 -->
        <el-tab-pane label="推荐关系" name="relations">
          <el-form inline @submit.prevent>
            <el-form-item label="状态">
              <el-select v-model="relQuery.status" clearable placeholder="全部" style="width: 130px" @change="loadRelations">
                <el-option label="已绑定" :value="1" />
                <el-option label="已结算" :value="2" />
                <el-option label="已失效" :value="3" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadRelations"><el-icon><Search /></el-icon>查询</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="relations" v-loading="loading" stripe border size="small">
            <el-table-column label="推荐人" min-width="170">
              <template #default="{ row }">
                <div>{{ row.referrer?.name || '-' }}</div>
                <div class="text-muted">{{ row.referrer?.memberNo }} · {{ row.referrer?.phone }}</div>
              </template>
            </el-table-column>
            <el-table-column label="被推荐人" min-width="170">
              <template #default="{ row }">
                <div>{{ row.invitee?.name || '-' }}</div>
                <div class="text-muted">{{ row.invitee?.memberNo }} · {{ row.invitee?.phone }}</div>
              </template>
            </el-table-column>
            <el-table-column label="渠道" width="90" align="center">
              <template #default="{ row }">{{ channelText(row.channel) }}</template>
            </el-table-column>
            <el-table-column label="绑定时间" width="160">
              <template #default="{ row }">{{ fmtTime(row.bindAt) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="statusType(row.status)">{{ row.statusText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="结算信息" min-width="180">
              <template #default="{ row }">
                <div v-if="row.status === 2">
                  <div>{{ fmtTime(row.settleAt) }}</div>
                  <div class="text-muted">触发单号 {{ row.triggerBizNo || '-' }}</div>
                </div>
                <span v-else class="text-muted">-</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="manualSettle(row)" v-if="canSettle && row.status !== 3">
                  {{ row.status === 2 ? '补发奖励' : '人工结算' }}
                </el-button>
                <el-button link type="danger" size="small" @click="invalidate(row)" v-if="canSettle && row.status === 1">
                  作废
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            class="pager"
            layout="total, prev, pager, next"
            :total="relTotal"
            :page-size="relQuery.pageSize"
            :current-page="relQuery.pageNum"
            @current-change="onRelPage"
          />
        </el-tab-pane>

        <!-- 奖励记录 -->
        <el-tab-pane label="奖励发放记录" name="rewards">
          <el-form inline @submit.prevent>
            <el-form-item label="会员ID">
              <el-input v-model="rwQuery.memberId" placeholder="精确匹配" clearable style="width: 140px" />
            </el-form-item>
            <el-form-item label="对象">
              <el-select v-model="rwQuery.rewardTarget" clearable placeholder="全部" style="width: 130px">
                <el-option label="推荐人" :value="1" />
                <el-option label="被推荐人" :value="2" />
              </el-select>
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="rwQuery.status" clearable placeholder="全部" style="width: 130px">
                <el-option label="待发放" :value="1" />
                <el-option label="已发放" :value="2" />
                <el-option label="发放失败" :value="3" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadRewards"><el-icon><Search /></el-icon>查询</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="rewards" v-loading="loading" stripe border size="small">
            <el-table-column label="会员" min-width="170">
              <template #default="{ row }">
                <div>{{ row.member?.name || '-' }}</div>
                <div class="text-muted">{{ row.member?.memberNo }} (ID {{ row.memberId }})</div>
              </template>
            </el-table-column>
            <el-table-column label="对象" width="90" align="center">
              <template #default="{ row }">{{ row.rewardTargetText }}</template>
            </el-table-column>
            <el-table-column label="奖励" width="150">
              <template #default="{ row }">
                <span class="reward">{{ row.rewardTypeText }} {{ rewardText(row) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="关系ID" width="80" align="center">
              <template #default="{ row }">#{{ row.referralId }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 2 ? 'success' : row.status === 3 ? 'danger' : 'warning'">
                  {{ row.statusText }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="失败原因" min-width="200">
              <template #default="{ row }">
                <span :class="row.failReason ? 'danger-text' : 'text-muted'">{{ row.failReason || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="发放时间" width="160">
              <template #default="{ row }">{{ fmtTime(row.issuedAt || row.createdAt) }}</template>
            </el-table-column>
          </el-table>
          <el-pagination
            class="pager"
            layout="total, prev, pager, next"
            :total="rwTotal"
            :page-size="rwQuery.pageSize"
            :current-page="rwQuery.pageNum"
            @current-change="onRwPage"
          />
        </el-tab-pane>

        <!-- 规则配置 -->
        <el-tab-pane label="活动规则" name="config">
          <div v-if="!canConfig" class="text-muted">无 referral:config 权限，无法查看与修改规则。</div>
          <el-form v-else label-width="180px" style="max-width: 760px">
            <el-divider content-position="left">活动开关与结算</el-divider>
            <el-form-item v-for="item in configItems" :key="item.key" :label="item.name">
              <el-switch
                v-if="item.type === 'BOOLEAN'"
                v-model="cfgForm[item.key]"
                active-value="true"
                inactive-value="false"
              />
              <el-select v-else-if="item.type === 'ENUM'" v-model="cfgForm[item.key]" style="width: 260px">
                <el-option v-for="o in item.options || []" :key="o" :label="rewardLabel(o)" :value="o" />
              </el-select>
              <el-input
                v-else-if="item.type === 'TEXT'"
                v-model="cfgForm[item.key]"
                type="textarea"
                :rows="2"
                style="width: 100%"
              />
              <el-input v-else v-model="cfgForm[item.key]" style="width: 260px" />
              <div class="text-muted hint">{{ item.desc }}</div>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="saveConfig" :loading="busy">保存规则</el-button>
            </el-form-item>
          </el-form>
          <el-alert
            v-if="canConfig"
            type="warning"
            :closable="false"
            show-icon
            title="改动即时生效"
            description="保存后立即影响新绑定与新结算；已结算的关系不会重算。修改「邀请码签名密钥」会让所有旧邀请码立刻失效，活动进行中请勿修改。"
          />
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { referralApi, type ReferralConfigItem } from '@/api/admin'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const canSettle = computed(() => userStore.has('referral:settle'))
const canConfig = computed(() => userStore.has('referral:config'))

const tab = ref('relations')
const loading = ref(false)
const busy = ref(false)

const stats = ref<Record<string, unknown>>({})
const relations = ref<Array<Record<string, any>>>([])
const relTotal = ref(0)
const relQuery = reactive({ status: undefined as number | undefined, pageNum: 1, pageSize: 20 })

const rewards = ref<Array<Record<string, any>>>([])
const rwTotal = ref(0)
const rwQuery = reactive({
  memberId: '',
  rewardTarget: undefined as number | undefined,
  status: undefined as number | undefined,
  pageNum: 1,
  pageSize: 20
})

const configItems = ref<ReferralConfigItem[]>([])
const cfgForm = reactive<Record<string, string>>({})

function num(v: unknown): number {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}
function fmtTime(v?: string): string {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '-'
}
function channelText(c?: string): string {
  return c === 'POSTER' ? '海报' : c === 'QRCODE' ? '门店码' : c === 'LINK' ? '链接' : c || '-'
}
function statusType(s: number): 'success' | 'warning' | 'info' {
  return s === 2 ? 'success' : s === 1 ? 'warning' : 'info'
}
function rewardText(row: Record<string, any>): string {
  const v = Number(row.rewardValue || 0)
  if (row.rewardType === 3) return '1 张'
  if (row.rewardType === 1 || row.rewardType === 4) return String(v)
  return v.toFixed(2) + ' 元'
}
function rewardLabel(code: string): string {
  return (
    {
      NONE: '不奖励',
      POINTS: '积分',
      BALANCE: '余额',
      GIFT_BALANCE: '赠送余额',
      COUPON: '优惠券',
      GROWTH: '成长值',
      REGISTER: '注册即奖',
      FIRST_CONSUME: '被推荐人首单',
      FIRST_RECHARGE: '被推荐人首次充值'
    }[code] || code
  )
}

async function loadStats() {
  stats.value = (await referralApi.stats()) as Record<string, unknown>
}

async function loadRelations() {
  loading.value = true
  try {
    const res = await referralApi.relations({ ...relQuery })
    relations.value = res.list
    relTotal.value = res.total
  } finally {
    loading.value = false
  }
}
function onRelPage(p: number) {
  relQuery.pageNum = p
  loadRelations()
}

async function loadRewards() {
  loading.value = true
  try {
    const res = await referralApi.rewards({
      rewardTarget: rwQuery.rewardTarget,
      status: rwQuery.status,
      memberId: rwQuery.memberId ? Number(rwQuery.memberId) : undefined,
      pageNum: rwQuery.pageNum,
      pageSize: rwQuery.pageSize
    })
    rewards.value = res.list
    rwTotal.value = res.total
  } finally {
    loading.value = false
  }
}
function onRwPage(p: number) {
  rwQuery.pageNum = p
  loadRewards()
}

async function loadConfig() {
  if (!canConfig.value) return
  const items = await referralApi.config()
  configItems.value = items
  items.forEach((i) => {
    cfgForm[i.key] = i.value === null || i.value === undefined ? '' : String(i.value)
  })
}

async function saveConfig() {
  busy.value = true
  try {
    await referralApi.saveConfig({ ...cfgForm })
    ElMessage.success('推荐规则已保存')
    await Promise.all([loadStats(), loadConfig()])
  } finally {
    busy.value = false
  }
}

async function runSettleScan() {
  busy.value = true
  try {
    const settled = await referralApi.settleScan()
    ElMessage.success(`兜底结算完成，本次结算 ${settled} 笔`)
    await refreshAll()
  } finally {
    busy.value = false
  }
}

async function runRetryRewards() {
  busy.value = true
  try {
    const done = await referralApi.retryRewards()
    ElMessage.success(`奖励重试完成，处理 ${done} 条`)
    await refreshAll()
  } finally {
    busy.value = false
  }
}

async function manualSettle(row: Record<string, any>) {
  const { value } = await ElMessageBox.prompt(
    row.status === 2
      ? '该关系已结算，此处只补发缺失的奖励。可填写操作原因（选填）'
      : '将立即结算并发放双方奖励，可填写操作原因（选填）',
    row.status === 2 ? '补发奖励' : '人工结算',
    { inputPlaceholder: '如：客户投诉补偿', inputValue: '' }
  )
  await referralApi.settle(row.id, value)
  ElMessage.success('操作完成')
  await refreshAll()
}

async function invalidate(row: Record<string, any>) {
  const { value } = await ElMessageBox.prompt('作废后该关系不再发放奖励，且被推荐人可重新绑定。请填写原因', '作废推荐关系', {
    inputPlaceholder: '如：风控判定为刷单',
    inputValidator: (v) => (v && v.trim().length > 0 ? true : '请填写作废原因')
  })
  await referralApi.invalidate(row.id, value)
  ElMessage.success('已作废')
  await refreshAll()
}

async function refreshAll() {
  await Promise.all([loadStats(), loadRelations(), loadRewards()])
}

onMounted(async () => {
  await Promise.all([loadStats(), loadRelations(), loadRewards(), loadConfig()])
})
</script>

<style scoped>
.stat-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.stat-box {
  flex: 1 1 140px;
  padding: 12px 16px;
  background: #f7f9fc;
  border-radius: 6px;
}
.stat-label {
  font-size: 12px;
  color: #909399;
}
.stat-value {
  margin-top: 6px;
  font-size: 22px;
  font-weight: 600;
  color: #1a73e8;
}
.stat-value.success {
  color: #67c23a;
}
.stat-value.warning {
  color: #e6a23c;
}
.stat-value.danger {
  color: #f56c6c;
}
.stat-value.muted {
  color: #909399;
}
.toolbar {
  margin-top: 14px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.hint {
  width: 100%;
  font-size: 12px;
  line-height: 1.5;
}
.reward {
  font-weight: 600;
  color: #1a73e8;
}
.danger-text {
  color: #f56c6c;
}
</style>
