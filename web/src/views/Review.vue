<template>
  <div class="page">
    <div class="page-title">待审</div>
    <div class="page-desc">
      共 {{ total }} 条待处理。自己提交的申请不能自己审——「通过/驳回」按钮会置灰。
    </div>

    <div class="toolbar">
      <el-select v-model="filterClassId" placeholder="全部班级" clearable style="width: 160px" @change="load">
        <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-select v-model="filterDeptId" placeholder="全部来源" clearable style="width: 150px" @change="load">
        <el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id" />
      </el-select>
      <el-select v-model="filterCategory" placeholder="全部方向" clearable style="width: 130px" @change="load">
        <el-option label="加分" :value="1" />
        <el-option label="扣分" :value="2" />
      </el-select>
      <el-select v-model="filterDimension" placeholder="全部类别" clearable style="width: 130px" @change="load">
        <el-option v-for="d in DIMENSIONS" :key="d.value" :label="d.label" :value="d.value" />
      </el-select>
      <el-checkbox v-model="changeOnly" @change="load">只看变更申请</el-checkbox>
      <div class="spacer" />
      <el-button @click="load"><el-icon><Refresh /></el-icon>&nbsp;刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe height="calc(100vh - 250px)">
      <el-table-column label="学生" width="150">
        <template #default="{ row }">
          <div>{{ row.studentName }}</div>
          <div class="sub">{{ row.studentNo }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="className" label="班级" width="110" />
      <el-table-column label="类别" width="80">
        <template #default="{ row }">
          <el-tag size="small" type="info">{{ row.dimensionText || '未分类' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="方向" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="catTag(row.category)">{{ row.categoryText }}</el-tag>
          <el-tag v-if="row.changeType" size="small" type="warning" class="ml4">
            {{ row.changeTypeText }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="分值" width="80" align="right">
        <template #default="{ row }">
          <span :class="ptsClass(row.signedPoints)">
            {{ row.signedPoints > 0 ? '+' : '' }}{{ row.signedPoints }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="事由" min-width="180" show-overflow-tooltip />
      <el-table-column prop="occurredOn" label="发生日期" width="110" />
      <el-table-column label="提交" width="150">
        <template #default="{ row }">
          <div>{{ row.submitterName || '—' }}</div>
          <div class="sub">{{ row.submittedAt || '' }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="deptName" label="来源" width="100">
        <template #default="{ row }">{{ row.deptName || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="270" fixed="right">
        <template #default="{ row }">
          <template v-if="row.canReview">
            <el-button link type="success" @click="onApprove(row)">通过</el-button>
            <el-button link type="danger" @click="onReject(row)">驳回</el-button>
            <el-button link type="warning" @click="onReturn(row)">打回重做</el-button>
          </template>
          <el-button link type="info" @click="onAdminRevoke(row)">撤回</el-button>
          <el-tooltip v-if="!row.canReview" content="不能审批 / 打回自己提交的申请" placement="top">
            <span class="dim">自己提交的</span>
          </el-tooltip>
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
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { metaApi, reviewApi, scoreApi } from '@/api'
import type { ClassMeta, Dept, PendingRow } from '@/api/types'

const rows = ref<PendingRow[]>([])
const classes = ref<ClassMeta[]>([])
const depts = ref<Dept[]>([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const size = ref(20)

const filterClassId = ref<number | undefined>()
const filterDeptId = ref<number | undefined>()
const filterCategory = ref<number | undefined>()
const filterDimension = ref<number | undefined>()
const changeOnly = ref(false)

const DIMENSIONS = [
  { value: 1, label: '德' },
  { value: 2, label: '智' },
  { value: 3, label: '体' },
  { value: 4, label: '美' },
  { value: 5, label: '劳' },
]

function ptsClass(v: number) {
  if (v > 0) return 'pts-plus'
  if (v < 0) return 'pts-minus'
  return 'pts-zero'
}

function catTag(c: number) {
  return ({ 1: 'success', 2: 'danger' } as const)[c as 1 | 2] ?? 'info'
}

async function load() {
  loading.value = true
  try {
    const r = await reviewApi.pending({
      classId: filterClassId.value,
      deptId: filterDeptId.value,
      category: filterCategory.value,
      dimension: filterDimension.value,
      changeOnly: changeOnly.value || undefined,
      page: page.value,
      size: size.value,
    })
    rows.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

async function onApprove(row: PendingRow) {
  const extra = row.changeType === 1 ? '（这是一条修改申请，通过后原记录会失效）' : ''
  await ElMessageBox.confirm(
    `确认通过「${row.studentName}」的这条申请？${extra}`,
    '确认通过',
    { type: 'warning' },
  )
  await reviewApi.approve(row.id)
  ElMessage.success('已通过，总分已更新')
  await load()
}

async function onReject(row: PendingRow) {
  const { value } = await ElMessageBox.prompt(
    '请说明驳回理由，提交人会看到（必填）',
    '驳回申请',
    {
      inputPlaceholder: '例如：缺少活动签到表，请补充佐证材料',
      inputValidator: (v: string) => (v && v.trim() ? true : '驳回理由不能为空'),
      type: 'warning',
    },
  )
  await reviewApi.reject(row.id, value)
  ElMessage.success('已驳回')
  await load()
}

async function onReturn(row: PendingRow) {
  const { value } = await ElMessageBox.prompt(
    '请说明打回理由，提交人改完可以重新提交（必填）',
    '打回重做',
    {
      inputPlaceholder: '例如：事由写错，请修改后重提',
      inputValidator: (v: string) => (v && v.trim() ? true : '打回理由不能为空'),
      type: 'warning',
    },
  )
  await scoreApi.returnForRedo(row.id, value)
  ElMessage.success('已打回，提交人可修改后重提')
  await load()
}

async function onAdminRevoke(row: PendingRow) {
  const { value } = await ElMessageBox.prompt(
    '可选：填写撤回原因（只记入审计日志）',
    '撤回申请',
    {
      inputPlaceholder: '可不填',
      type: 'warning',
    },
  )
  await scoreApi.adminRevoke(row.id, value?.trim() || undefined)
  ElMessage.success('已撤回')
  await load()
}

onMounted(async () => {
  const [c, d] = await Promise.all([metaApi.classes(), metaApi.depts()])
  classes.value = c
  depts.value = d
  await load()
})
</script>

<style scoped>
.sub { font-size: 12px; color: #909399; }
.ml4 { margin-left: 4px; }
.dim { color: #c0c4cc; font-size: 13px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
