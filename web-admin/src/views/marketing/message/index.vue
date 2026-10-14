<template>
  <div class="page-container">
    <div class="page-card">
      <div class="page-title">
        <span>消息渠道就绪状态</span>
        <el-button link type="primary" size="small" @click="loadChannels">刷新</el-button>
      </div>
      <div class="channel-row">
        <div v-for="c in channels" :key="c.code" class="channel-box" :class="c.available ? 'ok' : 'off'">
          <div class="channel-name">
            <el-icon><component :is="c.available ? 'CircleCheckFilled' : 'WarningFilled'" /></el-icon>
            {{ c.name }}
            <el-tag size="small" :type="c.available ? 'success' : 'info'" effect="plain">
              {{ c.available ? '可用' : '不可用' }}
            </el-tag>
          </div>
          <div class="channel-code">{{ c.code }}</div>
          <div v-if="!c.available" class="channel-reason">{{ c.reason }}</div>
          <div v-else class="channel-reason muted">配置完整，可直接下发</div>
        </div>
      </div>
      <div class="toolbar">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="降级策略"
          description="微信订阅消息不可用（未配 AppID/Secret、用户未授权、额度用尽、接口报错）时，消息会自动降级为站内信：会员在小程序「消息中心」仍能看到完整内容，记录里会留下降级原因。订阅消息是「一次性授权一条」，额度按模板维度记账，用尽后需用户再次授权。"
        />
      </div>
    </div>

    <div class="page-card">
      <el-tabs v-model="tab">
        <el-tab-pane label="消息模板" name="tpl">
          <el-table :data="tpls" v-loading="loading" stripe border size="small">
            <el-table-column label="模板" min-width="220">
              <template #default="{ row }">
                <div>{{ row.tplName }}</div>
                <div class="text-muted">{{ row.tplCode }} · {{ row.channelText }}</div>
              </template>
            </el-table-column>
            <el-table-column label="标题" width="150">
              <template #default="{ row }">{{ row.title || '-' }}</template>
            </el-table-column>
            <el-table-column label="占位符" min-width="180">
              <template #default="{ row }">
                <el-tag v-for="p in row.placeholders" :key="p" size="small" effect="plain" class="ph">{{
                  '{' + p + '}'
                }}</el-tag>
                <span v-if="!row.placeholders.length" class="text-muted">无</span>
              </template>
            </el-table-column>
            <el-table-column label="微信模板ID" width="150">
              <template #default="{ row }">
                <span :class="row.wxTemplateId ? '' : 'text-muted'">{{ row.wxTemplateId || '未配置' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="80" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
                  {{ row.status === 1 ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openEdit(row)" v-if="canEdit">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="发送记录" name="records">
          <el-form inline @submit.prevent>
            <el-form-item label="会员ID">
              <el-input v-model="query.memberId" placeholder="精确匹配" clearable style="width: 130px" />
            </el-form-item>
            <el-form-item label="模板">
              <el-select v-model="query.tplCode" clearable placeholder="全部" style="width: 190px">
                <el-option v-for="t in tpls" :key="t.tplCode" :label="t.tplName" :value="t.tplCode" />
              </el-select>
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="query.status" clearable placeholder="全部" style="width: 130px">
                <el-option label="待发送" :value="1" />
                <el-option label="已发送" :value="2" />
                <el-option label="发送失败" :value="3" />
                <el-option label="已读" :value="4" />
              </el-select>
            </el-form-item>
            <el-form-item label="关键词">
              <el-input v-model="query.keyword" placeholder="内容 / 业务单号" clearable style="width: 170px" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadRecords"><el-icon><Search /></el-icon>查询</el-button>
              <el-button plain @click="runRetry" :loading="busy" v-if="canResend">重试失败批次</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="records" v-loading="loading" stripe border size="small">
            <el-table-column label="消息" min-width="280">
              <template #default="{ row }">
                <div class="msg-title">{{ row.title || '-' }}</div>
                <div class="msg-body">{{ row.content }}</div>
              </template>
            </el-table-column>
            <el-table-column label="模板" width="150">
              <template #default="{ row }">
                <div>{{ row.tplCode }}</div>
                <div class="text-muted">会员 {{ row.memberId }}</div>
              </template>
            </el-table-column>
            <el-table-column label="渠道" width="110" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.channel === 'INNER' ? 'info' : 'success'" effect="plain">
                  {{ row.channelText }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="statusType(row.status)">{{ row.statusText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="重试" width="70" align="center">
              <template #default="{ row }">{{ row.retryCount }}</template>
            </el-table-column>
            <el-table-column label="备注 / 失败原因" min-width="200">
              <template #default="{ row }">
                <span :class="row.status === 3 ? 'danger-text' : 'text-muted'">{{ row.errorMsg || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="160">
              <template #default="{ row }">{{ fmtTime(row.sentAt || row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="resend(row)" v-if="canResend">重发</el-button>
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
      </el-tabs>
    </div>

    <el-drawer v-model="editVisible" title="编辑消息模板" size="620px">
      <el-form :model="form" label-width="130px">
        <el-form-item label="模板编码">
          <el-input :model-value="form.tplCode" disabled />
        </el-form-item>
        <el-form-item label="模板名称">
          <el-input v-model="form.tplName" maxlength="100" />
        </el-form-item>
        <el-form-item label="消息标题">
          <el-input v-model="form.title" maxlength="100" />
        </el-form-item>
        <el-form-item label="消息内容">
          <el-input v-model="form.contentTpl" type="textarea" :rows="4" maxlength="1000" show-word-limit />
          <div class="text-muted hint">支持占位符：{{ form.placeholders.map((p) => '{' + p + '}').join('、') || '无' }}</div>
        </el-form-item>
        <el-form-item label="跳转页面">
          <el-input v-model="form.pagePath" placeholder="如 pages/coupon/index" />
        </el-form-item>
        <el-divider content-position="left">微信订阅消息</el-divider>
        <el-form-item label="微信模板ID">
          <el-input v-model="form.wxTemplateId" placeholder="公众平台申请后填入，留空则永远降级站内信" />
        </el-form-item>
        <el-form-item label="字段映射">
          <el-input
            v-model="form.wxDataMap"
            type="textarea"
            :rows="3"
            placeholder='{"thing1":"{memberName}","amount2":"{amount}"}'
          />
          <div class="text-muted hint">
            键是微信模板字段（thing1/amount2/date3…），值是占位符。微信对字段长度有限制（thing 约 20 字），
            超长会被整条拒绝，系统会按字段前缀自动截断。
          </div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" maxlength="255" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="submit" :loading="busy">保存</el-button>
          <el-button @click="editVisible = false">取消</el-button>
        </el-form-item>
      </el-form>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { messageApi, type MessageChannelRow, type MessageTplRow } from '@/api/admin'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const canEdit = computed(() => userStore.has('message:tpl:edit'))
const canResend = computed(() => userStore.has('message:record:resend'))

const tab = ref('tpl')
const loading = ref(false)
const busy = ref(false)

const channels = ref<MessageChannelRow[]>([])
const tpls = ref<MessageTplRow[]>([])
const records = ref<Array<Record<string, any>>>([])
const total = ref(0)
const query = reactive({
  memberId: '',
  tplCode: undefined as string | undefined,
  status: undefined as number | undefined,
  keyword: '',
  pageNum: 1,
  pageSize: 20
})

const editVisible = ref(false)
const form = reactive({
  id: 0,
  tplCode: '',
  tplName: '',
  title: '',
  contentTpl: '',
  pagePath: '',
  wxTemplateId: '',
  wxDataMap: '',
  status: 1,
  remark: '',
  placeholders: [] as string[]
})

function fmtTime(v?: string): string {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '-'
}
function statusType(s: number): 'success' | 'warning' | 'danger' | 'info' {
  return s === 2 ? 'success' : s === 4 ? 'info' : s === 3 ? 'danger' : 'warning'
}

async function loadChannels() {
  channels.value = await messageApi.channels()
}

async function loadTpls() {
  tpls.value = await messageApi.tpls()
}

async function loadRecords() {
  loading.value = true
  try {
    const res = await messageApi.records({
      memberId: query.memberId ? Number(query.memberId) : undefined,
      tplCode: query.tplCode,
      status: query.status,
      keyword: query.keyword || undefined,
      pageNum: query.pageNum,
      pageSize: query.pageSize
    })
    records.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}
function onPage(p: number) {
  query.pageNum = p
  loadRecords()
}

function openEdit(row: any) {
  const t = row as MessageTplRow
  Object.assign(form, {
    id: t.id,
    tplCode: t.tplCode,
    tplName: t.tplName,
    title: t.title || '',
    contentTpl: t.contentTpl,
    pagePath: t.pagePath || '',
    wxTemplateId: t.wxTemplateId || '',
    wxDataMap: t.wxDataMap || '',
    status: t.status,
    remark: t.remark || '',
    placeholders: t.placeholders || []
  })
  editVisible.value = true
}

async function submit() {
  if (!form.contentTpl.trim()) {
    ElMessage.warning('消息内容不能为空')
    return
  }
  busy.value = true
  try {
    await messageApi.updateTpl(form.id, {
      tplName: form.tplName,
      title: form.title,
      contentTpl: form.contentTpl,
      pagePath: form.pagePath,
      wxTemplateId: form.wxTemplateId,
      wxDataMap: form.wxDataMap,
      status: form.status,
      remark: form.remark
    })
    ElMessage.success('模板已保存')
    editVisible.value = false
    await loadTpls()
  } finally {
    busy.value = false
  }
}

async function resend(row: Record<string, any>) {
  await messageApi.resend(row.id)
  ElMessage.success('已重发（微信不可用时自动降级站内信）')
  await loadRecords()
}

async function runRetry() {
  busy.value = true
  try {
    const done = await messageApi.retry()
    ElMessage.success(`重试完成，处理 ${done} 条`)
    await loadRecords()
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadChannels(), loadTpls(), loadRecords()])
})
</script>

<style scoped>
.channel-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.channel-box {
  flex: 1 1 240px;
  padding: 12px 16px;
  border-radius: 6px;
  border: 1px solid #ebeef5;
}
.channel-box.ok {
  background: #f0f9eb;
  border-color: #e1f3d8;
}
.channel-box.off {
  background: #fdf6ec;
  border-color: #faecd8;
}
.channel-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}
.channel-code {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
.channel-reason {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: #e6a23c;
}
.channel-reason.muted {
  color: #909399;
}
.toolbar {
  margin-top: 14px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.msg-title {
  font-weight: 600;
}
.msg-body {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}
.ph {
  margin-right: 4px;
}
.danger-text {
  color: #f56c6c;
}
.hint {
  width: 100%;
  font-size: 12px;
  line-height: 1.5;
}
</style>
