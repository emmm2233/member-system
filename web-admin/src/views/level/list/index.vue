<template>
  <div class="page-container">
    <div class="page-card">
      <div class="page-title">
        <span>会员等级与权益</span>
        <el-space>
          <el-button type="primary" plain @click="openEdit()" v-if="userStore.has('level:add')">
            <el-icon><Plus /></el-icon>新增等级
          </el-button>
          <el-button @click="recalculate" :loading="recalculating" v-if="userStore.has('level:evaluate')">
            <el-icon><Refresh /></el-icon>全量重算等级
          </el-button>
        </el-space>
      </div>

      <el-alert type="info" :closable="false" show-icon class="mb-12">
        <template #title>
          升级条件由「等级规则」决定（默认按累计成长值）；折扣率用于消费自动打折，积分倍率用于积分累计，改动仅影响之后的消费。
        </template>
      </el-alert>

      <el-table :data="rows" v-loading="loading" stripe border size="small">
        <el-table-column label="等级" width="150">
          <template #default="{ row }">
            <div class="level-name">
              {{ row.levelName }}
              <el-tag v-if="row.isDefault === 1" size="small" type="warning" effect="plain">默认</el-tag>
            </div>
            <div class="text-muted">序号 {{ row.levelValue }} · {{ row.levelCode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="会员数" width="90" align="right">
          <template #default="{ row }">{{ row.memberCount }}</template>
        </el-table-column>
        <el-table-column label="升级条件" min-width="200">
          <template #default="{ row }">
            <div v-if="row.rule.configured">
              <el-tag
                v-for="(it, i) in row.rule.items"
                :key="i"
                size="small"
                effect="plain"
                style="margin-right: 4px"
              >
                {{ it.fieldLabel }} {{ it.op }} {{ it.value }}
              </el-tag>
              <span class="text-muted">（{{ row.rule.logicType === 'OR' ? '满足任一' : '全部满足' }}）</span>
            </div>
            <span v-else class="text-muted">未配置（不会被自动升级命中）</span>
          </template>
        </el-table-column>
        <el-table-column label="消费折扣" width="100">
          <template #default="{ row }">{{ row.discountText }}</template>
        </el-table-column>
        <el-table-column label="积分倍率" width="90" align="right">
          <template #default="{ row }">{{ row.pointsRate }}x</template>
        </el-table-column>
        <el-table-column label="升级礼" width="90" align="right">
          <template #default="{ row }">{{ row.upgradeGiftPoints || 0 }} 分</template>
        </el-table-column>
        <el-table-column label="有效期" width="90">
          <template #default="{ row }">{{ row.validMonths > 0 ? row.validMonths + ' 月' : '永久' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openRule(row)">配置规则</el-button>
            <el-button link type="primary" size="small" @click="openEdit(row)" v-if="userStore.has('level:edit')">
              编辑
            </el-button>
            <el-button link type="warning" size="small" @click="openSimulate(row)">模拟测算</el-button>
            <el-button link type="danger" size="small" @click="remove(row)" v-if="userStore.has('level:edit')">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 等级编辑 -->
    <el-dialog v-model="editVisible" :title="form.id ? '编辑等级' : '新增等级'" width="560px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="等级名称" required>
          <el-input v-model="form.levelName" placeholder="如：金卡会员" maxlength="20" />
        </el-form-item>
        <el-form-item label="等级序号">
          <el-input-number v-model="form.levelValue" :min="1" :max="99" />
          <span class="text-muted" style="margin-left: 8px">数值越大等级越高</span>
        </el-form-item>
        <el-form-item label="消费折扣率">
          <el-input-number v-model="form.discountRate" :min="0.5" :max="1" :step="0.01" :precision="2" />
          <span class="text-muted" style="margin-left: 8px">1 = 不打折；0.95 = 95 折</span>
        </el-form-item>
        <el-form-item label="积分倍率">
          <el-input-number v-model="form.pointsRate" :min="0.5" :max="10" :step="0.1" :precision="2" />
        </el-form-item>
        <el-form-item label="升级礼积分">
          <el-input-number v-model="form.upgradeGiftPoints" :min="0" :step="50" />
        </el-form-item>
        <el-form-item label="等级有效期">
          <el-input-number v-model="form.validMonths" :min="0" :max="120" />
          <span class="text-muted" style="margin-left: 8px">月；0 表示永久</span>
        </el-form-item>
        <el-form-item label="设为默认等级">
          <el-switch v-model="form.isDefault" :active-value="1" :inactive-value="0" />
          <span class="text-muted" style="margin-left: 8px">新会员注册时的初始等级（全局唯一）</span>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="权益说明">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 规则配置 -->
    <el-dialog v-model="ruleVisible" :title="`配置升级规则：${currentLevel?.levelName || ''}`" width="620px">
      <el-form label-width="100px">
        <el-form-item label="满足逻辑">
          <el-radio-group v-model="ruleForm.logicType">
            <el-radio-button value="AND">全部满足</el-radio-button>
            <el-radio-button value="OR">满足任一</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="条件项" required>
          <div class="rule-list">
            <div v-for="(item, idx) in ruleForm.items" :key="idx" class="rule-row">
              <el-select v-model="item.field" placeholder="字段" style="width: 190px">
                <el-option v-for="f in fields" :key="f.field" :label="f.label" :value="f.field" />
              </el-select>
              <el-select v-model="item.op" placeholder="操作符" style="width: 90px">
                <el-option v-for="op in ops" :key="op" :label="op" :value="op" />
              </el-select>
              <el-input-number v-model="item.value" :min="0" :step="100" style="width: 150px" />
              <el-button link type="danger" @click="ruleForm.items.splice(idx, 1)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <el-button link type="primary" @click="addRuleItem">
              <el-icon><Plus /></el-icon>添加条件
            </el-button>
          </div>
        </el-form-item>

        <el-form-item label="等级有效期">
          <el-input-number v-model="ruleForm.validMonths" :min="0" :max="120" />
          <span class="text-muted" style="margin-left: 8px">月；0 表示永久</span>
        </el-form-item>
      </el-form>

      <el-alert type="warning" :closable="false" show-icon
        title="保存前建议先用「模拟测算」验证规则，避免误升级；规则只影响之后的评定，不会立即改动存量会员" />

      <template #footer>
        <el-button @click="ruleVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRule">保存规则</el-button>
      </template>
    </el-dialog>

    <!-- 模拟测算 -->
    <el-dialog v-model="simVisible" title="等级模拟测算" width="480px">
      <el-form label-width="90px">
        <el-form-item label="会员 ID">
          <el-input v-model="simMemberId" placeholder="输入会员 ID" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="simulating" @click="doSimulate">测算</el-button>
          <el-button @click="doEvaluate" :loading="simulating">直接评定该会员</el-button>
        </el-form-item>
      </el-form>
      <el-descriptions v-if="simResult" :column="1" border size="small">
        <el-descriptions-item label="当前等级">{{ simResult.currentLevel || '-' }}</el-descriptions-item>
        <el-descriptions-item label="成长值">{{ simResult.growth }}</el-descriptions-item>
        <el-descriptions-item label="目标等级">
          <el-tag :type="simResult.willChange ? 'success' : 'info'">{{ simResult.targetLevel || '-' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="结论">
          {{ simResult.willChange ? '规则判定后将变更等级' : '等级保持不变' }}
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { levelApi, type LevelRow, type LevelSave } from '@/api/admin'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const loading = ref(false)
const submitting = ref(false)
const recalculating = ref(false)
const rows = ref<LevelRow[]>([])
const fields = ref<Array<{ field: string; label: string; ops: string[] }>>([])
const ops = ['>=', '>', '<=', '<', '==']

const editVisible = ref(false)
const ruleVisible = ref(false)
const simVisible = ref(false)
const currentLevel = ref<LevelRow | null>(null)

const emptyForm = (): LevelSave => ({
  levelName: '',
  discountRate: 1,
  pointsRate: 1,
  upgradeGiftPoints: 0,
  isDefault: 0,
  validMonths: 0,
  status: 1,
  sortNo: 0
})
const form = reactive<LevelSave>(emptyForm())

const ruleForm = reactive({
  logicType: 'AND',
  items: [] as Array<{ field: string; op: string; value: number }>,
  validMonths: 0
})

const simMemberId = ref('')
const simulating = ref(false)
const simResult = ref<Record<string, unknown> | null>(null)

async function load() {
  loading.value = true
  try {
    rows.value = await levelApi.list()
  } finally {
    loading.value = false
  }
}

function openEdit(row?: any) {
  Object.assign(form, emptyForm())
  if (row) {
    Object.assign(form, {
      id: row.id,
      levelName: row.levelName,
      levelCode: row.levelCode,
      levelValue: row.levelValue,
      discountRate: Number(row.discountRate),
      pointsRate: Number(row.pointsRate),
      cardDiscountRate: row.cardDiscountRate ? Number(row.cardDiscountRate) : undefined,
      upgradeGiftPoints: row.upgradeGiftPoints,
      isDefault: row.isDefault,
      validMonths: row.validMonths,
      status: row.status,
      sortNo: row.sortNo,
      remark: row.remark
    })
  }
  editVisible.value = true
}

async function submit() {
  if (!form.levelName.trim()) {
    ElMessage.warning('请填写等级名称')
    return
  }
  submitting.value = true
  try {
    if (form.id) {
      await levelApi.update(form.id, { ...form })
    } else {
      await levelApi.create({ ...form })
    }
    ElMessage.success('已保存')
    editVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确认删除等级「${row.levelName}」？`, '删除确认', { type: 'warning' })
  await levelApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

function addRuleItem() {
  ruleForm.items.push({ field: 'growth', op: '>=', value: 1000 })
}

async function openRule(row: any) {
  currentLevel.value = row
  ruleForm.logicType = row.rule.logicType || 'AND'
  ruleForm.validMonths = row.validMonths
  ruleForm.items = row.rule.configured
    ? row.rule.items.map((it: Record<string, unknown>) => ({
        field: String(it.field),
        op: String(it.op),
        value: Number(it.value)
      }))
    : [{ field: 'growth', op: '>=', value: 1000 }]
  ruleVisible.value = true
}

async function submitRule() {
  if (!currentLevel.value) return
  if (!ruleForm.items.length) {
    ElMessage.warning('请至少添加一个条件')
    return
  }
  submitting.value = true
  try {
    await levelApi.saveRule(currentLevel.value.id, {
      logicType: ruleForm.logicType,
      items: ruleForm.items,
      validMonths: ruleForm.validMonths
    })
    ElMessage.success('规则已保存')
    ruleVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

function openSimulate(_row: any) {
  simResult.value = null
  simVisible.value = true
}

async function doSimulate() {
  if (!simMemberId.value) {
    ElMessage.warning('请输入会员 ID')
    return
  }
  simulating.value = true
  try {
    simResult.value = await levelApi.simulate(Number(simMemberId.value))
  } finally {
    simulating.value = false
  }
}

async function doEvaluate() {
  if (!simMemberId.value) {
    ElMessage.warning('请输入会员 ID')
    return
  }
  simulating.value = true
  try {
    const res = await levelApi.evaluate(Number(simMemberId.value))
    ElMessage.success(`${res.action}：${res.levelName || '无变化'}`)
    await doSimulate()
    load()
  } finally {
    simulating.value = false
  }
}

async function recalculate() {
  await ElMessageBox.confirm(
    '全量重算会按当前规则重新评定所有正常会员的等级，可能引起批量升降级，确认执行？',
    '全量重算确认',
    { type: 'warning' }
  )
  recalculating.value = true
  try {
    const res = await levelApi.recalculate(200)
    ElMessage.success(`扫描 ${res.scanned} 人，升级 ${res.upgraded}，降级 ${res.downgraded}`)
    load()
  } finally {
    recalculating.value = false
  }
}

onMounted(async () => {
  fields.value = await levelApi.ruleFields().catch(() => [])
  load()
})
</script>

<style scoped>
.level-name {
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
}

.rule-list {
  width: 100%;
}

.rule-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
</style>
