<template>
  <div class="page">
    <div class="page-title">班级管理</div>
    <div class="page-desc">
      仅一级可用。停用班级不会删除数据，历史流水仍按原班级归属；
      停用后本班账号将看不到数据。
    </div>

    <div class="toolbar">
      <el-select v-model="filterGradeId" placeholder="全部年级" clearable style="width: 140px" @change="load">
        <el-option v-for="g in grades" :key="g.id" :label="g.gradeName" :value="g.id" />
      </el-select>
      <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width: 120px" @change="load">
        <el-option label="启用" :value="1" />
        <el-option label="停用" :value="0" />
      </el-select>
      <el-input
        v-model="keyword"
        placeholder="班级名搜索"
        clearable
        style="width: 180px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-button @click="load">查询</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">新增班级</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="gradeName" label="年级" width="100" />
      <el-table-column prop="classNo" label="班号" width="80" align="center" />
      <el-table-column prop="name" label="班级" min-width="160" />
      <el-table-column prop="studentCount" label="在册学生" width="100" align="right" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.status === 1" link type="warning" @click="disable(row)">停用</el-button>
          <el-button v-else link type="success" @click="enable(row)">启用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑班级' : '新增班级'" width="460px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="年级" prop="gradeId">
          <el-select
            v-model="form.gradeId"
            :disabled="!!editingId"
            placeholder="请选择年级"
            style="width: 100%"
          >
            <el-option v-for="g in grades" :key="g.id" :label="g.gradeName" :value="g.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="班号" prop="classNo">
          <el-input-number v-model="form.classNo" :min="1" :max="99" :precision="0" />
        </el-form-item>
        <el-form-item label="班级名">
          <el-input v-model="form.name" maxlength="50" placeholder="留空按「年级+班号+班」生成" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { classApi, metaApi } from '@/api'
import type { ClassItem, Grade } from '@/api/types'

const rows = ref<ClassItem[]>([])
const grades = ref<Grade[]>([])
const loading = ref(false)

const filterGradeId = ref<number | undefined>()
const filterStatus = ref<number | undefined>()
const keyword = ref('')

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const form = reactive<{ gradeId: number; classNo: number; name: string }>({
  gradeId: 0,
  classNo: 1,
  name: '',
})

const rules: FormRules = {
  gradeId: [{ required: true, message: '请选择年级', trigger: 'change' }],
  classNo: [{ required: true, message: '请填写班号', trigger: 'blur' }],
}

async function load() {
  loading.value = true
  try {
    rows.value = await classApi.list({
      gradeId: filterGradeId.value,
      status: filterStatus.value,
      keyword: keyword.value || undefined,
    })
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  form.gradeId = 0
  form.classNo = 1
  form.name = ''
  dialogVisible.value = true
}

function openEdit(row: ClassItem) {
  editingId.value = row.id
  form.gradeId = row.gradeId
  form.classNo = row.classNo
  form.name = row.name
  dialogVisible.value = true
}

async function save() {
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return
  saving.value = true
  try {
    if (editingId.value) {
      await classApi.update(editingId.value, {
        classNo: form.classNo,
        name: form.name || undefined,
      })
      ElMessage.success('已保存')
    } else {
      await classApi.create({
        gradeId: form.gradeId,
        classNo: form.classNo,
        name: form.name || undefined,
      })
      ElMessage.success('已新增')
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function disable(row: ClassItem) {
  const cascade = row.studentCount > 0
  const text = cascade
    ? `该班级还有 ${row.studentCount} 名在册学生。停用后本班账号将看不到数据，是否同时把这些学生置为离班？`
    : `确定停用「${row.name}」吗？`
  await ElMessageBox.confirm(text, '停用班级', { type: 'warning' })
  const r = await classApi.changeStatus(row.id, 0, cascade)
  ElMessage.success(
    cascade ? `已停用，${r.affectedStudents} 名学生已置为离班` : '已停用',
  )
  await load()
}

async function enable(row: ClassItem) {
  await ElMessageBox.confirm(`确定启用「${row.name}」吗？`, '启用班级', { type: 'warning' })
  await classApi.changeStatus(row.id, 1)
  ElMessage.success('已启用')
  await load()
}

onMounted(async () => {
  grades.value = await metaApi.grades()
  await load()
})
</script>
