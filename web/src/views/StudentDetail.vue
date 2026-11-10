<template>
  <div class="page">
    <el-page-header @back="router.back()">
      <template #content>
        <span class="page-title">{{ summary?.name ?? '学生明细' }}</span>
      </template>
    </el-page-header>

    <el-row v-if="summary" :gutter="12" class="cards">
      <el-col :span="4">
        <el-card shadow="never" class="stat">
          <div class="stat-label">学号</div>
          <div class="stat-value small">{{ summary.studentNo }}</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="never" class="stat">
          <div class="stat-label">班级</div>
          <div class="stat-value small">{{ summary.className }}</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="never" class="stat">
          <div class="stat-label">加分</div>
          <div class="stat-value pts-plus">+{{ fmt(summary.addScore) }}</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="never" class="stat">
          <div class="stat-label">扣分</div>
          <div class="stat-value pts-minus">{{ fmt(summary.deductScore) }}</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="never" class="stat highlight">
          <div class="stat-label">总分</div>
          <div class="stat-value total">{{ fmt(summary.totalScore) }}</div>
        </el-card>
      </el-col>
    </el-row>

    <div class="toolbar" style="margin-top: 16px">
      <el-radio-group v-model="category" @change="load">
        <el-radio-button :value="undefined">全部</el-radio-button>
        <el-radio-button :value="1">加分</el-radio-button>
        <el-radio-button :value="2">扣分</el-radio-button>
      </el-radio-group>
      <el-select
        v-model="dimension"
        placeholder="全部类别"
        clearable
        style="width: 140px"
        @change="load"
      >
        <el-option label="未分类" :value="0" />
        <el-option v-for="d in DIMENSIONS" :key="d.value" :label="d.label" :value="d.value" />
      </el-select>
      <el-checkbox v-model="hideInvalid" @change="() => {}">隐藏已失效</el-checkbox>
    </div>

    <el-table v-loading="loading" :data="visibleItems" border stripe>
      <el-table-column label="类别" width="90">
        <template #default="{ row }">
          <el-tag size="small" type="info">{{ row.dimensionText || '未分类' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="分值" width="80" align="right">
        <template #default="{ row }">
          <span :class="ptsClass(signed(row))">{{ signed(row) > 0 ? '+' : '' }}{{ fmt(signed(row)) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="事由" min-width="240" show-overflow-tooltip />
      <el-table-column prop="occurredOn" label="发生日期" width="120" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="deptName" label="来源" width="100">
        <template #default="{ row }">{{ row.deptName || '—' }}</template>
      </el-table-column>
      <el-table-column prop="submitterName" label="提交人" width="100" />
      <el-table-column label="操作" width="190">
        <template #default="{ row }">
          <el-button v-if="row.status === 3" link type="warning" @click="showReject(row)">
            看驳回理由
          </el-button>
          <el-button
            v-if="row.status === 2 && store.isViewer"
            link
            type="primary"
            @click="openAppeal(row)"
          >申诉</el-button>
          <el-button
            v-if="row.status === 2 && store.isAdmin"
            link
            type="warning"
            @click="openAdminChange(row)"
          >管理员修正</el-button>
          <el-button
            v-if="row.status === 2 && store.isAdmin"
            link
            type="primary"
            @click="openAdminChange(row, true)"
          >改类别</el-button>
          <span v-if="row.status !== 2 && row.status !== 3" class="dim">—</span>
        </template>
      </el-table-column>
    </el-table>

    <el-alert
      v-if="store.isViewer"
      class="tip"
      type="info"
      :closable="false"
      show-icon
      title="对已通过的记录有异议可以申诉"
      description="点行尾「申诉」填写理由，一级审核后会按结果处理；受理会生成更正记录，并让原记录失效。"
    />
    <el-alert
      v-else-if="store.isAdmin"
      class="tip"
      type="info"
      :closable="false"
      show-icon
      title="已通过的记录不能直接修改"
      description="需要更正时点行尾「管理员修正」，系统会新建一条变更记录并让原记录失效，变更链完整保留。"
    />
    <el-alert
      v-else
      class="tip"
      type="info"
      :closable="false"
      show-icon
      title="已通过的记录不能直接修改"
      description="需要更正时，请到「录入申请」页对该记录发起变更申请，由审核员审批后生效。"
    />

    <!-- 申诉弹窗 -->
    <el-dialog v-model="appealVisible" title="提交申诉" width="520px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="只能对本班已通过的记录申诉"
        description="同一学生对同一条记录只能有一条待处理申诉；提交后会给审核员发钉钉通知。"
      />
      <el-form :model="appealForm" label-width="90px" style="margin-top: 16px">
        <el-form-item label="申诉类型" required>
          <el-radio-group v-model="appealForm.appealType">
            <el-radio-button :value="1">分数有误</el-radio-button>
            <el-radio-button :value="2">事由有误</el-radio-button>
            <el-radio-button :value="3">重复录入</el-radio-button>
            <el-radio-button :value="4">其他</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="申诉理由" required>
          <el-input
            v-model="appealForm.appealReason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="说明这条记录哪里有问题，例如：这条扣分不是我班学生"
          />
        </el-form-item>
        <el-form-item label="佐证链接">
          <el-input
            v-model="appealForm.evidenceUrl"
            maxlength="500"
            placeholder="选填，图片 / PDF 的 URL（不做文件上传）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="appealVisible = false">取消</el-button>
        <el-button type="primary" :loading="appealSubmitting" @click="submitAppeal">
          提交申诉
        </el-button>
      </template>
    </el-dialog>

    <!-- 管理员修正弹窗 -->
    <el-dialog
      v-model="adminChangeVisible"
      :title="adminChangeDimensionOnly ? '修改五育类别' : '管理员修正已通过记录'"
      width="560px"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="不会直接修改原记录"
        description="系统会新建一条变更记录（直接生效），原记录置为「已失效」，变更链完整保留。"
      />
      <el-form :model="adminChangeForm" label-width="90px" style="margin-top: 16px">
        <template v-if="!adminChangeDimensionOnly">
          <el-form-item label="修正方式" required>
            <el-radio-group v-model="adminChangeForm.changeType">
              <el-radio-button :value="1">修改</el-radio-button>
              <el-radio-button :value="2">删除这条记录</el-radio-button>
            </el-radio-group>
          </el-form-item>
        </template>
        <template v-if="adminChangeForm.changeType === 1">
          <el-form-item label="类别" required>
            <el-select v-model="adminChangeForm.dimension" placeholder="请选择德/智/体/美/劳" style="width: 100%">
              <el-option
                v-for="d in DIMENSIONS"
                :key="d.value"
                :label="d.label"
                :value="d.value"
              />
            </el-select>
          </el-form-item>
          <template v-if="!adminChangeDimensionOnly">
            <el-form-item label="方向">
              <el-radio-group v-model="adminChangeForm.category">
                <el-radio-button :value="1">加分</el-radio-button>
                <el-radio-button :value="2">扣分</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="分值" required>
              <el-input-number
                v-model="adminChangeForm.points"
                :min="0.5"
                :step="0.5"
                :precision="1"
              />
              <span class="hint-inline">填正数，方向由加分/扣分决定</span>
            </el-form-item>
            <el-form-item label="事由">
              <el-input
                v-model="adminChangeForm.reason"
                maxlength="500"
                placeholder="留空沿用原事由"
              />
            </el-form-item>
            <el-form-item label="发生日期">
              <el-date-picker
                v-model="adminChangeForm.occurredOn"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="留空沿用原日期"
                style="width: 100%"
              />
            </el-form-item>
          </template>
        </template>
        <el-form-item label="变更依据" required>
          <el-input
            v-model="adminChangeForm.changeReason"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="说明为什么要改，例如：签到表显示应为 3 分"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adminChangeVisible = false">取消</el-button>
        <el-button type="primary" :loading="adminChangeSubmitting" @click="submitAdminChange">
          确认修正
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { appealApi, scoreApi } from '@/api'
import type { ScoreBoardRow, ScoreItem } from '@/api/types'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const studentId = Number(route.params.id)
const summary = ref<ScoreBoardRow | null>(null)
const items = ref<ScoreItem[]>([])
const loading = ref(false)
const category = ref<number | undefined>()
const dimension = ref<number | undefined>()
const hideInvalid = ref(false)

const DIMENSIONS = [
  { value: 1, label: '德' },
  { value: 2, label: '智' },
  { value: 3, label: '体' },
  { value: 4, label: '美' },
  { value: 5, label: '劳' },
]

const appealVisible = ref(false)
const appealSubmitting = ref(false)
const appealItem = ref<ScoreItem | null>(null)
const appealForm = reactive({
  appealType: 1,
  appealReason: '',
  evidenceUrl: '',
})

const adminChangeVisible = ref(false)
const adminChangeSubmitting = ref(false)
const adminChangeItem = ref<ScoreItem | null>(null)
const adminChangeDimensionOnly = ref(false)
const adminChangeForm = reactive({
  changeType: 1,
  dimension: 0,
  category: 1,
  points: 1,
  reason: '',
  occurredOn: null as string | null,
  changeReason: '',
})

const visibleItems = computed(() =>
  hideInvalid.value ? items.value.filter((i) => i.status !== 5) : items.value,
)

function fmt(v: number | null | undefined) {
  if (v === null || v === undefined) return '0'
  return String(Number(v))
}

/** 分值带符号显示：扣分显示成 -2 而不是 2 */
function signed(row: ScoreItem) {
  return row.category === 2 ? -Number(row.points) : Number(row.points)
}

function ptsClass(v: number) {
  if (v > 0) return 'pts-plus'
  if (v < 0) return 'pts-minus'
  return 'pts-zero'
}

function statusText(s: number) {
  return { 0: '草稿', 1: '待审', 2: '已通过', 3: '已驳回', 4: '已撤销', 5: '已失效' }[s] ?? '未知'
}

function statusTag(s: number) {
  return ({ 0: 'info', 1: 'warning', 2: 'success', 3: 'danger', 4: 'info', 5: 'info' } as const)[s as 0]
    ?? 'info'
}

async function showReject(row: ScoreItem) {
  await ElMessageBox.alert(row.rejectReason || '（未填写理由）', '驳回理由', { type: 'warning' })
}

function openAppeal(row: ScoreItem) {
  appealItem.value = row
  appealForm.appealType = 1
  appealForm.appealReason = ''
  appealForm.evidenceUrl = ''
  appealVisible.value = true
}

async function submitAppeal() {
  if (!appealItem.value) return
  if (!appealForm.appealReason.trim()) {
    ElMessage.warning('请填写申诉理由')
    return
  }
  appealSubmitting.value = true
  try {
    await appealApi.create({
      scoreItemId: appealItem.value.id,
      appealType: appealForm.appealType,
      appealReason: appealForm.appealReason.trim(),
      evidenceUrl: appealForm.evidenceUrl.trim() || null,
    })
    ElMessage.success('已提交并通知审核员')
    appealVisible.value = false
  } finally {
    appealSubmitting.value = false
  }
}

function openAdminChange(row: ScoreItem, dimensionOnly = false) {
  adminChangeItem.value = row
  adminChangeDimensionOnly.value = dimensionOnly
  adminChangeForm.changeType = 1
  adminChangeForm.dimension = row.dimension ?? 0
  adminChangeForm.category = row.category ?? 1
  adminChangeForm.points = Math.abs(Number(row.points)) || 1
  adminChangeForm.reason = row.reason ?? ''
  adminChangeForm.occurredOn = row.occurredOn
  adminChangeForm.changeReason = ''
  adminChangeVisible.value = true
}

async function submitAdminChange() {
  if (!adminChangeItem.value) return
  if (!adminChangeForm.changeReason.trim()) {
    ElMessage.warning('请填写变更依据')
    return
  }
  if (adminChangeForm.changeType === 1) {
    if (adminChangeForm.dimension < 1 || adminChangeForm.dimension > 5) {
      ElMessage.warning('请选择类别（德/智/体/美/劳）')
      return
    }
    if (!adminChangeForm.category) {
      ElMessage.warning('请选择修正后的方向')
      return
    }
    if (!adminChangeForm.points || adminChangeForm.points <= 0) {
      ElMessage.warning('修正后的分值必须为正数')
      return
    }
  }
  adminChangeSubmitting.value = true
  try {
    await scoreApi.adminChange(adminChangeItem.value.id, {
      changeType: adminChangeForm.changeType,
      dimension: adminChangeForm.changeType === 1 ? adminChangeForm.dimension : undefined,
      category: adminChangeForm.changeType === 1 ? adminChangeForm.category : undefined,
      points: adminChangeForm.changeType === 1 ? adminChangeForm.points : undefined,
      reason: adminChangeForm.changeType === 1 ? adminChangeForm.reason : undefined,
      occurredOn: adminChangeForm.changeType === 1 ? adminChangeForm.occurredOn : undefined,
      changeReason: adminChangeForm.changeReason.trim(),
    })
    ElMessage.success('修正已生效，原记录已失效')
    adminChangeVisible.value = false
    await load()
  } finally {
    adminChangeSubmitting.value = false
  }
}

async function load() {
  loading.value = true
  try {
    const [s, list] = await Promise.all([
      scoreApi.summary(studentId),
      scoreApi.studentItems(studentId, {
        category: category.value,
        dimension: dimension.value,
      }),
    ])
    summary.value = s
    items.value = list
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.cards { margin: 16px 0 0; }
.stat { text-align: center; }
.stat.highlight { background: #ecf5ff; }
.stat-label { font-size: 12px; color: #909399; }
.stat-value { font-size: 20px; font-weight: 600; margin-top: 4px; }
.stat-value.small { font-size: 15px; }
.stat-value.total { color: #409eff; font-size: 24px; }

.dim { color: #c0c4cc; }
.tip { margin-top: 16px; }
.hint-inline { margin-left: 10px; font-size: 12px; color: #909399; }
</style>
