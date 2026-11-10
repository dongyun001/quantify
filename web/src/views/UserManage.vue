<template>
  <div class="page">
    <div class="page-title">账号管理</div>
    <div class="page-desc">
      账号数量不限，按需创建。角色决定能做什么，数据范围决定能看多大——两者自由组合。
    </div>

    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="用户名或姓名"
        clearable
        style="width: 180px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-select v-model="filterRole" placeholder="全部角色" clearable style="width: 140px" @change="load">
        <el-option label="一级 · 审核员" value="ADMIN" />
        <el-option label="二级 · 录入员" value="EDITOR" />
        <el-option label="三级 · 查看员" value="VIEWER" />
      </el-select>
      <el-select v-model="filterScope" placeholder="全部范围" clearable style="width: 130px" @change="load">
        <el-option label="全院" :value="1" />
        <el-option label="本年级" :value="2" />
        <el-option label="本班" :value="3" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>&nbsp;创建账号
      </el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe height="calc(100vh - 250px)">
      <el-table-column prop="username" label="登录名" width="140" />
      <el-table-column prop="realName" label="姓名" width="100" />
      <el-table-column label="角色" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="roleTag(row.role)">{{ row.roleText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="数据范围" width="150">
        <template #default="{ row }">
          {{ row.scopeName }}<span v-if="row.scopeDetail && row.scopeDetail !== '全院'">
            （{{ row.scopeDetail }}）</span>
        </template>
      </el-table-column>
      <el-table-column prop="deptName" label="部门标签" width="110">
        <template #default="{ row }">{{ row.deptName || '—' }}</template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
      <el-table-column label="密码" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.pwdChanged" size="small" type="success">已改</el-tag>
          <el-tag v-else size="small" type="warning">初始密码</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
            {{ row.status === 1 ? '正常' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastLoginAt" label="最后登录" width="160">
        <template #default="{ row }">{{ row.lastLoginAt || '从未登录' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="onReset(row)">重置密码</el-button>
          <el-button
            v-if="row.status === 1"
            link
            type="danger"
            :disabled="row.id === store.user?.userId"
            @click="onToggle(row, 0)"
          >禁用</el-button>
          <el-button v-else link type="success" @click="onToggle(row, 1)">启用</el-button>
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

    <!-- 创建账号 -->
    <el-dialog v-model="createVisible" title="创建账号" width="520px" @closed="resetCreate">
      <el-form ref="createRef" :model="createForm" :rules="createRules" label-width="100px">
        <el-form-item label="登录名" prop="username">
          <el-input v-model="createForm.username" placeholder="3~50 个字符，建好后不可修改" />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="createForm.realName" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-radio-group v-model="createForm.role">
            <el-radio-button value="ADMIN">一级 · 审核员</el-radio-button>
            <el-radio-button value="EDITOR">二级 · 录入员</el-radio-button>
            <el-radio-button value="VIEWER">三级 · 查看员</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="数据范围" prop="scopeType">
          <el-radio-group v-model="createForm.scopeType">
            <el-radio-button :value="1">全院</el-radio-button>
            <el-radio-button :value="2">本年级</el-radio-button>
            <el-radio-button :value="3">本班</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="createForm.scopeType === 2" label="选择年级">
          <el-select v-model="createForm.scopeId" style="width: 100%">
            <el-option v-for="g in grades" :key="g.id" :label="g.gradeName" :value="g.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="createForm.scopeType === 3" label="选择班级">
          <el-select v-model="createForm.scopeId" style="width: 100%">
            <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="部门标签">
          <el-select v-model="createForm.deptId" clearable placeholder="可不填，只是个标签" style="width: 100%">
            <el-option v-for="d in depts" :key="d.id" :label="d.deptName" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" placeholder="如：体育部干事" />
        </el-form-item>
        <el-form-item label="初始密码">
          <el-input v-model="createForm.password" placeholder="留空则系统随机生成 8 位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 密码只显示一次 -->
    <el-dialog v-model="pwdVisible" title="请立即保存密码" width="460px" :close-on-click-modal="false">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="这个密码只会显示这一次"
        description="系统不保存明文密码，关闭后无法找回，只能重新重置。请立即复制发给对方。"
      />
      <el-descriptions :column="1" border style="margin-top: 16px">
        <el-descriptions-item label="登录名">{{ pwdResult?.username }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ pwdResult?.realName }}</el-descriptions-item>
        <el-descriptions-item label="角色 / 范围">
          {{ pwdResult?.role }} / {{ pwdResult?.scopeName }}
        </el-descriptions-item>
        <el-descriptions-item label="密码">
          <span class="pwd">{{ pwdResult?.initialPassword }}</span>
          <el-button link type="primary" @click="copyPwd">复制</el-button>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button type="primary" @click="pwdVisible = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { metaApi, userApi } from '@/api'
import type { ClassMeta, CreateUserResult, Dept, Grade, UserRow } from '@/api/types'
import { useUserStore } from '@/stores/user'

const store = useUserStore()

const rows = ref<UserRow[]>([])
const classes = ref<ClassMeta[]>([])
const grades = ref<Grade[]>([])
const depts = ref<Dept[]>([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const size = ref(20)

const keyword = ref('')
const filterRole = ref<string | undefined>()
const filterScope = ref<number | undefined>()

const createVisible = ref(false)
const creating = ref(false)
const createRef = ref<FormInstance>()

const pwdVisible = ref(false)
const pwdResult = ref<CreateUserResult | null>(null)

const createForm = reactive({
  username: '',
  realName: '',
  role: 'EDITOR',
  scopeType: 1 as 1 | 2 | 3,
  scopeId: undefined as number | undefined,
  deptId: undefined as number | undefined,
  remark: '',
  password: '',
})

const createRules: FormRules = {
  username: [
    { required: true, message: '请输入登录名', trigger: 'blur' },
    { min: 3, max: 50, message: '长度需在 3~50 之间', trigger: 'blur' },
  ],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
  scopeType: [{ required: true, message: '请选择数据范围', trigger: 'change' }],
}

function roleTag(r: string) {
  return ({ ADMIN: 'danger', EDITOR: 'warning', VIEWER: 'info' } as const)[r as 'ADMIN'] ?? 'info'
}

async function load() {
  loading.value = true
  try {
    const r = await userApi.page({
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      role: filterRole.value,
      scopeType: filterScope.value,
    })
    rows.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

function openCreate() {
  createVisible.value = true
}

function resetCreate() {
  createForm.username = ''
  createForm.realName = ''
  createForm.role = 'EDITOR'
  createForm.scopeType = 1
  createForm.scopeId = undefined
  createForm.deptId = undefined
  createForm.remark = ''
  createForm.password = ''
  createRef.value?.clearValidate()
}

async function onCreate() {
  const ok = await createRef.value?.validate().catch(() => false)
  if (!ok) return
  if (createForm.scopeType !== 1 && !createForm.scopeId) {
    ElMessage.warning('请选择年级或班级')
    return
  }

  creating.value = true
  try {
    const r = await userApi.create({
      username: createForm.username,
      realName: createForm.realName,
      role: createForm.role,
      scopeType: createForm.scopeType,
      scopeId: createForm.scopeId,
      deptId: createForm.deptId,
      remark: createForm.remark,
      password: createForm.password || undefined,
    })
    createVisible.value = false
    pwdResult.value = r
    pwdVisible.value = true
    await load()
  } finally {
    creating.value = false
  }
}

async function onReset(row: UserRow) {
  await ElMessageBox.confirm(
    `为「${row.realName}」生成新密码？对方会被强制下线，下次登录要改密码。`,
    '重置密码',
    { type: 'warning' },
  )
  const r = await userApi.resetPassword(row.id)
  pwdResult.value = {
    id: r.userId,
    username: r.username,
    realName: row.realName,
    role: row.role,
    scopeName: row.scopeName,
    initialPassword: r.newPassword,
    passwordGenerated: r.passwordGenerated,
  }
  pwdVisible.value = true
  await load()
}

async function onToggle(row: UserRow, status: number) {
  const word = status === 1 ? '启用' : '禁用'
  await ElMessageBox.confirm(`确认${word}「${row.realName}」的账号？`, `${word}账号`, {
    type: 'warning',
  })
  await userApi.changeStatus(row.id, status)
  ElMessage.success(`已${word}`)
  await load()
}

async function copyPwd() {
  const pwd = pwdResult.value?.initialPassword ?? ''
  try {
    await navigator.clipboard.writeText(pwd)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

onMounted(async () => {
  const [c, g, d] = await Promise.all([metaApi.classes(), metaApi.grades(), metaApi.depts()])
  classes.value = c
  grades.value = g
  depts.value = d
  await load()
})
</script>

<style scoped>
.pwd {
  font-family: Consolas, Monaco, monospace;
  font-size: 17px;
  font-weight: 700;
  color: #f56c6c;
  letter-spacing: 1px;
  margin-right: 10px;
}
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
