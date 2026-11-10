<template>
  <div class="page">
    <div class="page-title">学生管理</div>
    <div class="page-desc">
      仅一级可用。转班只改学生所属班级，历史流水仍归原班级（新录入的分数归新班）；
      离班是逻辑标记，不删除记录。
    </div>

    <div class="toolbar">
      <el-select v-model="filterClassId" placeholder="全部班级" clearable filterable style="width: 160px" @change="load">
        <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-select v-model="filterStatus" placeholder="在册" clearable style="width: 120px" @change="load">
        <el-option label="在册" :value="1" />
        <el-option label="离班" :value="0" />
      </el-select>
      <el-input
        v-model="keyword"
        placeholder="学号或姓名"
        clearable
        style="width: 180px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-button @click="load">查询</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">新增学生</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="studentNo" label="学号" width="140" />
      <el-table-column prop="name" label="姓名" width="110" />
      <el-table-column prop="className" label="班级" min-width="140" />
      <el-table-column prop="baseScore" label="基础分" width="90" align="right" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
            {{ row.status === 1 ? '在册' : '离班' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.status === 1" link type="warning" @click="toggle(row)">离班</el-button>
          <el-button v-else link type="success" @click="toggle(row)">复学</el-button>
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

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑学生' : '新增学生'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="学号" prop="studentNo">
          <el-input
            v-model="form.studentNo"
            :disabled="!!editingId"
            maxlength="20"
            placeholder="如 25560641001"
          />
        </el-form-item>
        <el-form-item label="姓名" prop="name">
          <el-input v-model="form.name" maxlength="50" />
        </el-form-item>
        <el-form-item label="班级" prop="classId">
          <el-select v-model="form.classId" filterable placeholder="请选择班级" style="width: 100%">
            <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="基础分">
          <el-input-number v-model="form.baseScore" :min="0" :step="0.5" :precision="2" />
        </el-form-item>
        <el-form-item v-if="editingId" label="状态">
          <el-radio-group v-model="form.status">
            <el-radio-button :value="1">在册</el-radio-button>
            <el-radio-button :value="0">离班</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-alert
          v-if="editingId"
          type="info"
          :closable="false"
          show-icon
          title="学号不可修改"
          description="转班后历史流水仍归原班级，新录入的分数归新班。"
        />
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
import { metaApi, studentApi } from '@/api'
import type { ClassMeta, StudentItem } from '@/api/types'

const rows = ref<StudentItem[]>([])
const classes = ref<ClassMeta[]>([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const size = ref(20)

const filterClassId = ref<number | undefined>()
const filterStatus = ref<number | undefined>(1)
const keyword = ref('')

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const form = reactive<{
  studentNo: string
  name: string
  classId: number
  baseScore: number
  status: number
}>({
  studentNo: '',
  name: '',
  classId: 0,
  baseScore: 50,
  status: 1,
})

const rules: FormRules = {
  studentNo: [{ required: true, message: '请填写学号', trigger: 'blur' }],
  name: [{ required: true, message: '请填写姓名', trigger: 'blur' }],
  classId: [{ required: true, message: '请选择班级', trigger: 'change' }],
}

async function load() {
  loading.value = true
  try {
    const r = await studentApi.page({
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      classId: filterClassId.value,
      status: filterStatus.value,
    })
    rows.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  form.studentNo = ''
  form.name = ''
  form.classId = 0
  form.baseScore = 50
  form.status = 1
  dialogVisible.value = true
}

function openEdit(row: StudentItem) {
  editingId.value = row.id
  form.studentNo = row.studentNo
  form.name = row.name
  form.classId = row.classId
  form.baseScore = Number(row.baseScore)
  form.status = row.status
  dialogVisible.value = true
}

async function save() {
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return
  saving.value = true
  try {
    if (editingId.value) {
      await studentApi.update(editingId.value, {
        name: form.name,
        classId: form.classId,
        baseScore: form.baseScore,
        status: form.status,
      })
      ElMessage.success('已保存')
    } else {
      await studentApi.create({
        studentNo: form.studentNo,
        name: form.name,
        classId: form.classId,
        baseScore: form.baseScore,
      })
      ElMessage.success('已新增')
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggle(row: StudentItem) {
  const target = row.status === 1 ? 0 : 1
  await ElMessageBox.confirm(
    target === 0
      ? `确定把「${row.name}」置为离班吗？历史流水会保留。`
      : `确定让「${row.name}」复学吗？`,
    target === 0 ? '离班' : '复学',
    { type: 'warning' },
  )
  await studentApi.changeStatus(row.id, target)
  ElMessage.success(target === 0 ? '已离班' : '已复学')
  await load()
}

onMounted(async () => {
  classes.value = await metaApi.classes()
  await load()
})
</script>

<style scoped>
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
