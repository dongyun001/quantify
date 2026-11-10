<template>
  <div class="page">
    <div class="page-title">申诉审批</div>
    <div class="page-desc">
      待处理申诉在本班范围内可见。受理时必须填写更正内容，系统会生成变更记录并让原记录失效；
      驳回则维持原记录。钉钉发送失败可以手动重发。
    </div>

    <div class="toolbar">
      <div class="spacer" />
      <el-button @click="load"><el-icon><Refresh /></el-icon>&nbsp;刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column label="学生" width="140">
        <template #default="{ row }">
          <div>{{ row.studentName }}</div>
          <div class="sub">{{ row.studentNo }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="className" label="班级" width="110" />
      <el-table-column label="原记录" min-width="220">
        <template #default="{ row }">
          <el-tag size="small" :type="catTag(row.scoreCategory)">{{ row.scoreCategoryText }}</el-tag>
          <el-tag size="small" type="info" class="ml4">{{ row.scoreDimensionText || '未分类' }}</el-tag>
          <span class="pts" :class="ptsClass(row.scoreSignedPoints)">
            {{ row.scoreSignedPoints > 0 ? '+' : '' }}{{ row.scoreSignedPoints }}
          </span>
          <span>{{ row.scoreReason || '—' }}</span>
          <span v-if="row.scoreOccurredOn" class="sub"> · {{ row.scoreOccurredOn }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="appealTypeText" label="申诉类型" width="100" />
      <el-table-column prop="appealReason" label="申诉理由" min-width="200" show-overflow-tooltip />
      <el-table-column label="申诉人" width="140">
        <template #default="{ row }">
          <div>{{ row.createdByName || '—' }}</div>
          <div class="sub">{{ row.createdAt }}</div>
        </template>
      </el-table-column>
      <el-table-column label="钉钉" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="notifyTag(row.notifyStatus)">{{ row.notifyStatusText }}</el-tag>
          <el-tooltip v-if="row.notifyStatus === 2" :content="row.notifyError || '发送失败'" placement="top">
            <el-icon class="warn-icon"><Warning /></el-icon>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="showDetail(row)">详情</el-button>
          <el-button link type="success" @click="openAccept(row)">受理</el-button>
          <el-button link type="danger" @click="openReject(row)">驳回</el-button>
          <el-button v-if="row.notifyStatus === 2" link type="warning" @click="retryNotify(row)">
            重发
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="size"
      :current-page="page"
      @current-change="(p: number) => { page = p; load() }"
    />

    <el-empty v-if="!loading && !rows.length" description="没有待处理的申诉" />

    <!-- 最近处理：受理/驳回后钉钉结果通知可能失败，这里可以刷新并重发 -->
    <el-card v-if="recent.length" shadow="never" class="recent">
      <template #header><b>最近处理</b></template>
      <el-table :data="recent" size="small" border>
        <el-table-column prop="studentName" label="学生" width="100" />
        <el-table-column prop="className" label="班级" width="110" />
        <el-table-column label="处理结果" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="处理意见" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.reviewRemark || '—' }}</template>
        </el-table-column>
        <el-table-column label="钉钉" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="notifyTag(row.notifyStatus)">{{ row.notifyStatusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button link type="primary" @click="refreshOne(row.id)">刷新</el-button>
            <el-button v-if="row.notifyStatus === 2" link type="warning" @click="retryNotify(row)">
              重发
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 受理弹窗 -->
    <el-dialog v-model="acceptVisible" title="受理申诉" width="560px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="受理会立即生效"
        description="系统在同一事务内新建一条更正记录，并把原记录置为「已失效」；变更链完整保留。"
      />
      <el-form :model="acceptForm" label-width="100px" style="margin-top: 16px">
        <el-form-item label="更正方式" required>
          <el-radio-group v-model="acceptForm.changeType">
            <el-radio-button :value="1">修改</el-radio-button>
            <el-radio-button :value="2">删除这条记录</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <template v-if="acceptForm.changeType === 1">
          <el-form-item label="类别" required>
            <el-select v-model="acceptForm.dimension" placeholder="请选择德/智/体/美/劳" style="width: 100%">
              <el-option label="德" :value="1" />
              <el-option label="智" :value="2" />
              <el-option label="体" :value="3" />
              <el-option label="美" :value="4" />
              <el-option label="劳" :value="5" />
            </el-select>
          </el-form-item>
          <el-form-item label="方向" required>
            <el-radio-group v-model="acceptForm.category">
              <el-radio-button :value="1">加分</el-radio-button>
              <el-radio-button :value="2">扣分</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="分值" required>
            <el-input-number v-model="acceptForm.points" :min="0.5" :step="0.5" :precision="1" />
          </el-form-item>
          <el-form-item label="事由">
            <el-input v-model="acceptForm.reason" maxlength="500" placeholder="留空沿用原事由" />
          </el-form-item>
          <el-form-item label="发生日期">
            <el-date-picker
              v-model="acceptForm.occurredOn"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="留空沿用原日期"
              style="width: 100%"
            />
          </el-form-item>
        </template>
        <el-form-item label="处理意见">
          <el-input
            v-model="acceptForm.reviewRemark"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="例如：核实后更正为加分 3 分"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="acceptVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitAccept">确认受理</el-button>
      </template>
    </el-dialog>

    <!-- 驳回弹窗 -->
    <el-dialog v-model="rejectVisible" title="驳回申诉" width="520px">
      <el-form label-width="90px">
        <el-form-item label="驳回理由" required>
          <el-input
            v-model="rejectRemark"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="例如：签到表显示确实未到，维持原记录"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submitReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" title="申诉详情" width="640px">
      <template v-if="current">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="学生">
            {{ current.studentName }}（{{ current.studentNo }}）
          </el-descriptions-item>
          <el-descriptions-item label="班级">{{ current.className }}</el-descriptions-item>
          <el-descriptions-item label="原记录">
            {{ current.scoreCategoryText }} · {{ current.scoreDimensionText || '未分类' }}
            {{ current.scoreSignedPoints > 0 ? '+' : '' }}{{ current.scoreSignedPoints }} 分
          </el-descriptions-item>
          <el-descriptions-item label="发生日期">{{ current.scoreOccurredOn || '—' }}</el-descriptions-item>
          <el-descriptions-item label="原事由" :span="2">{{ current.scoreReason || '—' }}</el-descriptions-item>
          <el-descriptions-item label="申诉类型">{{ current.appealTypeText }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="statusTag(current.status)">{{ current.statusText }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="申诉理由" :span="2">{{ current.appealReason }}</el-descriptions-item>
          <el-descriptions-item label="佐证链接" :span="2">
            <a v-if="current.evidenceUrl" :href="current.evidenceUrl" target="_blank" rel="noreferrer">
              {{ current.evidenceUrl }}
            </a>
            <span v-else class="sub">—</span>
          </el-descriptions-item>
          <el-descriptions-item label="申诉人">{{ current.createdByName }}</el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ current.createdAt }}</el-descriptions-item>
        </el-descriptions>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Warning } from '@element-plus/icons-vue'
import { appealApi } from '@/api'
import type { AppealRow } from '@/api/types'

const rows = ref<AppealRow[]>([])
const recent = ref<AppealRow[]>([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const size = ref(20)

const detailVisible = ref(false)
const current = ref<AppealRow | null>(null)

const acceptVisible = ref(false)
const acceptId = ref(0)
const submitting = ref(false)
const acceptForm = reactive({
  changeType: 1,
  dimension: 0,
  category: 1,
  points: 1,
  reason: '',
  occurredOn: null as string | null,
  reviewRemark: '',
})

const rejectVisible = ref(false)
const rejectId = ref(0)
const rejectRemark = ref('')

function statusTag(s: number) {
  return ({ 0: 'warning', 1: 'success', 2: 'danger', 3: 'info' } as const)[s as 0] ?? 'info'
}

function notifyTag(s: number) {
  return ({ 0: 'info', 1: 'success', 2: 'danger' } as const)[s as 0] ?? 'info'
}

function catTag(c: number | null) {
  return ({ 1: 'success', 2: 'danger' } as const)[c as 1 | 2] ?? 'info'
}

function ptsClass(v: number) {
  if (v > 0) return 'pts-plus'
  if (v < 0) return 'pts-minus'
  return 'pts-zero'
}

function showDetail(row: AppealRow) {
  current.value = row
  detailVisible.value = true
}

function openAccept(row: AppealRow) {
  acceptId.value = row.id
  acceptForm.changeType = 1
  acceptForm.dimension = row.scoreDimension ?? 0
  acceptForm.category = row.scoreCategory ?? 1
  acceptForm.points = Math.abs(row.scoreSignedPoints) || 1
  acceptForm.reason = row.scoreReason ?? ''
  acceptForm.occurredOn = row.scoreOccurredOn
  acceptForm.reviewRemark = ''
  acceptVisible.value = true
}

function openReject(row: AppealRow) {
  rejectId.value = row.id
  rejectRemark.value = ''
  rejectVisible.value = true
}

async function submitAccept() {
  if (acceptForm.changeType === 1) {
    if (acceptForm.dimension < 1 || acceptForm.dimension > 5) {
      ElMessage.warning('请选择类别（德/智/体/美/劳）')
      return
    }
    if (!acceptForm.category) {
      ElMessage.warning('请选择更正后的方向')
      return
    }
    if (!acceptForm.points || acceptForm.points <= 0) {
      ElMessage.warning('更正后的分值必须为正数')
      return
    }
  }
  submitting.value = true
  try {
    const r = await appealApi.accept(acceptId.value, {
      reviewRemark: acceptForm.reviewRemark || undefined,
      changeType: acceptForm.changeType,
      dimension: acceptForm.changeType === 1 ? acceptForm.dimension : undefined,
      category: acceptForm.changeType === 1 ? acceptForm.category : undefined,
      points: acceptForm.changeType === 1 ? acceptForm.points : undefined,
      reason: acceptForm.changeType === 1 ? acceptForm.reason : undefined,
      occurredOn: acceptForm.changeType === 1 ? acceptForm.occurredOn : undefined,
    })
    ElMessage.success('已受理，原记录已失效，新记录已生效')
    acceptVisible.value = false
    await afterProcess(acceptId.value)
    await load()
  } finally {
    submitting.value = false
  }
}

async function submitReject() {
  if (!rejectRemark.value.trim()) {
    ElMessage.warning('请填写驳回理由')
    return
  }
  submitting.value = true
  try {
    await appealApi.reject(rejectId.value, rejectRemark.value.trim())
    ElMessage.success('已驳回，原记录未变动')
    rejectVisible.value = false
    await afterProcess(rejectId.value)
    await load()
  } finally {
    submitting.value = false
  }
}

/** 处理完的申诉从待处理列表消失，放进「最近处理」方便查看钉钉结果 / 重发 */
async function afterProcess(id: number) {
  try {
    const d = await appealApi.detail(id)
    const idx = recent.value.findIndex((x) => x.id === id)
    if (idx >= 0) recent.value[idx] = d
    else recent.value.unshift(d)
  } catch {
    // 详情读取失败不影响主流程
  }
}

async function refreshOne(id: number) {
  const d = await appealApi.detail(id)
  const idx = recent.value.findIndex((x) => x.id === id)
  if (idx >= 0) recent.value[idx] = d
}

async function retryNotify(row: AppealRow) {
  const r = await appealApi.notifyRetry(row.id)
  if (r.notifyStatus === 1) {
    ElMessage.success('钉钉消息已重发')
  } else {
    ElMessage.error(r.notifyError || '重发失败')
  }
  await refreshOne(row.id)
}

async function load() {
  loading.value = true
  try {
    const r = await appealApi.pending({ page: page.value, size: size.value })
    rows.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.sub { font-size: 12px; color: #909399; }
.ml4 { margin-left: 4px; }
.pts { margin: 0 6px; font-weight: 600; }
.pager { margin-top: 12px; justify-content: flex-end; }
.recent { margin-top: 20px; }
.warn-icon { color: #e6a23c; margin-left: 4px; vertical-align: middle; }
</style>
