<template>
  <div class="page-container">
    <div class="page-card">
      <div class="page-title">
        <span>系统参数</span>
        <el-button @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
      </div>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="mb-12"
        title="业务阈值一律在此配置，代码中不硬编码。修改后立即生效（缓存 10 分钟内自动刷新）。"
      />

      <el-tabs v-model="activeGroup">
        <el-tab-pane
          v-for="(items, group) in grouped"
          :key="group"
          :label="groupLabel(String(group))"
          :name="String(group)"
        >
          <el-table :data="items" size="small" border>
            <el-table-column prop="configName" label="参数名称" width="220" />
            <el-table-column prop="configKey" label="键" width="260" />
            <el-table-column label="值" min-width="220">
              <template #default="{ row }">
                <el-input
                  v-model="row.configValue"
                  size="small"
                  :disabled="row.editable === 0"
                  @blur="onValueChange(row)"
                />
              </template>
            </el-table-column>
            <el-table-column prop="valueType" label="类型" width="90" />
            <el-table-column prop="remark" label="说明" min-width="200" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/request'

interface ConfigRow {
  id: number
  configKey: string
  configValue: string
  configName: string
  configGroup?: string
  valueType: string
  editable?: number
  remark?: string
}

const rows = ref<ConfigRow[]>([])
const activeGroup = ref('')

const grouped = computed(() => {
  const map: Record<string, ConfigRow[]> = {}
  for (const r of rows.value) {
    const g = r.configGroup || 'other'
    if (!map[g]) map[g] = []
    map[g].push(r)
  }
  return map
})

function groupLabel(g: string) {
  return (
    ({
      account: '账户与余额',
      points: '积分规则',
      level: '等级规则',
      order: '订单与退款',
      marketing: '营销',
      message: '消息通知',
      report: '报表',
      pay: '支付（预留）'
    } as Record<string, string>)[g] || g
  )
}

async function load() {
  rows.value = (await http.get<ConfigRow[]>('/admin/system/configs')) as unknown as ConfigRow[]
  const groups = Object.keys(grouped.value)
  if (groups.length && !activeGroup.value) {
    activeGroup.value = groups[0]
  }
}

let lastValue = ''
function onValueChange(row: any) {
  if (row.configValue === lastValue) return
  lastValue = row.configValue
  http
    .put<void>('/admin/system/configs', { configKey: row.configKey, configValue: row.configValue })
    .then(() => ElMessage.success(`已更新：${row.configName}`))
    .catch(() => load())
}

onMounted(load)
</script>
