<template>
  <div class="page">
    <div class="page-title">事由搜索</div>
    <div class="page-desc">
      输入活动名称（如「张衡杯」），列出所有相关的加减分记录。不限学期、不限状态，
      草稿和被驳回的也会列出来，方便对账。
    </div>

    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="输入活动或事由关键字，如：张衡杯"
        clearable
        style="width: 280px"
        @keyup.enter="onSearch"
      />
      <el-button type="primary" @click="onSearch">搜索</el-button>
      <span v-if="searched" class="summary">
        命中 <b>{{ total }}</b> 条 · 加分
        <b class="add">{{ fmt(summary.addPoints) }}</b> · 扣分
        <b class="deduct">{{ fmt(summary.deductPoints) }}</b>
      </span>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe height="calc(100vh - 250px)">
      <el-table-column label="日期" width="110">
        <template #default="{ row }">{{ row.occurredOn || '—' }}</template>
      </el-table-column>
      <el-table-column prop="studentNo" label="学号" width="130" />
      <el-table-column prop="studentName" label="姓名" width="90" />
      <el-table-column prop="className" label="班级" width="110" />
      <el-table-column label="五育" width="80">
        <template #default="{ row }">{{ dimText(row.dimension) }}</template>
      </el-table-column>
      <el-table-column label="分值" width="90" align="right">
        <template #default="{ row }">
          <span :class="row.category === 2 ? 'deduct' : 'add'">
            {{ row.category === 2 ? '−' : '+' }}{{ fmt(row.points) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="事由" min-width="260" />
      <el-table-column prop="term" label="学期" width="120" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="审核人" width="100">
        <template #default="{ row }">{{ row.reviewerName || '—' }}</template>
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
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { scoreApi, type ScoreSearchRow } from '@/api'

const keyword = ref('')
const rows = ref<ScoreSearchRow[]>([])
const loading = ref(false)
const searched = ref(false)
const total = ref(0)
const page = ref(1)
const size = ref(50)
const summary = reactive({ count: 0, addPoints: 0, deductPoints: 0 })

function fmt(v: number | null | undefined) {
  if (v == null) return '0'
  return Number(v).toFixed(2).replace(/\.00$/, '')
}

const DIMS: Record<number, string> = { 1: '德', 2: '智', 3: '体', 4: '美', 5: '劳' }
function dimText(d: number | null) {
  return d == null ? '未分类' : DIMS[d] ?? String(d)
}

const STATUS: Record<number, string> = {
  0: '草稿', 1: '待审', 2: '已通过', 3: '已驳回', 4: '已撤销', 5: '已失效',
}
function statusText(s: number) {
  return STATUS[s] ?? String(s)
}
function statusTag(s: number) {
  if (s === 1) return 'warning'
  if (s === 2) return 'success'
  if (s === 3) return 'danger'
  return 'info'
}

async function load() {
  const q = keyword.value.trim()
  if (!q) return
  loading.value = true
  try {
    const r = await scoreApi.searchByReason({ q, page: page.value, size: size.value })
    rows.value = r.list
    total.value = r.total
    summary.count = r.summary?.count ?? r.total
    summary.addPoints = r.summary?.addPoints ?? 0
    summary.deductPoints = r.summary?.deductPoints ?? 0
    searched.value = true
  } finally {
    loading.value = false
  }
}

function onSearch() {
  if (!keyword.value.trim()) {
    ElMessage.warning('请输入事由关键字')
    return
  }
  page.value = 1
  load()
}
</script>

<style scoped>
.summary {
  font-size: 13px;
  color: #4e5969;
}
.summary b {
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.add { color: #00b42a; }
.deduct { color: #f53f3f; }
.pager {
  margin-top: 12px;
}
</style>
