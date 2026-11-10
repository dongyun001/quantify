<template>
  <div class="page">
    <div class="page-title">我的申诉</div>
    <div class="page-desc">
      只能对本班「已通过」的记录申诉。待处理的申诉可以撤回；已受理 / 已驳回的由一级处理。
    </div>

    <div class="toolbar">
      <el-radio-group v-model="status" @change="load">
        <el-radio-button :value="undefined">全部</el-radio-button>
        <el-radio-button :value="0">待处理</el-radio-button>
        <el-radio-button :value="1">已受理</el-radio-button>
        <el-radio-button :value="2">已驳回</el-radio-button>
        <el-radio-button :value="3">已撤回</el-radio-button>
      </el-radio-group>
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
      <el-table-column label="原记录" min-width="240">
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
      <el-table-column prop="appealReason" label="申诉理由" min-width="180" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">{{ row.statusText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="处理意见" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.reviewRemark || '—' }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="提交时间" width="170" />
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="showDetail(row)">查看</el-button>
          <el-button v-if="row.status === 0" link type="warning" @click="onWithdraw(row)">
            撤回
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
          <el-descriptions-item label="发生日期">
            {{ current.scoreOccurredOn || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="原事由" :span="2">
            {{ current.scoreReason || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="申诉类型">{{ current.appealTypeText }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="statusTag(current.status)">{{ current.statusText }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="申诉理由" :span="2">
            {{ current.appealReason }}
          </el-descriptions-item>
          <el-descriptions-item label="佐证链接" :span="2">
            <a v-if="current.evidenceUrl" :href="current.evidenceUrl" target="_blank" rel="noreferrer">
              {{ current.evidenceUrl }}
            </a>
            <span v-else class="sub">—</span>
          </el-descriptions-item>
          <el-descriptions-item label="处理意见" :span="2">
            {{ current.reviewRemark || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="处理人">{{ current.reviewedByName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="处理时间">{{ current.reviewedAt || '—' }}</el-descriptions-item>
          <el-descriptions-item label="提交时间" :span="2">{{ current.createdAt }}</el-descriptions-item>
        </el-descriptions>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { appealApi } from '@/api'
import type { AppealRow } from '@/api/types'

const rows = ref<AppealRow[]>([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref<number | undefined>()

const detailVisible = ref(false)
const current = ref<AppealRow | null>(null)

function statusTag(s: number) {
  return ({ 0: 'warning', 1: 'success', 2: 'danger', 3: 'info' } as const)[s as 0] ?? 'info'
}

function catTag(c: number | null) {
  return ({ 1: 'success', 2: 'danger', 3: 'warning' } as const)[c as 1 | 2 | 3] ?? 'info'
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

async function onWithdraw(row: AppealRow) {
  await ElMessageBox.confirm('确定撤回这条申诉吗？撤回后如果还需要申诉，可以重新提交。', '提示', {
    type: 'warning',
  })
  await appealApi.withdraw(row.id)
  ElMessage.success('已撤回')
  await load()
}

async function load() {
  loading.value = true
  try {
    const r = await appealApi.mine({ status: status.value, page: page.value, size: size.value })
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
</style>
