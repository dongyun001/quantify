<template>
  <div class="page">
    <div class="page-title">批量导入</div>
    <div class="page-desc">
      用 Excel 一次给多个学生加减分。每行一条记录，分值可以各不相同；
      先预览核对，再正式导入。单次最多 2000 行。
    </div>

    <!-- 1. 选择文件 -->
    <el-card shadow="never" class="step-card">
      <template #header>
        <div class="card-head">
          <b>1. 选择文件</b>
          <el-button link type="primary" :loading="downloading" @click="onDownloadTemplate">
            下载模板
          </el-button>
        </div>
      </template>

      <el-upload
        drag
        :auto-upload="false"
        :limit="1"
        accept=".xlsx,.xls"
        :file-list="fileList"
        :on-change="onFileChange"
        :on-remove="onFileRemove"
        :on-exceed="onExceed"
      >
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div class="el-upload__text">把 Excel 拖到这里，或<em>点击选择</em></div>
        <template #tip>
          <div class="el-upload__tip">
            只支持 .xlsx / .xls，不超过 20MB。表头按列名识别，列顺序可以调整。
          </div>
        </template>
      </el-upload>

      <div class="actions">
        <el-button
          type="primary"
          :loading="previewing"
          :disabled="!selectedFile || previewing"
          @click="onPreview"
        >
          上传并预览
        </el-button>
        <el-button v-if="preview" @click="reset">重新选择</el-button>
      </div>
    </el-card>

    <!-- 2. 预览与修正 -->
    <template v-if="preview">
      <el-card shadow="never" class="step-card">
        <template #header>
          <div class="card-head">
            <b>2. 核对并修正</b>
            <span class="stats">
              共 {{ preview.total }} 行 · 可导入
              <b class="ok">{{ preview.validCount }}</b> · 有问题
              <b :class="preview.invalidCount ? 'bad' : ''">{{ preview.invalidCount }}</b>
            </span>
          </div>
        </template>

        <el-alert
          v-if="preview.invalidCount > 0"
          type="error"
          :closable="false"
          show-icon
          :title="`有 ${preview.invalidCount} 行需要修正`"
          description="错误行已标红。类别 / 方向 / 分值 / 事由 / 日期可以直接在表格里改；学号或姓名有问题请回 Excel 修改后重新上传。"
        />
        <el-alert
          v-if="warningCount > 0"
          class="mt"
          type="warning"
          :closable="false"
          show-icon
          :title="`有 ${warningCount} 行提示`"
          description="警告不阻断导入，但请确认不是重复录入。"
        />

        <el-table
          :data="preview.rows"
          border
          size="small"
          max-height="560"
          class="preview-table mt"
          :row-class-name="rowClass"
        >
          <el-table-column prop="rowNo" label="行" width="55" align="center" />
          <el-table-column prop="studentNo" label="学号" width="130" />
          <el-table-column prop="studentName" label="姓名" width="90" />
          <el-table-column prop="className" label="班级" width="110" />
          <el-table-column label="类别" width="95">
            <template #default="{ row }">
              <el-select
                v-model="row.dimension"
                size="small"
                placeholder="请选择"
                :disabled="row.identityInvalid"
                @change="onRowEdited(row)"
              >
                <el-option label="德" :value="1" />
                <el-option label="智" :value="2" />
                <el-option label="体" :value="3" />
                <el-option label="美" :value="4" />
                <el-option label="劳" :value="5" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="方向" width="115">
            <template #default="{ row }">
              <el-select
                v-model="row.category"
                size="small"
                placeholder="请选择"
                :disabled="row.identityInvalid"
                @change="onRowEdited(row)"
              >
                <el-option label="加分" :value="1" />
                <el-option label="扣分" :value="2" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="分值" width="125">
            <template #default="{ row }">
              <el-input-number
                v-model="row.points"
                size="small"
                :min="0.01"
                :step="0.5"
                :precision="2"
                controls-position="right"
                :disabled="row.identityInvalid"
                @change="onRowEdited(row)"
              />
            </template>
          </el-table-column>
          <el-table-column label="事由" min-width="180">
            <template #default="{ row }">
              <el-input
                v-model="row.reason"
                size="small"
                maxlength="500"
                :disabled="row.identityInvalid"
                @input="onRowEdited(row)"
              />
            </template>
          </el-table-column>
          <el-table-column label="发生日期" width="150">
            <template #default="{ row }">
              <el-date-picker
                v-model="row.occurredOn"
                type="date"
                size="small"
                value-format="YYYY-MM-DD"
                placeholder="可不填"
                style="width: 100%"
                :disabled="row.identityInvalid"
                @change="onRowEdited(row)"
              />
            </template>
          </el-table-column>
          <el-table-column label="问题 / 提示" min-width="250">
            <template #default="{ row }">
              <div v-if="row.error" class="cell-error">{{ row.error }}</div>
              <div v-if="row.warning" class="cell-warning">{{ row.warning }}</div>
              <span v-if="!row.error && !row.warning" class="cell-ok">可导入</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 3. 导入选项 -->
      <el-card shadow="never" class="step-card">
        <template #header><b>3. 确认导入</b></template>

        <div class="options">
          <el-checkbox v-model="submitNow">导入后直接提交审批</el-checkbox>
          <el-checkbox v-if="store.isAdmin" v-model="effectNow">
            直接生效（跳过审批，仅一级）
          </el-checkbox>
        </div>
        <div class="hint">
          <template v-if="effectNow">
            这一批将直接计入总分（状态为「已通过」）。
          </template>
          <template v-else-if="submitNow">
            这一批将以「待审」状态进入审批流程。
          </template>
          <template v-else>
            这一批将保存为「草稿」，之后可在「录入申请」里逐条提交。
          </template>
        </div>

        <div class="actions">
          <el-button
            type="primary"
            :loading="importing"
            :disabled="!canImport"
            @click="onImport"
          >
            确认导入 {{ importableRows.length }} 条
          </el-button>
          <span v-if="!canImport" class="block-hint">
            请先修正 {{ preview.invalidCount }} 处问题
          </span>
        </div>
      </el-card>
    </template>

    <!-- 导入结果 -->
    <el-card v-if="result" shadow="never" class="step-card">
      <template #header><b>导入完成</b></template>
      <el-result
        icon="success"
        :title="`成功导入 ${result.created} 条`"
        :sub-title="`批次号 ${result.batchNo} · ${result.statusText}`"
      >
        <template #extra>
          <el-button type="primary" @click="goBoard">去积分总表看看</el-button>
          <el-button :loading="revoking" @click="onRevoke">撤回本批次</el-button>
          <el-button @click="reset">继续导入</el-button>
        </template>
      </el-result>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ElMessage,
  ElMessageBox,
  type UploadFile,
  type UploadFiles,
} from 'element-plus'
import { scoreApi } from '@/api'
import type {
  BatchImportItem,
  BatchImportResult,
  ImportPreview,
  ImportPreviewRow,
} from '@/api/types'
import { useUserStore } from '@/stores/user'

