<template>
  <div class="page">
    <div class="page-title">积分总表</div>
    <div class="page-desc">
      {{ scopeDesc }} · 学期 {{ term || '当前学期' }}
      <span v-if="store.isViewer"> · 你只有查看权限</span>
      <span v-else-if="store.isEditor"> · 你只能修改自己提交的记录</span>
    </div>

    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="学号或姓名"
        clearable
        style="width: 180px"
        @keyup.enter="reloadFromFirstPage"
        @clear="reloadFromFirstPage"
      />
      <el-select v-model="classId" placeholder="全部班级" clearable style="width: 160px" @change="load">
        <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-button type="primary" @click="reloadFromFirstPage">查询</el-button>

      <div class="spacer" />

      <el-button :loading="exporting" @click="onExport">
        <el-icon><Download /></el-icon>&nbsp;导出 Excel
      </el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      border
      stripe
      height="calc(100vh - 290px)"
      :default-sort="{ prop: 'rank', order: 'ascending' }"
    >
      <el-table-column prop="rank" label="排名" width="70" align="center" />
      <el-table-column prop="studentNo" label="学号" width="130" />
      <el-table-column label="姓名" width="100">
        <template #default="{ row }">
          <a class="name-link" @click="goDetail(row)">{{ row.name }}</a>
        </template>
      </el-table-column>
      <el-table-column prop="className" label="班级" width="110" />
      <el-table-column label="加分" width="80" align="right">
        <template #default="{ row }">
          <span :class="ptsClass(row.addScore)">{{ fmt(row.addScore) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="扣分" width="80" align="right">
        <template #default="{ row }">
          <span :class="ptsClass(row.deductScore)">{{ fmt(row.deductScore) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="基础分" width="80" align="right">
        <template #default="{ row }">{{ fmt(row.baseScore) }}</template>
      </el-table-column>
      <el-table-column label="总分" width="90" align="right" sortable>
        <template #default="{ row }">
          <span class="total-cell">{{ fmt(row.totalScore) }}</span>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      background
      layout="total, sizes, prev, pager, next, jumper"
      :total="total"
      :page-size="size"
      :current-page="page"
      :page-sizes="[20, 50, 100, 200]"
      @current-change="onPageChange"
      @size-change="onSizeChange"
    />

    <div class="footer-note">
      共 {{ total }} 人 · 总分 = 基础分 + 加分 − 扣分，只统计「已通过」的记录
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import { configApi, exportScores, metaApi, scoreApi } from '@/api'
import type { ClassMeta, ScoreBoardRow } from '@/api/types'
import { useUserStore } from '@/stores/user'

const store = useUserStore()
const router = useRouter()

const rows = ref<ScoreBoardRow[]>([])
const classes = ref<ClassMeta[]>([])
const loading = ref(false)
const exporting = ref(false)
const keyword = ref('')
const classId = ref<number | undefined>()
const term = ref('')

// 分页。size 上限后端卡在 200，前端别超过
const total = ref(0)
const page = ref(1)
const size = ref(50)

const scopeDesc = computed(() => {
  const u = store.user
  if (!u) return ''
  if (u.scopeType === 3) return `数据范围：${u.className ?? '本班'}`
  if (u.scopeType === 2) return `数据范围：${u.gradeName ?? '本年级'}`
  return `数据范围：全院 ${u.classCount} 个班`
})

function fmt(v: number | null | undefined) {
  if (v === null || v === undefined) return '0'
  return String(Number(v))
}

function ptsClass(v: number) {
  if (v > 0) return 'pts-plus'
  if (v < 0) return 'pts-minus'
  return 'pts-zero'
}

function goDetail(row: ScoreBoardRow) {
  router.push(`/student/${row.studentId}`)
}

async function load() {
  loading.value = true
  try {
    const r = await scoreApi.board({
      term: term.value || undefined,
      keyword: keyword.value || undefined,
      classId: classId.value,
      page: page.value,
      size: size.value,
    })
    rows.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

/** 改查询条件时要回到第 1 页 —— 否则在第 3 页搜一个只有 2 条结果的关键词会显示空白 */
function reloadFromFirstPage() {
  page.value = 1
  return load()
}

function onPageChange(p: number) {
  page.value = p
  load()
}

function onSizeChange(s: number) {
  size.value = s
  page.value = 1
  load()
}

async function onExport() {
  exporting.value = true
  try {
    // 三级账号只有本班，scope 传 class 即可；一级二级按当前筛选导出
    await exportScores({
      scope: 'class',
      classId: classId.value ?? classes.value[0]?.id,
      term: term.value || undefined,
    })
    ElMessage.success('已开始下载')
  } catch (e) {
    ElMessage.error((e as Error).message || '导出失败')
  } finally {
    exporting.value = false
  }
}

onMounted(async () => {
  try {
    const configs = await configApi.list()
    term.value = configs.find((c) => c.configKey === 'current_term')?.configValue ?? ''
  } catch {
    // 读不到配置就让后端用默认学期，不要让页面打不开
    term.value = ''
  }
  classes.value = await metaApi.classes()
  if (store.isViewer && classes.value.length === 1) {
    classId.value = classes.value[0].id
  }
  await load()
})
</script>

<style scoped>
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.footer-note {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}
</style>
