<template>
  <div class="page-container">
    <el-steps :active="step" align-center class="mb-12">
      <el-step title="选择优惠券" />
      <el-step title="选择发放人群" />
      <el-step title="确认发放" />
    </el-steps>

    <!-- 步骤 1 -->
    <div class="page-card" v-show="step === 0">
      <div class="page-title"><span>① 选择要发放的优惠券</span></div>
      <el-table :data="tpls" size="small" border highlight-current-row @current-change="onTplSelect">
        <el-table-column width="50">
          <template #default="{ row }">
            <el-radio :value="row.id" :model-value="selectedTplId" @change="selectedTplId = row.id">
              <span></span>
            </el-radio>
          </template>
        </el-table-column>
        <el-table-column label="券名称" min-width="180">
          <template #default="{ row }">
            <div class="tpl-name">{{ row.tplName }}</div>
            <div class="text-muted">{{ typeText(row.couponType) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="面额/折扣" width="120">
          <template #default="{ row }">{{ faceText(row) }}</template>
        </el-table-column>
        <el-table-column label="门槛" width="120">
          <template #default="{ row }">
            {{ Number(row.minAmount) > 0 ? `满 ${fmt(row.minAmount)}` : '无门槛' }}
          </template>
        </el-table-column>
        <el-table-column label="剩余库存" width="110">
          <template #default="{ row }">
            {{ row.totalQty < 0 ? '不限量' : row.totalQty - row.issuedQty }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!tpls.length" description="暂无券模板，请先创建" />
    </div>

    <!-- 步骤 2 -->
    <div class="page-card" v-show="step === 1">
      <div class="page-title"><span>② 选择发放人群</span></div>

      <el-radio-group v-model="form.targetType" class="mb-12" @change="onTargetChange">
        <el-radio-button value="MEMBER">指定会员</el-radio-button>
        <el-radio-button value="CONDITION">按条件筛选</el-radio-button>
        <el-radio-button value="ALL">全部会员</el-radio-button>
      </el-radio-group>

      <!-- 指定会员 -->
      <div v-if="form.targetType === 'MEMBER'">
        <el-form label-width="110px">
          <el-form-item label="手机号/会员号">
            <el-input
              v-model="memberInput"
              type="textarea"
              :rows="4"
              placeholder="每行一个，或用逗号/空格分隔；支持手机号或会员号"
            />
          </el-form-item>
          <el-form-item>
            <el-button @click="resolveMembers" :loading="resolving">解析会员</el-button>
            <span class="text-muted" style="margin-left: 12px">
              已解析 {{ form.memberIds.length }} 人
            </span>
          </el-form-item>
        </el-form>
        <el-alert v-if="resolveErrors.length" type="warning" :closable="false" show-icon class="mb-12">
          <template #title>
            {{ resolveErrors.length }} 条无法识别：{{ resolveErrors.slice(0, 5).join('；') }}
          </template>
        </el-alert>
      </div>

      <!-- 条件筛选 -->
      <div v-else-if="form.targetType === 'CONDITION'">
        <el-form label-width="110px">
          <el-form-item label="会员等级">
            <el-select v-model="form.condition!.levelIds" multiple placeholder="不限" style="width: 100%">
              <el-option v-for="l in levels" :key="l.id" :label="l.levelName" :value="l.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="会员标签">
            <el-select v-model="form.condition!.tagIds" multiple placeholder="不限（多选为同时满足）" style="width: 100%">
              <el-option v-for="t in tags" :key="t.id" :label="t.tagName" :value="t.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="入会时间">
            <el-date-picker
              v-model="createdRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              start-placeholder="开始"
              end-placeholder="结束"
            />
          </el-form-item>
          <el-form-item label="最近消费">
            <el-date-picker
              v-model="consumeRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              start-placeholder="开始"
              end-placeholder="结束"
            />
            <span class="text-muted" style="margin-left: 8px">留空表示不限；用于筛选沉睡会员</span>
          </el-form-item>
          <el-form-item label="累计消费 ≥">
            <el-input-number v-model="form.condition!.minTotalConsume" :min="0" :precision="2" :step="100" />
          </el-form-item>
          <el-form-item label="余额 ≥">
            <el-input-number v-model="form.condition!.minBalance" :min="0" :precision="2" :step="100" />
          </el-form-item>
          <el-form-item label="生日月份">
            <el-select v-model="form.condition!.birthdayMonth" placeholder="不限" clearable style="width: 140px">
              <el-option v-for="m in 12" :key="m" :label="m + ' 月'" :value="m" />
            </el-select>
          </el-form-item>
        </el-form>
        <el-button type="primary" plain @click="doEstimate" :loading="estimating">
          <el-icon><Search /></el-icon>预估目标人数
        </el-button>
        <el-alert
          v-if="estimate !== null"
          :type="estimate > 0 ? 'success' : 'warning'"
          :closable="false"
          show-icon
          class="mt-12"
          :title="estimate > 0 ? `符合条件 ${estimate} 人` : '没有符合条件的会员，请调整筛选条件'"
        />
      </div>

      <!-- 全部会员 -->
      <div v-else>
        <el-alert type="error" :closable="false" show-icon class="mb-12">
          <template #title>
            全量发券会向所有正常会员发放，操作不可撤销。请勾选下方确认框后再继续。
          </template>
        </el-alert>
        <el-checkbox v-model="confirmAll">我确认向全部正常会员发放该券</el-checkbox>
      </div>

      <el-divider />
      <el-form label-width="110px">
        <el-form-item label="每人发放">
          <el-input-number v-model="form.qtyPerMember" :min="1" :max="99" />
          <span class="text-muted" style="margin-left: 8px">张</span>
        </el-form-item>
        <el-form-item label="发送通知">
          <el-switch v-model="form.notify" />
          <span class="text-muted" style="margin-left: 8px">通过订阅消息通知会员（需会员已授权）</span>
        </el-form-item>
        <el-form-item label="发放原因" required>
          <el-input v-model="form.reason" placeholder="如：沉睡会员唤醒活动" style="width: 320px" />
        </el-form-item>
      </el-form>
    </div>

    <!-- 步骤 3 -->
    <div class="page-card" v-show="step === 2">
      <div class="page-title"><span>③ 确认发放</span></div>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="优惠券">{{ selectedTpl?.tplName }}</el-descriptions-item>
        <el-descriptions-item label="面额/折扣">
          {{ selectedTpl ? faceText(selectedTpl) : '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="发放模式">{{ targetText }}</el-descriptions-item>
        <el-descriptions-item label="目标人数">
          <span v-if="form.targetType === 'MEMBER'">{{ form.memberIds.length }} 人</span>
          <span v-else-if="form.targetType === 'CONDITION'">{{ estimate ?? '未预估' }} 人</span>
          <span v-else>全部正常会员</span>
        </el-descriptions-item>
        <el-descriptions-item label="每人数量">{{ form.qtyPerMember }} 张</el-descriptions-item>
        <el-descriptions-item label="发放原因">{{ form.reason || '-' }}</el-descriptions-item>
      </el-descriptions>

      <el-alert type="warning" :closable="false" show-icon class="mt-12"
        title="发放后不可撤销；如需回收请在会员券列表逐张作废" />

      <div v-if="grantResult" class="result mt-12">
        <el-result
          :icon="grantResult.failCount === 0 ? 'success' : 'warning'"
          :title="grantResult.tip"
          :sub-title="`任务号 ${grantResult.taskNo} · 目标 ${grantResult.targetCount} 人 · 成功 ${grantResult.successCount} · 失败 ${grantResult.failCount} · 共发放 ${grantResult.totalIssued} 张`"
        />
        <el-table v-if="grantResult.errors.length" :data="grantResult.errors" size="small" border>
          <el-table-column prop="memberId" label="会员ID" width="100" />
          <el-table-column prop="errorMsg" label="失败原因" min-width="200" />
        </el-table>
      </div>
    </div>

    <!-- 操作栏 -->
    <div class="page-card action-bar">
      <el-button @click="step > 0 && step--" :disabled="step === 0">上一步</el-button>
      <el-button v-if="step < 2" type="primary" @click="nextStep">下一步</el-button>
      <el-button v-else type="danger" :loading="granting" @click="doGrant" :disabled="grantResult !== null">
        确认发放
      </el-button>
      <el-button @click="reset">重置</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { couponApi, levelApi, systemApi, type CouponTplRow, type GrantRequest, type GrantResult } from '@/api/admin'
import { memberApi } from '@/api'

const step = ref(0)
const tpls = ref<CouponTplRow[]>([])
const selectedTplId = ref<number | undefined>()
const levels = ref<Array<{ id: number; levelName: string }>>([])
const tags = ref<Array<{ id: number; tagName: string }>>([])
const memberInput = ref('')
const resolving = ref(false)
const resolveErrors = ref<string[]>([])
const estimating = ref(false)
const estimate = ref<number | null>(null)
const granting = ref(false)
const grantResult = ref<GrantResult | null>(null)
const confirmAll = ref(false)
const createdRange = ref<[string, string] | null>(null)
const consumeRange = ref<[string, string] | null>(null)

const form = reactive<GrantRequest>({
  tplId: 0,
  targetType: 'MEMBER',
  memberIds: [],
  condition: {
    levelIds: [],
    tagIds: [],
    status: 1,
    limit: 50000
  },
  qtyPerMember: 1,
  notify: false,
  reason: ''
})

const selectedTpl = computed(() => tpls.value.find((t) => t.id === selectedTplId.value))
const targetText = computed(
  () => ({ MEMBER: '指定会员', CONDITION: '按条件筛选', ALL: '全部会员' })[form.targetType]
)

function fmt(v?: number | string | null) {
  if (v === undefined || v === null) return '0.00'
  return Number(v).toFixed(2)
}

function faceText(row: any) {
  if (row.couponType === 2) {
    const rate = Number(row.faceValue) > 1 ? Number(row.faceValue) / 100 : Number(row.faceValue)
    return (rate * 10).toFixed(1).replace(/\.0$/, '') + '折'
  }
  return '¥' + fmt(row.faceValue)
}

function typeText(type: number) {
  return ({ 1: '满减券', 2: '折扣券', 3: '代金券', 4: '兑换券' } as Record<number, string>)[type] || '优惠券'
}

function onTplSelect(row: CouponTplRow | null) {
  if (row) {
    selectedTplId.value = row.id
  }
}

function onTargetChange() {
  estimate.value = null
  resolveErrors.value = []
}

/** 解析粘贴的会员列表（手机号或会员号） */
async function resolveMembers() {
  const tokens = memberInput.value
    .split(/[\s,，;；\n]+/)
    .map((s) => s.trim())
    .filter(Boolean)
  if (!tokens.length) {
    ElMessage.warning('请输入手机号或会员号')
    return
  }
  resolving.value = true
  const ids: number[] = []
  const errors: string[] = []
  try {
    for (const token of tokens) {
      try {
        const found = await memberApi.page({ keyword: token, pageNum: 1, pageSize: 5 })
        const exact = found.list.find((m) => m.phone === token || m.memberNo === token) || found.list[0]
        if (exact) {
          ids.push(exact.id)
        } else {
          errors.push(token)
        }
      } catch {
        errors.push(token)
      }
    }
    form.memberIds = Array.from(new Set(ids))
    resolveErrors.value = errors
    ElMessage.success(`已解析 ${form.memberIds.length} 位会员${errors.length ? `，${errors.length} 条未识别` : ''}`)
  } finally {
    resolving.value = false
  }
}

async function doEstimate() {
  estimating.value = true
  try {
    const c = form.condition!
    c.createdStart = createdRange.value?.[0]
    c.createdEnd = createdRange.value?.[1]
    c.lastConsumeStart = consumeRange.value?.[0]
    c.lastConsumeEnd = consumeRange.value?.[1]
    const res = await couponApi.estimate({ ...form, tplId: selectedTplId.value || 0 })
    estimate.value = res.count
  } finally {
    estimating.value = false
  }
}

function nextStep() {
  if (step.value === 0) {
    if (!selectedTplId.value) {
      ElMessage.warning('请先选择优惠券')
      return
    }
    form.tplId = selectedTplId.value
  }
  if (step.value === 1) {
    if (!form.reason?.trim()) {
      ElMessage.warning('请填写发放原因')
      return
    }
    if (form.targetType === 'MEMBER' && !form.memberIds.length) {
      ElMessage.warning('请先解析要发券的会员')
      return
    }
    if (form.targetType === 'CONDITION' && (estimate.value === null || estimate.value === 0)) {
      ElMessage.warning('请先预估目标人数，且人数需大于 0')
      return
    }
    if (form.targetType === 'ALL' && !confirmAll.value) {
      ElMessage.warning('全量发券需勾选确认框')
      return
    }
  }
  step.value++
}

async function doGrant() {
  granting.value = true
  try {
    const payload: GrantRequest = {
      ...form,
      confirmAll: form.targetType === 'ALL' ? confirmAll.value : undefined
    }
    if (form.targetType === 'CONDITION') {
      const c = form.condition!
      c.createdStart = createdRange.value?.[0]
      c.createdEnd = createdRange.value?.[1]
      c.lastConsumeStart = consumeRange.value?.[0]
      c.lastConsumeEnd = consumeRange.value?.[1]
    }
    grantResult.value = await couponApi.grant(payload)
    ElMessage.success(grantResult.value.tip)
  } catch (e) {
    // 错误提示已统一处理
  } finally {
    granting.value = false
  }
}

function reset() {
  step.value = 0
  selectedTplId.value = undefined
  memberInput.value = ''
  form.memberIds = []
  form.reason = ''
  form.qtyPerMember = 1
  form.notify = false
  form.targetType = 'MEMBER'
  form.condition = { levelIds: [], tagIds: [], status: 1, limit: 50000 }
  createdRange.value = null
  consumeRange.value = null
  estimate.value = null
  grantResult.value = null
  confirmAll.value = false
}

onMounted(async () => {
  const [t, l, tg] = await Promise.all([
    couponApi.list({ onlyEnabled: true }).catch(() => []),
    levelApi.list().catch(() => []),
    memberApi.tags().catch(() => [])
  ])
  tpls.value = t
  levels.value = l.map((x) => ({ id: x.id, levelName: x.levelName }))
  tags.value = tg
})
</script>

<style scoped>
.tpl-name {
  font-weight: 600;
}

.action-bar {
  display: flex;
  justify-content: center;
  gap: 12px;
}

.result {
  margin-top: 12px;
}
</style>