const store = useUserStore()
const router = useRouter()

const fileList = ref<UploadFile[]>([])
const selectedFile = ref<File | null>(null)
const downloading = ref(false)
const previewing = ref(false)
const preview = ref<ImportPreview | null>(null)
const importing = ref(false)
const revoking = ref(false)
const result = ref<BatchImportResult | null>(null)

const submitNow = ref(true)
const effectNow = ref(false)

const importableRows = computed(() =>
  (preview.value?.rows ?? []).filter((r) => !r.error && r.studentId != null),
)

const warningCount = computed(
  () => (preview.value?.rows ?? []).filter((r) => r.warning).length,
)

const canImport = computed(
  () => !!preview.value && preview.value.invalidCount === 0 && importableRows.value.length > 0,
)

function onFileChange(file: UploadFile, files: UploadFiles) {
  fileList.value = files.slice(-1)
  selectedFile.value = file.raw ?? null
  preview.value = null
  result.value = null
}

function onFileRemove() {
  fileList.value = []
  selectedFile.value = null
  preview.value = null
}

function onExceed(files: File[]) {
  const file = files[0]
  if (!file) return
  ElMessage.warning('一次只能上传一个文件，已替换为新文件')
  selectedFile.value = file
  fileList.value = [{
    name: file.name,
    size: file.size,
    raw: file,
    uid: Date.now(),
    status: 'ready',
  } as UploadFile]
  preview.value = null
  result.value = null
}

function rowClass({ row }: { row: ImportPreviewRow }) {
  if (row.error) return 'row-error'
  if (row.warning) return 'row-warning'
  return ''
}

/** 只校验可在预览页修改的字段；学号/姓名问题由 identityInvalid 标记，保持不可修 */
function localError(row: ImportPreviewRow): string | null {
  const errors: string[] = []

  if (row.dimension == null || row.dimension < 1 || row.dimension > 5) {
    errors.push('请选择类别（德/智/体/美/劳）')
  }

  if (row.category !== 1 && row.category !== 2) {
    errors.push('类型只能是 加分/扣分')
  }

  const points = row.points
  if (points == null || Number(points) <= 0) {
    errors.push('分值必须为正数，扣分请把类型选为「扣分」')
  } else {
    const text = String(points)
    const dot = text.indexOf('.')
    if (dot >= 0 && text.length - dot - 1 > 2) {
      errors.push('分值最多 2 位小数')
    } else if (Number(points) > 9999.99) {
      errors.push('分值超出允许范围（最大 9999.99）')
    }
  }

  if (!row.reason || !row.reason.trim()) {
    errors.push('事由不能为空')
  } else if (row.reason.trim().length > 500) {
    errors.push('事由不能超过 500 字')
  }

  if (row.occurredOn && !/^\d{4}-\d{2}-\d{2}$/.test(row.occurredOn)) {
    errors.push('日期格式应为 yyyy-MM-dd')
  }

  return errors.length ? errors.join('；') : null
}

