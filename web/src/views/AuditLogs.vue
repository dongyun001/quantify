<template>
  <div class="page">
    <div class="page-title">操作日志</div>
    <div class="page-desc">
      谁在什么时候把什么从多少改成了多少，全部留痕。班级评比最容易起争议，这里就是依据。
    </div>

    <div class="toolbar">
      <el-select v-model="action" placeholder="全部操作" clearable style="width: 170px" @change="load">
        <el-option v-for="a in actions" :key="a.value" :label="a.label" :value="a.value" />
      </el-select>
      <el-button @click="load"><el-icon><Refresh /></el-icon>&nbsp;刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe height="calc(100vh - 240px)">
      <el-table-column prop="createdAt" label="时间" width="170" />
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          <el-tag size="small" :type="actionTag(row.action)">{{ actionText(row.action) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="operatorName" label="操作人" width="110" />
      <el-table-column prop="deptName" label="部门标签" width="110">
        <template #default="{ row }">{{ row.deptName || '—' }}</template>
      </el-table-column>
      <el-table-column label="对象" width="150">
        <template #default="{ row }">
          <span class="mono">{{ targetText(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="干了什么" min-width="360">
        <template #default="{ row }">
          <div class="desc">{{ describeAudit(row).text }}</div>
          <div v-if="describeAudit(row).reason" class="reason">
            原因：{{ describeAudit(row).reason }}
          </div>
          <ul v-if="describeAudit(row).details.length" class="details">
            <li v-for="(d, i) in describeAudit(row).details" :key="i">{{ d }}</li>
          </ul>
          <el-popover
            v-if="row.beforeJson || row.afterJson"
            trigger="click"
            placement="left"
            :width="520"
          >
            <template #reference>
              <span class="raw-link">原始数据</span>
            </template>
            <div class="raw">
              <div v-if="row.beforeJson"><b>改前</b><pre>{{ row.beforeJson }}</pre></div>
              <div v-if="row.afterJson"><b>改后</b><pre>{{ row.afterJson }}</pre></div>
            </div>
          </el-popover>
        </template>
      </el-table-column>
      <el-table-column prop="operatorIp" label="IP" width="130" />
    </el-table>

    <el-pagination
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="size"
      :current-page="page"
      @current-change="(p: number) => { page = p; load() }"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { describeAudit, targetText } from '@/utils/audit'
import request from '@/api/request'
import type { AuditLog } from '@/api/types'

const rows = ref<AuditLog[]>([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const size = ref(20)
const action = ref<string | undefined>()

const actions = [
  { value: 'LOGIN', label: '登录' },
  { value: 'LOGIN_FAIL', label: '登录失败' },
  { value: 'CREATE', label: '录入' },
  { value: 'SUBMIT', label: '提交申请' },
  { value: 'APPROVE', label: '审批通过' },
  { value: 'REJECT', label: '驳回' },
  { value: 'REVOKE', label: '撤回' },
  { value: 'CHANGE', label: '发起变更' },
  { value: 'EXPORT', label: '导出' },
  { value: 'RESET_PASSWORD', label: '重置密码' },
  { value: 'DISABLE', label: '禁用账号' },
]

const ACTION_TEXT: Record<string, string> = {
  LOGIN: '登录', LOGOUT: '登出', LOGIN_FAIL: '登录失败',
  CREATE: '录入', UPDATE: '修改', SUBMIT: '提交申请',
  APPROVE: '审批通过', REJECT: '驳回', REVOKE: '撤回', CHANGE: '发起变更',
  EXPORT: '导出', RESET_PASSWORD: '重置密码',
  ENABLE: '启用账号', DISABLE: '禁用账号', CHANGE_PASSWORD: '修改密码',
}

const actionText = (a: string) => ACTION_TEXT[a] ?? a

function actionTag(a: string) {
  if (a === 'REJECT' || a === 'DISABLE' || a === 'LOGIN_FAIL') return 'danger'
  if (a === 'APPROVE' || a === 'ENABLE') return 'success'
  if (a === 'CHANGE' || a === 'EXPORT') return 'warning'
  return 'info'
}

async function load() {
  loading.value = true
  try {
    const r = await request.get<never, { total: number; list: AuditLog[] }>('/logs', {
      params: { page: page.value, size: size.value, action: action.value },
    })
    rows.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.mono { font-family: Consolas, Monaco, monospace; font-size: 12px; color: #606266; }
.desc { font-size: 13px; color: #1d2129; line-height: 1.6; }
.reason {
  margin-top: 4px;
  padding: 4px 8px;
  font-size: 12px;
  line-height: 1.6;
  color: #7a4a00;
  background: #fff7e8;
  border-left: 2px solid #ff9a2e;
}
.details {
  margin: 4px 0 0;
  padding-left: 16px;
  font-size: 12px;
  color: #4e5969;
  line-height: 1.7;
}
.raw-link {
  margin-top: 4px;
  display: inline-block;
  font-size: 12px;
  color: #165dff;
  cursor: pointer;
}
.raw-link:hover { text-decoration: underline; }
.raw b { font-size: 12px; color: #4e5969; }
.raw pre {
  margin: 4px 0 10px;
  padding: 8px;
  max-height: 220px;
  overflow: auto;
  background: #f7f8fa;
  border-radius: 2px;
  font-size: 11px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
