<template>
  <div class="page">
    <div class="page-title">录入申请</div>
    <div class="page-desc">
      <template v-if="store.isAdmin">
        你是<b>一级</b>：可以勾选「直接生效」跳过审批；不勾选则走正常审批流程。
      </template>
      <template v-else>
        你是<b>二级</b>：提交后需要审核员审批通过才计入总分。
      </template>
    </div>

    <el-row :gutter="16">
      <!-- 左：录入表单 -->
      <el-col :span="10">
        <el-card shadow="never">
          <template #header><b>{{ editingId ? '修改申请' : '新增记录' }}</b></template>

          <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
            <el-form-item label="学生" prop="studentId">
              <el-select
                v-model="form.studentId"
                filterable
                remote
                reserve-keyword
                placeholder="输入学号或姓名搜索"
                :remote-method="searchStudents"
                :loading="searching"
                style="width: 100%"
              >
                <el-option
                  v-for="s in candidates"
                  :key="s.id"
                  :label="`${s.name}（${s.studentNo}）`"
                  :value="s.id"
                />
              </el-select>
            </el-form-item>

            <el-form-item label="类别" prop="dimension">
              <el-select v-model="form.dimension" placeholder="请选择德/智/体/美/劳" style="width: 100%">
                <el-option
                  v-for="d in DIMENSIONS"
                  :key="d.value"
                  :label="d.label"
                  :value="d.value"
                />
              </el-select>
            </el-form-item>

            <el-form-item label="方向" prop="category">
              <el-radio-group v-model="form.category">
                <el-radio-button :value="1">加分</el-radio-button>
                <el-radio-button :value="2">扣分</el-radio-button>
              </el-radio-group>
            </el-form-item>

            <el-form-item label="分值" prop="points">
              <el-input-number v-model="form.points" :min="0.5" :step="0.5" :precision="1" />
              <span class="hint-inline">
                {{ form.category === 2 ? '扣' : '加' }} {{ form.points }} 分（填正数，方向由加分/扣分决定）
              </span>
            </el-form-item>

            <el-form-item label="事由" prop="reason">
              <el-input
                v-model="form.reason"
                type="textarea"
                :rows="2"
                maxlength="200"
                show-word-limit
                placeholder="如：双语未到"
              />
            </el-form-item>

            <el-form-item label="发生日期">
              <el-date-picker
                v-model="form.occurredOn"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="可不填"
                style="width: 100%"
              />
            </el-form-item>

            <el-form-item v-if="store.isAdmin" label="直接生效">
              <el-switch v-model="form.effectNow" />
              <span class="hint-inline">跳过审批，立即计入总分</span>
            </el-form-item>

            <el-form-item>
              <el-button type="primary" :loading="saving" @click="onSave(false)">
                {{ editingId ? '保存修改' : '保存草稿' }}
              </el-button>
              <el-button :loading="saving" @click="onSave(true)">
                {{ store.isAdmin && form.effectNow ? '保存并生效' : '保存并提交' }}
              </el-button>
              <el-button v-if="editingId" link @click="resetForm">取消编辑</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <!-- 右：我的申请 -->
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <b>我的申请</b>
              <el-radio-group v-model="mineStatus" size="small" @change="loadMine">
                <el-radio-button :value="undefined">全部</el-radio-button>
                <el-radio-button :value="0">草稿</el-radio-button>
                <el-radio-button :value="1">待审</el-radio-button>
                <el-radio-button :value="3">已驳回</el-radio-button>
                <el-radio-button :value="2">已通过</el-radio-button>
              </el-radio-group>
            </div>
          </template>

          <el-table v-loading="mineLoading" :data="mine" border size="small" max-height="520">
            <el-table-column prop="studentName" label="学生" width="80" />
            <el-table-column prop="dimensionText" label="类别" width="70" />
            <el-table-column prop="categoryText" label="方向" width="70" />
            <el-table-column label="分值" width="70" align="right">
              <template #default="{ row }">
                <span :class="ptsClass(row.signedPoints)">
                  {{ row.signedPoints > 0 ? '+' : '' }}{{ row.signedPoints }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="事由" min-width="150" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="statusTag(row.status)">{{ row.statusText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160">
              <template #default="{ row }">
                <el-button v-if="row.canEdit" link type="primary" @click="startEdit(row)">编辑</el-button>
                <el-button v-if="row.canEdit" link type="success" @click="onSubmit(row)">提交</el-button>
                <el-button v-if="row.canRevoke" link type="warning" @click="onRevoke(row)">撤回</el-button>
                <el-button
                  v-if="row.status === 2"
                  link
                  type="primary"
                  @click="startChange(row)"
                >申请变更</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-alert
            v-if="mine.some((r) => r.rejectReason)"
            class="reject-tip"
            type="warning"
            :closable="false"
            show-icon
            title="有被驳回的申请"
            description="把鼠标放到表格里的「已驳回」标签上可以看到理由，改完重新提交即可。"
          />

          <el-alert
            v-if="mine.some((r) => r.status === 1)"
            class="pending-tip"
            type="info"
            :closable="false"
            show-icon
            title="已提交待审的记录不能修改"
            description="待审期间只能撤回；撤回后可以重新编辑并提交。"
          />
        </el-card>
      </el-col>
    </el-row>

    <!-- 变更申请弹窗 -->
    <el-dialog v-model="changeVisible" title="申请变更已通过的记录" width="520px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="原记录不会被直接修改"
        description="系统会新建一条变更申请，审核通过后原记录自动失效，变更链完整保留。"
      />
      <el-form :model="changeForm" label-width="90px" style="margin-top: 16px">
        <el-form-item label="变更类型">
          <el-radio-group v-model="changeForm.changeType">
            <el-radio-button :value="1">修改</el-radio-button>
            <el-radio-button :value="2">删除这条记录</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <template v-if="changeForm.changeType === 1">
          <el-form-item label="类别" required>
            <el-select v-model="changeForm.dimension" placeholder="请选择德/智/体/美/劳" style="width: 100%">
              <el-option
                v-for="d in DIMENSIONS"
                :key="d.value"
                :label="d.label"
                :value="d.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="方向">
            <el-radio-group v-model="changeForm.category">
              <el-radio-button :value="1">加分</el-radio-button>
              <el-radio-button :value="2">扣分</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="新分值">
            <el-input-number v-model="changeForm.points" :min="0.5" :step="0.5" :precision="1" />
          </el-form-item>
          <el-form-item label="新事由">
            <el-input v-model="changeForm.reason" placeholder="留空则沿用原事由" />
          </el-form-item>
        </template>
        <el-form-item label="变更依据" required>
          <el-input
            v-model="changeForm.changeReason"
            type="textarea"
            :rows="2"
            placeholder="说明为什么要改，例如：签到表显示服务时长为 3 小时"
          />
        </el-form-item>
        <el-form-item v-if="store.isAdmin" label="直接生效">
          <el-switch v-model="changeForm.effectNow" />
          <span class="hint-inline">跳过审批（一级权限）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="changeVisible = false">取消</el-button>
        <el-button type="primary" :loading="changing" @click="submitChange">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { scoreApi, studentApi, type ScoreItemForm } from '@/api'
import type { MyApplyRow, Student } from '@/api/types'
import { useUserStore } from '@/stores/user'

const store = useUserStore()

const formRef = ref<FormInstance>()
const form = reactive<ScoreItemForm & { effectNow: boolean }>({
  studentId: 0,
  dimension: 0,
  category: 1,
  points: 1,
  reason: '',
  occurredOn: null,
  effectNow: false,
})

const DIMENSIONS = [
  { value: 1, label: '德' },
  { value: 2, label: '智' },
  { value: 3, label: '体' },
  { value: 4, label: '美' },
  { value: 5, label: '劳' },
]

const rules: FormRules = {
  studentId: [{ required: true, message: '请选择学生', trigger: 'change' }],
  dimension: [{
    validator: (_rule: unknown, value: number, callback: (e?: Error) => void) =>
      (value >= 1 && value <= 5 ? callback() : callback(new Error('请选择类别（德/智/体/美/劳）'))),
    trigger: 'change',
  }],
  category: [{ required: true, message: '请选择方向', trigger: 'change' }],
  points: [{ required: true, message: '请填写分值', trigger: 'blur' }],
  reason: [{ required: true, message: '请填写事由', trigger: 'blur' }],
}

const candidates = ref<Student[]>([])
const searching = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)

const mine = ref<MyApplyRow[]>([])
const mineLoading = ref(false)
const mineStatus = ref<number | undefined>()

const changeVisible = ref(false)
const changing = ref(false)
const changeForm = reactive({
  id: 0,
  changeType: 1,
  dimension: 0,
  category: 1,
  points: 1,
  reason: '',
  changeReason: '',
  effectNow: false,
})

function ptsClass(v: number) {
  if (v > 0) return 'pts-plus'
  if (v < 0) return 'pts-minus'
  return 'pts-zero'
}

function statusTag(s: number) {
  return ({ 0: 'info', 1: 'warning', 2: 'success', 3: 'danger', 4: 'info', 5: 'info' } as const)[s as 0] ?? 'info'
}

async function searchStudents(kw: string) {
  if (!kw) return
  searching.value = true
  try {
    candidates.value = await studentApi.search(kw)
  } finally {
    searching.value = false
  }
}

function resetForm() {
  editingId.value = null
  form.studentId = 0
  form.dimension = 0
  form.category = 1
  form.points = 1
  form.reason = ''
  form.occurredOn = null
  form.effectNow = false
}

async function onSave(andSubmit: boolean) {
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return

  saving.value = true
  try {
    let id = editingId.value

    if (id) {
      await scoreApi.update(id, form)
      ElMessage.success('已保存')
    } else {
      const r = await scoreApi.create(form)
      id = r.id
      if (r.duplicateWarning) {
        // 只提示不阻断——同一个学生一个月内两次被子未叠是完全可能的
        ElMessage.warning(r.duplicateWarning)
      }
      if (r.effectNow) {
        ElMessage.success('已直接生效，总分已更新')
      } else {
        ElMessage.success('已保存为草稿')
      }
    }

    if (andSubmit && id && !(store.isAdmin && form.effectNow)) {
      await scoreApi.submit(id)
      ElMessage.success('已提交，等待审核')
    }

    resetForm()
    await loadMine()
  } finally {
    saving.value = false
  }
}

function startEdit(row: MyApplyRow) {
  editingId.value = row.id
  form.studentId = row.studentId
  form.dimension = row.dimension ?? 0
  form.category = row.category as 1 | 2
  form.points = Math.abs(row.signedPoints)
  form.reason = row.reason
  form.occurredOn = row.occurredOn
  form.effectNow = false
  ElMessage.info('已载入到左侧表单，改完点「保存修改」')
}

async function onSubmit(row: MyApplyRow) {
  await scoreApi.submit(row.id)
  ElMessage.success('已提交')
  await loadMine()
}

async function onRevoke(row: MyApplyRow) {
  await ElMessageBox.confirm('确定撤回这条申请吗？撤回后可以重新编辑提交。', '提示', { type: 'warning' })
  await scoreApi.revoke(row.id)
  ElMessage.success('已撤回')
  await loadMine()
}

function startChange(row: MyApplyRow) {
  changeForm.id = row.id
  changeForm.changeType = 1
  changeForm.dimension = row.dimension ?? 0
  changeForm.category = row.category
  changeForm.points = Math.abs(row.signedPoints)
  changeForm.reason = row.reason
  changeForm.changeReason = ''
  changeForm.effectNow = false
  changeVisible.value = true
}

async function submitChange() {
  if (!changeForm.changeReason.trim()) {
    ElMessage.warning('请填写变更依据')
    return
  }
  if (changeForm.changeType === 1 && (changeForm.dimension < 1 || changeForm.dimension > 5)) {
    ElMessage.warning('请选择类别（德/智/体/美/劳）')
    return
  }
  changing.value = true
  try {
    const r = await scoreApi.change(changeForm.id, {
      changeType: changeForm.changeType,
      dimension: changeForm.changeType === 1 ? changeForm.dimension : undefined,
      category: changeForm.category,
      points: changeForm.points,
      reason: changeForm.reason,
      changeReason: changeForm.changeReason,
      effectNow: changeForm.effectNow,
    })
    ElMessage.success(r.effectNow ? '变更已直接生效' : '变更申请已提交，等待审核')
    changeVisible.value = false
    await loadMine()
  } finally {
    changing.value = false
  }
}

async function loadMine() {
  mineLoading.value = true
  try {
    const r = await scoreApi.mine({ status: mineStatus.value, page: 1, size: 50 })
    mine.value = r.list
  } finally {
    mineLoading.value = false
  }
}

onMounted(loadMine)
</script>

<style scoped>
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.hint-inline {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}
.reject-tip { margin-top: 12px; }
.pending-tip { margin-top: 12px; }
</style>
