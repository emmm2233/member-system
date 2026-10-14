<template>
  <div class="page-container">
    <!-- 步骤条 -->
    <el-steps :active="activeStep" align-center class="mb-12">
      <el-step title="下载模板" description="按模板整理会员数据" />
      <el-step title="上传文件" description="支持 .xlsx / .xls" />
      <el-step title="查看结果" description="成功/失败逐行明细" />
    </el-steps>

    <div class="page-card">
      <div class="page-title">
        <span>① 下载导入模板</span>
        <el-button type="primary" plain @click="downloadTemplate">
          <el-icon><Download /></el-icon>下载模板
        </el-button>
      </div>
      <el-alert type="info" :closable="false" show-icon>
        <template #title>
          模板列：手机号（唯一，可空）、姓名、昵称、性别（男/女/未知）、生日（yyyy-MM-dd）、来源（门店建档/小程序注册/批量导入/推荐注册）、推荐人手机号、标签、备注
        </template>
      </el-alert>
    </div>

    <div class="page-card">
      <div class="page-title"><span>② 上传文件</span></div>
      <el-upload
        drag
        :auto-upload="false"
        :show-file-list="false"
        accept=".xlsx,.xls"
        :on-change="onFileChange"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">将 Excel 拖到此处，或<em>点击选择文件</em></div>
        <template #tip>
          <div class="el-upload__tip">单次最多 20000 行；重复手机号会被跳过并记录失败原因</div>
        </template>
      </el-upload>

      <div v-if="selectedFile" class="file-row mt-12">
        <el-icon><Document /></el-icon>
        <span class="file-name">{{ selectedFile.name }}</span>
        <span class="text-muted">{{ (selectedFile.size / 1024).toFixed(1) }} KB</span>
        <el-button type="primary" :loading="uploading" @click="submitUpload">开始导入</el-button>
      </div>
    </div>

    <!-- 导入结果 -->
    <div class="page-card" v-if="result">
      <div class="page-title">
        <span>
          ③ 导入结果
          <el-tag size="small" :type="statusType(result.status)" style="margin-left: 8px">
            {{ result.statusText }}
          </el-tag>
          <el-tag size="small" type="info" effect="plain" style="margin-left: 6px">
            任务号 {{ result.taskNo }}
          </el-tag>
        </span>
        <el-button v-if="result.errorFileUrl" @click="downloadErrorFile">
          <el-icon><Download /></el-icon>下载错误明细
        </el-button>
      </div>

      <el-row :gutter="12" class="mb-12">
        <el-col :span="8">
          <el-statistic title="总行数" :value="result.totalRows" />
        </el-col>
        <el-col :span="8">
          <el-statistic title="成功" :value="result.successRows" value-style="color: #34a853" />
        </el-col>
        <el-col :span="8">
          <el-statistic title="失败" :value="result.failRows" value-style="color: #ea4335" />
        </el-col>
      </el-row>

      <el-alert
        :type="result.failRows === 0 ? 'success' : 'warning'"
        :closable="false"
        show-icon
        :title="result.tip"
        class="mb-12"
      />

      <el-table v-if="result.errors && result.errors.length" :data="result.errors" size="small" border>
        <el-table-column prop="rowNum" label="原行号" width="90" />
        <el-table-column prop="phone" label="手机号" width="140" />
        <el-table-column prop="realName" label="姓名" width="120" />
        <el-table-column prop="errorMsg" label="失败原因" min-width="260" />
      </el-table>
      <div v-if="result.errors && result.errors.length < result.failRows" class="text-muted mt-8">
        仅显示前 {{ result.errors.length }} 条，完整明细请下载错误文件
      </div>

      <el-space class="mt-12">
        <el-button type="primary" @click="goMemberList">查看会员列表</el-button>
        <el-button @click="reset">继续导入</el-button>
      </el-space>
    </div>

    <!-- 导出 -->
    <div class="page-card">
      <div class="page-title"><span>会员导出</span></div>
      <el-form :model="exportQuery" inline>
        <el-form-item label="状态">
          <el-select v-model="exportQuery.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="正常" :value="1" />
            <el-option label="冻结" :value="2" />
            <el-option label="注销" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="入会时间">
          <el-date-picker
            v-model="exportRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 240px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="exporting" @click="doExport">
            <el-icon><Download /></el-icon>导出 Excel
          </el-button>
        </el-form-item>
      </el-form>
      <el-alert type="warning" :closable="false" show-icon
        title="导出含完整手机号与资产信息，属于敏感操作，请勿外传；超 20000 条请缩小筛选范围" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { UploadFile, UploadFiles } from 'element-plus'
