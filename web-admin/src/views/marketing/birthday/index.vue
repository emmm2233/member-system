<template>
  <div class="page-container">
    <div class="page-card">
      <div class="stat-row">
        <div class="stat-box">
          <div class="stat-label">本年度已发放</div>
          <div class="stat-value success">{{ num(stats.issuedThisYear) }}</div>
        </div>
        <div class="stat-box">
          <div class="stat-label">部分发放（待补）</div>
          <div class="stat-value warning">{{ num(stats.partial) }}</div>
        </div>
        <div class="stat-box">
          <div class="stat-label">发放失败</div>
          <div class="stat-value danger">{{ num(stats.failed) }}</div>
        </div>
        <div class="stat-box">
          <div class="stat-label">今天生日会员</div>
          <div class="stat-value">{{ num(stats.todayCount) }}</div>
        </div>
        <div class="stat-box">
          <div class="stat-label">生日券模板</div>
          <div class="stat-value small">{{ stats.couponTplName || '未配置' }}</div>
        </div>
      </div>
      <div class="toolbar">
        <el-button type="primary" plain @click="runIssue" :loading="busy" v-if="canIssue">
          <el-icon><Present /></el-icon>立即发放（等同定时任务）
        </el-button>
        <el-button plain @click="runRepair" :loading="busy" v-if="canIssue">修复失败记录</el-button>
        <el-button plain @click="loadPreview()">预览今日名单</el-button>
      </div>
      <div class="toolbar">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="一年只送一次"
          description="发放记录以 (会员, 生日年份) 唯一索引兜底：定时任务重跑、多实例并发、手工补发都不会重复发放。若发放中途失败，记录会留在「部分发放」，修复任务只补缺失项（资产层幂等键保证已成功的项不会加第二次）。"
        />
      </div>
    </div>

    <div class="page-card">
      <el-tabs v-model="tab">
        <el-tab-pane label="发放记录" name="logs">
          <el-form inline @submit.prevent>
            <el-form-item label="会员ID">
              <el-input v-model="query.memberId" placeholder="精确匹配" clearable style="width: 140px" />
            </el-form-item>
            <el-form-item label="年份">
              <el-input v-model="query.year" placeholder="如 2026" clearable style="width: 120px" />
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="query.status" clearable placeholder="全部" style="width: 140px">
                <el-option label="已发放" :value="1" />
                <el-option label="部分发放" :value="2" />
                <el-option label="发放失败" :value="3" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadLogs"><el-icon><Search /></el-icon>查询</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="logs" v-loading="loading" stripe border size="small">
            <el-table-column label="会员" min-width="170">
              <template #default="{ row }">
                <div>{{ row.member?.name || '-' }}</div>
                <div class="text-muted">{{ row.member?.memberNo }} · {{ row.member?.phone }}</div>
              </template>
            </el-table-column>
            <el-table-column label="生日" width="120" align="center">
              <template #default="{ row }">{{ row.giftDate }}（{{ row.giftYear }}）</template>
            </el-table-column>
            <el-table-column label="礼包内容" min-width="220">
              <template #default="{ row }">
                <el-tag v-if="num(row.points) > 0" size="small" type="success" effect="plain">
                  {{ row.points }} 积分
                </el-tag>
                <el-tag v-if="num(row.balance) > 0" size="small" type="warning" effect="plain">
                  {{ Number(row.balance).toFixed(2) }} 元赠送余额
                </el-tag>
                <el-tag v-if="num(row.growth) > 0" size="small" effect="plain">{{ row.growth }} 成长值</el-tag>
                <el-tag v-if="row.couponId" size="small" type="danger" effect="plain">券 #{{ row.couponId }}</el-tag>
                <span v-if="!row.points && !row.balance && !row.growth && !row.couponId" class="text-muted">无礼品项</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 1 ? 'success' : row.status === 2 ? 'warning' : 'danger'">
                  {{ row.statusText }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="消息" width="80" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.messageSent === 1 ? 'success' : 'info'" effect="plain">
                  {{ row.messageSent === 1 ? '已发' : '未发' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="失败原因" min-width="180">
              <template #default="{ row }">
                <span :class="row.failReason ? 'danger-text' : 'text-muted'">{{ row.failReason || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="发放时间" width="160">
              <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="issueAgain(row)" v-if="canIssue">补发</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            class="pager"
            layout="total, prev, pager, next"
            :total="total"
            :page-size="query.pageSize"
            :current-page="query.pageNum"
            @current-change="onPage"
          />
        </el-tab-pane>

        <el-tab-pane label="名单预览" name="preview">
          <el-form inline @submit.prevent>
            <el-form-item label="生日（月-日）">
              <el-input v-model="previewMd" placeholder="MM-dd，留空为今天" clearable style="width: 160px" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadPreview()">预览</el-button>
            </el-form-item>
          </el-form>
          <el-alert
            type="success"
            :closable="false"
            show-icon
            :title="`${preview.monthDay || ''} 生日会员共 ${num(preview.count)} 人（下列为前 20 条）`"
          />
          <el-table :data="previewList" v-loading="loading" stripe border size="small" style="margin-top: 12px">
            <el-table-column label="会员" min-width="170">
              <template #default="{ row }">
                <div>{{ row.name }}</div>
                <div class="text-muted">{{ row.memberNo }} · {{ row.phone }}</div>
              </template>
            </el-table-column>
            <el-table-column label="生日" width="140" align="center">
              <template #default="{ row }">{{ row.birthday }}</template>
            </el-table-column>
            <el-table-column label="今年是否已发放" width="150" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.giftedThisYear ? 'success' : 'info'">
                  {{ row.giftedThisYear ? '已发放' : '未发放' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="礼品配置" name="config">
          <div v-if="!canConfig" class="text-muted">无 birthday:config 权限，无法查看与修改配置。</div>
          <el-form v-else label-width="180px" style="max-width: 760px">
            <el-form-item v-for="item in configItems" :key="item.key" :label="item.name">
              <el-switch
                v-if="item.type === 'BOOLEAN'"
                v-model="cfgForm[item.key]"
                active-value="true"
                inactive-value="false"
              />
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
              <el-button type="primary" @click="saveConfig" :loading="busy">保存配置</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { birthdayApi, type ReferralConfigItem } from '@/api/admin'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const canIssue = computed(() => userStore.has('birthday:issue'))
const canConfig = computed(() => userStore.has('birthday:config'))

const tab = ref('logs')
const loading = ref(false)
const busy = ref(false)

const stats = ref<Record<string, unknown>>({})
const logs = ref<Array<Record<string, any>>>([])
const total = ref(0)
const query = reactive({
  memberId: '',
  year: '' as string,
  status: undefined as number | undefined,
  pageNum: 1,
  pageSize: 20
})

const previewMd = ref('')
const preview = ref<Record<string, unknown>>({})
const previewList = ref<Array<Record<string, any>>>([])

const configItems = ref<ReferralConfigItem[]>([])
const cfgForm = reactive<Record<string, string>>({})

function num(v: unknown): number {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}
function fmtTime(v?: string): string {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '-'
}

async function loadStats() {
  const res = await birthdayApi.stats()
  stats.value = res.data as Record<string, unknown>
}

async function loadLogs() {
  loading.value = true
  try {
    const res = await birthdayApi.logs({
      memberId: query.memberId ? Number(query.memberId) : undefined,
      year: query.year ? Number(query.year) : undefined,
      status: query.status,
      pageNum: query.pageNum,
      pageSize: query.pageSize
    })
    logs.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}
function onPage(p: number) {
  query.pageNum = p
  loadLogs()
}

async function loadPreview(md?: string) {
  loading.value = true
  try {
    const res = await birthdayApi.preview(md === undefined ? previewMd.value || undefined : md)
    preview.value = res
    previewList.value = (res.sample as Array<Record<string, any>>) || []
    if (md === undefined) tab.value = 'preview'
  } finally {
    loading.value = false
  }
}

async function loadConfig() {
  if (!canConfig.value) return
  const items = await birthdayApi.config()
  configItems.value = items
  items.forEach((i) => {
    cfgForm[i.key] = i.value === null || i.value === undefined ? '' : String(i.value)
  })
}

async function saveConfig() {
  busy.value = true
  try {
    await birthdayApi.saveConfig({ ...cfgForm })
    ElMessage.success('生日礼配置已保存')
    await Promise.all([loadStats(), loadConfig()])
  } finally {
    busy.value = false
  }
}

async function runIssue() {
  busy.value = true
  try {
    const issued = await birthdayApi.issueBatch()
    ElMessage.success(`发放完成，本次发放 ${issued} 人`)
    await Promise.all([loadStats(), loadLogs()])
  } finally {
    busy.value = false
  }
}

async function runRepair() {
  busy.value = true
  try {
    const done = await birthdayApi.repair()
    ElMessage.success(`修复完成，处理 ${done} 条`)
    await Promise.all([loadStats(), loadLogs()])
  } finally {
    busy.value = false
  }
}

async function issueAgain(row: Record<string, any>) {
  await ElMessageBox.confirm(
    `将为会员 ${row.member?.name || row.memberId} 补发 ${row.giftYear} 年度生日礼（只补缺失项，不会重复入账）。`,
    '补发生日礼',
    { type: 'warning' }
  )
  const res = await birthdayApi.issue(row.memberId, row.giftYear, true)
  ElMessage.success(res.status === 1 ? '补发成功' : `补发完成，状态：${res.status}`)
  await Promise.all([loadStats(), loadLogs()])
}

onMounted(async () => {
  await Promise.all([loadStats(), loadLogs(), loadConfig()])
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
.stat-value.small {
  font-size: 15px;
  line-height: 1.6;
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
.danger-text {
  color: #f56c6c;
}
</style>
