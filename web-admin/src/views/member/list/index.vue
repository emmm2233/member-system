<template>
  <div class="page-container">
    <!-- 查询条件 -->
    <div class="page-card">
      <el-form :model="query" inline size="default" @submit.prevent>
        <el-form-item label="关键词">
          <el-input
            v-model="query.keyword"
            placeholder="会员号 / 手机号 / 姓名 / 昵称"
            clearable
            style="width: 220px"
            @keyup.enter="onSearch"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="正常" :value="1" />
            <el-option label="冻结" :value="2" />
            <el-option label="注销" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源">
          <el-select v-model="query.source" placeholder="全部" clearable style="width: 130px">
            <el-option label="门店建档" :value="1" />
            <el-option label="小程序注册" :value="2" />
            <el-option label="批量导入" :value="3" />
            <el-option label="推荐注册" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="余额区间">
          <el-input-number v-model="query.minBalance" :min="0" :controls="false" placeholder="最小" style="width: 90px" />
          <span style="margin: 0 4px">-</span>
          <el-input-number v-model="query.maxBalance" :min="0" :controls="false" placeholder="最大" style="width: 90px" />
        </el-form-item>
        <el-form-item label="入会时间">
          <el-date-picker
            v-model="createdRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 240px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onSearch">
            <el-icon><Search /></el-icon>查询
          </el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 列表 -->
    <div class="page-card">
      <div class="page-title">
        <span>
          会员列表
          <el-tag size="small" type="info" effect="plain" style="margin-left: 8px">共 {{ total }} 人</el-tag>
        </span>
        <el-space>
          <el-button type="primary" @click="openCreate" v-if="userStore.has('member:add')">
            <el-icon><Plus /></el-icon>新建会员
          </el-button>
        </el-space>
      </div>

      <el-table
        v-loading="loading"
        :data="rows"
        stripe
        border
        size="small"
        highlight-current-row
        @row-dblclick="(row: MemberVO) => openDetail(row)"
      >
        <el-table-column prop="memberNo" label="会员号" width="160" />
        <el-table-column label="会员" min-width="160">
          <template #default="{ row }">
            <div class="member-cell">
              <el-avatar :size="26" :src="row.avatar" style="background: var(--brand-primary)">
                {{ (row.nickname || row.realName || 'M').charAt(0) }}
              </el-avatar>
              <div>
                <div>{{ row.nickname || row.realName || '-' }}</div>
                <div class="text-muted">{{ row.phoneMasked || '未绑定手机' }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="等级" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.levelName" size="small" effect="light">{{ row.levelName }}</el-tag>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="标签" min-width="120">
          <template #default="{ row }">
            <el-tag
              v-for="tag in (row.tags || []).slice(0, 2)"
              :key="tag.id"
              size="small"
              :color="tag.tagColor || undefined"
              effect="plain"
              style="margin-right: 4px"
            >
              {{ tag.tagName }}
            </el-tag>
            <span v-if="(row.tags || []).length > 2" class="text-muted">+{{ row.tags.length - 2 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="余额" width="130" align="right">
          <template #default="{ row }">
            <div class="amount">¥{{ fmt(row.balance) }}</div>
            <div class="text-muted">赠 ¥{{ fmt(row.giftBalance) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="积分" width="90" align="right">
          <template #default="{ row }">
            <span class="amount">{{ row.points ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="累计消费" width="120" align="right">
          <template #default="{ row }">
            <div class="amount">¥{{ fmt(row.totalConsume) }}</div>
            <div class="text-muted">{{ row.totalConsumeCount ?? 0 }} 笔</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 1 ? 'success' : row.status === 2 ? 'warning' : 'info'"
            >
              {{ row.statusText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="入会时间" width="160">
          <template #default="{ row }">
            <span class="text-muted">{{ row.createdAt || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button
              link
              type="primary"
              size="small"
              @click="openRecharge(row)"
              v-if="userStore.has('account:recharge')"
            >
              充值
            </el-button>
            <el-button
              link
              type="primary"
              size="small"
              @click="openAdjust(row)"
              v-if="userStore.has('account:adjust')"
            >
              调整
            </el-button>
            <el-dropdown @command="(cmd: string) => onRowCommand(cmd, row as MemberVO)" style="margin-left: 8px">
              <el-button link type="primary" size="small">
                更多<el-icon><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="freeze" v-if="row.status === 1 && userStore.has('member:edit')">
                    冻结
                  </el-dropdown-item>
                  <el-dropdown-item command="unfreeze" v-if="row.status === 2 && userStore.has('member:edit')">
                    解冻
                  </el-dropdown-item>
                  <el-dropdown-item command="cancel" v-if="userStore.has('member:remove')">注销</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="mt-12"
        background
        layout="total, sizes, prev, pager, next, jumper"
        :total="total"
        :current-page="query.pageNum"
        :page-size="query.pageSize"
        :page-sizes="[10, 20, 50, 100]"
        @current-change="(p: number) => { query.pageNum = p; load() }"
        @size-change="(s: number) => { query.pageSize = s; query.pageNum = 1; load() }"
      />
    </div>

    <!-- 新建/编辑会员 -->
    <el-dialog v-model="createVisible" :title="form.id ? '编辑会员' : '新建会员'" width="560px">
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="会员唯一识别，可留空" maxlength="11" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="form.realName" placeholder="真实姓名" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" placeholder="展示昵称" />
        </el-form-item>
        <el-form-item label="性别">
          <el-radio-group v-model="form.gender">
            <el-radio :value="0">未知</el-radio>
            <el-radio :value="1">男</el-radio>
            <el-radio :value="2">女</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="生日">
          <el-date-picker v-model="form.birthday" type="date" value-format="YYYY-MM-DD" placeholder="生日礼依据" />
        </el-form-item>
        <el-form-item label="来源">
          <el-select v-model="form.source" style="width: 100%">
            <el-option label="门店建档" :value="1" />
            <el-option label="小程序注册" :value="2" />
            <el-option label="批量导入" :value="3" />
            <el-option label="推荐注册" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="推荐人手机">
          <el-input v-model="form.referrerPhone" placeholder="选填，用于绑定推荐关系" maxlength="11" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 充值 -->
    <el-dialog v-model="rechargeVisible" title="会员充值" width="560px">
      <el-alert type="info" :closable="false" show-icon class="mb-12">
        <template #title>
          {{ current?.nickname || current?.realName }} · 当前余额 ¥{{ fmt(current?.balance) }}（赠送 ¥{{
            fmt(current?.giftBalance)
          }}） · 积分 {{ current?.points ?? 0 }}
        </template>
      </el-alert>
      <div class="plan-grid">
        <div
          v-for="plan in plans"
          :key="plan.id"
          class="plan-card"
          :class="{ active: rechargeForm.planId === plan.id }"
          @click="selectPlan(plan)"
        >
          <div class="plan-amount">¥{{ fmt(plan.rechargeAmount) }}</div>
          <div class="plan-gift">
            送 ¥{{ fmt(plan.giftAmount) }}
            <span v-if="plan.giftPoints"> + {{ plan.giftPoints }} 积分</span>
          </div>
          <div class="plan-coupon" v-if="plan.giftCouponTplIds">含赠券</div>
        </div>
      </div>
      <el-form label-width="90px" class="mt-12">
        <el-form-item label="充值金额">
          <el-input-number v-model="rechargeForm.rechargeAmount" :min="0.01" :precision="2" :step="100" />
        </el-form-item>
        <el-form-item label="赠送金额">
          <el-input v-model="rechargeForm.giftAmount" disabled />
        </el-form-item>
        <el-form-item label="支付方式">
          <el-radio-group v-model="rechargeForm.payMethod">
            <el-radio :value="2">现金</el-radio>
            <el-radio :value="3">线下扫码</el-radio>
            <el-radio :value="9">其他</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="外部流水号" v-if="rechargeForm.payMethod === 3">
          <el-input v-model="rechargeForm.outTradeNo" placeholder="线下扫码凭证号（选填）" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="rechargeForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rechargeVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRecharge">确认充值</el-button>
      </template>
    </el-dialog>

    <!-- 充值成功结果 -->
    <el-dialog v-model="resultVisible" title="充值成功" width="460px">
      <el-result icon="success" :title="`充值 ¥${fmt(rechargeResult?.rechargeAmount)} 成功`">
        <template #sub-title>
          <div class="result-lines">
            <div>本单号：{{ rechargeResult?.orderNo }}</div>
            <div>赠送金额：¥{{ fmt(rechargeResult?.giftAmount) }}</div>
            <div v-if="rechargeResult?.giftPoints">赠送积分：{{ rechargeResult.giftPoints }}</div>
            <div>充值后余额：¥{{ fmt(rechargeResult?.balanceAfter) }}（赠送 ¥{{ fmt(rechargeResult?.giftBalanceAfter) }}）</div>
            <div>充值后积分：{{ rechargeResult?.pointsAfter ?? 0 }}</div>
            <div v-if="rechargeResult?.grantedCoupons?.length">
              赠券：{{ rechargeResult.grantedCoupons.map((c) => c.tplName).join('、') }}
            </div>
          </div>
        </template>
        <el-button type="primary" @click="resultVisible = false">完成</el-button>
      </el-result>
    </el-dialog>

    <!-- 资产调整 -->
    <el-dialog v-model="adjustVisible" title="资产调整" width="520px">
      <el-alert type="warning" :closable="false" show-icon class="mb-12" title="资产调整会写入不可变流水与操作日志，原因必填" />
      <el-form :model="adjustForm" label-width="100px">
        <el-form-item label="本金变动">
          <el-input-number v-model="adjustForm.balanceDelta" :precision="2" :step="10" />
        </el-form-item>
        <el-form-item label="赠送变动">
          <el-input-number v-model="adjustForm.giftDelta" :precision="2" :step="10" />
        </el-form-item>
        <el-form-item label="积分变动">
          <el-input-number v-model="adjustForm.pointsDelta" :step="100" />
        </el-form-item>
        <el-form-item label="成长值变动">
          <el-input-number v-model="adjustForm.growthDelta" :step="100" />
        </el-form-item>
        <el-form-item label="调整原因" required>
          <el-input v-model="adjustForm.reason" type="textarea" :rows="2" placeholder="例如：客诉补偿 500 积分" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitAdjust">确认调整</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { accountApi, memberApi, rechargeApi, type MemberVO, type RechargePlan } from '@/api'
import { useUserStore } from '@/store/user'

defineOptions({ name: 'MemberList' })

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const submitting = ref(false)
const rows = ref<MemberVO[]>([])
const total = ref(0)
const createdRange = ref<[string, string] | null>(null)
const plans = ref<RechargePlan[]>([])

const query = reactive({
  keyword: '',
  status: undefined as number | undefined,
  source: undefined as number | undefined,
  minBalance: undefined as number | undefined,
  maxBalance: undefined as number | undefined,
  pageNum: 1,
  pageSize: 20
})

/* ---------- 列表 ---------- */

async function load() {
  loading.value = true
  try {
    const params: Record<string, unknown> = {
      pageNum: query.pageNum,
      pageSize: query.pageSize
    }
    if (query.keyword) params.keyword = query.keyword
    if (query.status) params.status = query.status
    if (query.source) params.source = query.source
    if (query.minBalance !== undefined) params.minBalance = query.minBalance
    if (query.maxBalance !== undefined) params.maxBalance = query.maxBalance
    if (createdRange.value) {
      params.createdStart = createdRange.value[0]
      params.createdEnd = createdRange.value[1]
    }
    const res = await memberApi.page(params)
    rows.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function onSearch() {
  query.pageNum = 1
  load()
}

function onReset() {
  query.keyword = ''
  query.status = undefined
  query.source = undefined
  query.minBalance = undefined
  query.maxBalance = undefined
  createdRange.value = null
  query.pageNum = 1
  load()
}

function fmt(v?: number | string | null) {
  if (v === undefined || v === null) return '0.00'
  return Number(v).toFixed(2)
}

function openDetail(row: any) {
  router.push(`/member/detail/${row.id}`)
}

/* ---------- 新建/编辑 ---------- */

const createVisible = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({
  id: undefined as number | undefined,
  phone: '',
  realName: '',
  nickname: '',
  gender: 0,
  birthday: '',
  source: 1,
  referrerPhone: '',
  remark: ''
})

const formRules: FormRules = {
  phone: [{ pattern: /^$|^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }]
}

function openCreate() {
  Object.assign(form, {
    id: undefined,
    phone: '',
    realName: '',
    nickname: '',
    gender: 0,
    birthday: '',
    source: 1,
    referrerPhone: '',
    remark: ''
  })
  createVisible.value = true
}

async function submitCreate() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await memberApi.create({ ...form })
    ElMessage.success('会员建档成功')
    createVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

/* ---------- 行操作 ---------- */

async function onRowCommand(cmd: string, row: any) {
  if (cmd === 'freeze' || cmd === 'unfreeze') {
    const status = cmd === 'freeze' ? 2 : 1
    await memberApi.changeStatus(row.id, status)
    ElMessage.success(cmd === 'freeze' ? '已冻结' : '已解冻')
    load()
  } else if (cmd === 'cancel') {
    await ElMessageBox.confirm(
      `确认注销会员 ${row.memberNo}？注销要求余额与积分均为 0。`,
      '注销确认',
      { type: 'warning' }
    )
    await memberApi.changeStatus(row.id, 3, '后台注销')
    ElMessage.success('已注销')
    load()
  }
}

/* ---------- 充值 ---------- */

const rechargeVisible = ref(false)
const resultVisible = ref(false)
const current = ref<MemberVO | null>(null)
const rechargeResult = ref<{
  orderNo: string
  rechargeAmount: number
  giftAmount: number
  giftPoints: number
  pointsAfter: number
  balanceAfter: number
  giftBalanceAfter: number
  grantedCoupons: Array<{ couponNo: string; tplName: string }>
} | null>(null)

const rechargeForm = reactive({
  planId: undefined as number | undefined,
  rechargeAmount: 0,
  giftAmount: 0,
  giftPoints: 0,
  payMethod: 2,
  outTradeNo: '',
  remark: ''
})

async function openRecharge(row: any) {
  current.value = row
  if (!plans.value.length) {
    plans.value = await rechargeApi.plans()
  }
  rechargeForm.planId = undefined
  rechargeForm.rechargeAmount = plans.value[0]?.rechargeAmount ?? 100
  rechargeForm.giftAmount = 0
  rechargeForm.giftPoints = 0
  rechargeForm.payMethod = 2
  rechargeForm.outTradeNo = ''
  rechargeForm.remark = ''
  if (plans.value[0]) selectPlan(plans.value[0])
  rechargeVisible.value = true
}

function selectPlan(plan: RechargePlan) {
  rechargeForm.planId = plan.id
  rechargeForm.rechargeAmount = plan.rechargeAmount
  rechargeForm.giftAmount = plan.giftAmount
  rechargeForm.giftPoints = plan.giftPoints
}

async function submitRecharge() {
  if (!current.value) return
  if (!rechargeForm.rechargeAmount || rechargeForm.rechargeAmount <= 0) {
    ElMessage.warning('请输入充值金额')
    return
  }
  submitting.value = true
  try {
    const res = await rechargeApi.recharge({
      memberId: current.value.id,
      planId: rechargeForm.planId,
      rechargeAmount: rechargeForm.rechargeAmount,
      payMethod: rechargeForm.payMethod,
      outTradeNo: rechargeForm.outTradeNo || undefined,
      remark: rechargeForm.remark || undefined
    })
    rechargeResult.value = res
    rechargeVisible.value = false
    resultVisible.value = true
    load()
  } finally {
    submitting.value = false
  }
}

/* ---------- 资产调整 ---------- */

const adjustVisible = ref(false)
const adjustForm = reactive({
  memberId: 0,
  balanceDelta: 0,
  giftDelta: 0,
  pointsDelta: 0,
  growthDelta: 0,
  reason: ''
})

function openAdjust(row: any) {
  adjustForm.memberId = row.id
  adjustForm.balanceDelta = 0
  adjustForm.giftDelta = 0
  adjustForm.pointsDelta = 0
  adjustForm.growthDelta = 0
  adjustForm.reason = ''
  adjustVisible.value = true
}

async function submitAdjust() {
  if (!adjustForm.reason.trim()) {
    ElMessage.warning('资产调整必须填写原因')
    return
  }
  const payload: Record<string, unknown> = { memberId: adjustForm.memberId, reason: adjustForm.reason }
  if (adjustForm.balanceDelta) payload.balanceDelta = adjustForm.balanceDelta
  if (adjustForm.giftDelta) payload.giftDelta = adjustForm.giftDelta
  if (adjustForm.pointsDelta) payload.pointsDelta = adjustForm.pointsDelta
  if (adjustForm.growthDelta) payload.growthDelta = adjustForm.growthDelta
  submitting.value = true
  try {
    await accountApi.adjust(payload)
    ElMessage.success('调整成功')
    adjustVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.member-cell {
  display: flex;
  align-items: center;
  gap: 8px;
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
  transition: all 0.2s;
  text-align: center;
}

.plan-card:hover {
  border-color: var(--el-color-primary-light-5);
}

.plan-card.active {
  border-color: var(--brand-primary);
  background: var(--brand-primary-light);
}

.plan-amount {
  font-size: 20px;
  font-weight: 700;
  color: #1f2d3d;
}

.plan-gift {
  font-size: 12px;
  color: var(--brand-warning);
  margin-top: 4px;
}

.plan-coupon {
  font-size: 12px;
  color: #909399;
}

.result-lines {
  text-align: left;
  display: inline-block;
  line-height: 1.9;
  font-size: 13px;
}
</style>