import { tokenStore } from '@/api/request'

const router = useRouter()
const activeStep = ref(0)
const selectedFile = ref<File | null>(null)
const uploading = ref(false)
const exporting = ref(false)
const exportRange = ref<[string, string] | null>(null)

interface ImportResult {
  taskNo: string
  totalRows: number
  successRows: number
  failRows: number
  status: number
  statusText: string
  errorFileUrl?: string
  errors: Array<{ rowNum: number; phone: string; realName: string; errorMsg: string }>
  tip: string
}
const result = ref<ImportResult | null>(null)

const exportQuery = reactive({ status: undefined as number | undefined })

function statusType(status: number) {
  if (status === 3) return 'success'
  if (status === 4) return 'warning'
  return 'danger'
}

/** 带鉴权的文件下载（导出/模板接口需要登录态） */
async function downloadWithAuth(url: string, body?: unknown, fallbackName = 'download.xlsx') {
  const resp = await fetch(url, {
    method: body === undefined ? 'GET' : 'POST',
    headers: {
      Authorization: 'Bearer ' + tokenStore.get(),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' })
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  if (!resp.ok) {
    ElMessage.error('下载失败：HTTP ' + resp.status)
    return
  }
  const blob = await resp.blob()
  const cd = resp.headers.get('Content-Disposition') || ''
  let name = fallbackName
  const match = cd.match(/filename\*=UTF-8''([^;]+)/)
  if (match) {
    name = decodeURIComponent(match[1])
  }
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = name
  a.click()
  URL.revokeObjectURL(a.href)
}

function downloadTemplate() {
  downloadWithAuth('/api/admin/members/import/template', undefined, '会员导入模板.xlsx')
}

function onFileChange(uploadFile: UploadFile, _uploadFiles?: UploadFiles) {
  // el-upload 的 raw 是可选的 UploadRawFile，存进来前收窄成 File，
  // 避免后面 FormData 组装处到处判空
  selectedFile.value = uploadFile.raw ?? null
  activeStep.value = 1
}

async function submitUpload() {
  if (!selectedFile.value) return
  uploading.value = true
  try {
    const form = new FormData()
    form.append('file', selectedFile.value)
    const resp = await fetch('/api/admin/members/import', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + tokenStore.get() },
      body: form
    })
    const json = await resp.json()
    if (json.code !== 0) {
      ElMessage.error(json.msg || '导入失败')
      return
    }
    result.value = json.data
    activeStep.value = 2
    ElMessage.success(json.data.tip)
  } catch (e) {
    ElMessage.error('导入请求失败，请检查网络')
  } finally {
    uploading.value = false
  }
}

function downloadErrorFile() {
  if (!result.value?.errorFileUrl) return
  const a = document.createElement('a')
  a.href = result.value.errorFileUrl
  a.download = '导入错误明细.xlsx'
  a.click()
}

function goMemberList() {
  router.push('/member/list')
}

function reset() {
  selectedFile.value = null
  result.value = null
  activeStep.value = 0
}

async function doExport() {
  exporting.value = true
  try {
    const payload: Record<string, unknown> = {}
    if (exportQuery.status) payload.status = exportQuery.status
    if (exportRange.value) {
      payload.createdStart = exportRange.value[0]
      payload.createdEnd = exportRange.value[1]
    }
    await downloadWithAuth('/api/admin/members/export', payload, '会员列表.xlsx')
    ElMessage.success('导出已开始，请查看浏览器下载')
  } finally {
    exporting.value = false
  }
}
</script>

<style scoped>
.file-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: var(--brand-primary-light);
  border-radius: 8px;
}

.file-name {
  font-weight: 600;
  flex: 1;
}
</style>