function onRowEdited(row: ImportPreviewRow) {
  if (row.identityInvalid) return
  row.error = localError(row)
  // 改过字段后，原来的重复提示已经过期
  row.warning = null
  recomputeCounts()
}

function recomputeCounts() {
  if (!preview.value) return
  const valid = preview.value.rows.filter((r) => !r.error).length
  preview.value.validCount = valid
  preview.value.invalidCount = preview.value.rows.length - valid
  preview.value.canImport = valid === preview.value.rows.length
}

async function onDownloadTemplate() {
  downloading.value = true
  try {
    await scoreApi.importTemplate()
    ElMessage.success('模板已下载')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '下载模板失败')
  } finally {
    downloading.value = false
  }
}

async function onPreview() {
  if (!selectedFile.value) return
  previewing.value = true
  try {
    const data = await scoreApi.importPreview(selectedFile.value)
    preview.value = data
    if (data.invalidCount > 0) {
      ElMessage.warning(`解析完成：有 ${data.invalidCount} 行需要修正`)
    } else {
      ElMessage.success(`解析完成：${data.validCount} 行都可以导入`)
    }
  } finally {
    previewing.value = false
  }
}

async function onImport() {
  if (!preview.value || !canImport.value) return

  if (warningCount.value > 0) {
    try {
      await ElMessageBox.confirm(
        `有 ${warningCount.value} 行提示（例如重复录入），确定继续导入吗？`,
        '请确认',
        { type: 'warning' },
      )
    } catch {
      return
    }
  }

  const items: BatchImportItem[] = importableRows.value.map((row) => ({
    studentId: row.studentId as number,
    dimension: row.dimension as number,
    category: row.category as number,
    points: Number(row.points),
    reason: row.reason.trim(),
    occurredOn: row.occurredOn || null,
  }))

  importing.value = true
  try {
    result.value = await scoreApi.batchImport({
      items,
      submitNow: submitNow.value,
      effectNow: store.isAdmin && effectNow.value,
    })
    ElMessage.success(`成功导入 ${result.value.created} 条`)
    preview.value = null
    fileList.value = []
    selectedFile.value = null
  } finally {
    importing.value = false
  }
}

async function onRevoke() {
  if (!result.value) return
  const batchNo = result.value.batchNo
  try {
    await ElMessageBox.confirm(
      `确定撤回批次 ${batchNo} 里还没审核的记录吗？已通过的不受影响。`,
      '撤回批次',
      { type: 'warning' },
    )
  } catch {
    return
  }

  revoking.value = true
  try {
    const data = await scoreApi.revokeBatch(batchNo)
    ElMessage.success(`已撤回 ${data.revoked} 条`)
    result.value = null
  } finally {
    revoking.value = false
  }
}

function goBoard() {
  router.push('/board')
}

function reset() {
  fileList.value = []
  selectedFile.value = null
  preview.value = null
  result.value = null
  submitNow.value = true
  effectNow.value = false
}
</script>

<style scoped>
.step-card {
  margin-bottom: 16px;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.stats {
  font-size: 13px;
  color: #606266;
}
.stats .ok {
  color: #67c23a;
}
.stats .bad {
  color: #f56c6c;
}
.actions {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.upload-icon {
  font-size: 46px;
  color: #c0c4cc;
}
.options {
  display: flex;
  align-items: center;
  gap: 24px;
}
.hint {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}
.block-hint {
  font-size: 12px;
  color: #f56c6c;
}
.mt {
  margin-top: 12px;
}
.cell-error {
  color: #f56c6c;
  font-size: 12px;
  line-height: 1.4;
}
.cell-warning {
  color: #e6a23c;
  font-size: 12px;
  line-height: 1.4;
}
.cell-ok {
  color: #67c23a;
  font-size: 12px;
}
.preview-table :deep(.el-table__body tr.row-error > td.el-table__cell) {
  background: #fef0f0;
}
.preview-table :deep(.el-table__body tr.row-warning > td.el-table__cell) {
  background: #fdf6ec;
}
</style>
