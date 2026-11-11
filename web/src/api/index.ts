import request, { getToken } from './request'
import type {
  AdminChangeForm, AppealReviewForm, AppealRow, BatchImportItem, BatchImportResult,
  BatchRevokeResult, ClassItem, ClassMeta, CreateAppealForm, CreateUserResult, CurrentUser, Dept,
  Grade, ImportPreview, LoginVO, MyApplyRow, PageResult, PendingRow, ScoreBoardRow,
  ScoreItem, Student, StudentItem, UserRow,
} from './types'

// ---------------------------------------------------------------- 认证

export const authApi = {
  login: (username: string, password: string) =>
    request.post<never, LoginVO>('/auth/login', { username, password }),
  logout: () => request.post('/auth/logout'),
  me: () => request.get<never, CurrentUser>('/auth/me'),
  changePassword: (oldPassword: string, newPassword: string) =>
    request.post('/auth/password', { oldPassword, newPassword }),
}

// ---------------------------------------------------------------- 学生

export interface StudentForm {
  studentNo?: string
  name?: string
  classId?: number
  baseScore?: number
  status?: number
}

export const studentApi = {
  page: (params: {
    page?: number; size?: number; keyword?: string
    classId?: number; gradeId?: number; status?: number
  }) => request.get<never, PageResult<StudentItem>>('/students', { params }),
  search: (keyword: string, limit = 20) =>
    request.get<never, Student[]>('/students/search', { params: { keyword, limit } }),
  detail: (id: number) => request.get<never, Student>(`/students/${id}`),
  create: (data: StudentForm) => request.post<never, number>('/students', data),
  update: (id: number, data: StudentForm) => request.put(`/students/${id}`, data),
  changeStatus: (id: number, status: number) =>
    request.post(`/students/${id}/status`, { status }),
}

// ---------------------------------------------------------------- 班级管理（ADMIN）

export const classApi = {
  list: (params: { gradeId?: number; status?: number; keyword?: string }) =>
    request.get<never, ClassItem[]>('/classes', { params }),
  create: (data: { gradeId: number; classNo: number; name?: string }) =>
    request.post<never, number>('/classes', data),
  update: (id: number, data: { classNo?: number; name?: string }) =>
    request.put(`/classes/${id}`, data),
  changeStatus: (id: number, status: number, cascadeStudents = false) =>
    request.post<never, { id: number; status: number; affectedStudents: number }>(
      `/classes/${id}/status`, { status, cascadeStudents }),
}

// ---------------------------------------------------------------- 积分

export interface ScoreItemForm {
  studentId: number
  dimension: number
  category: number
  points: number
  reason: string
  occurredOn?: string | null
  term?: string
  evidenceUrl?: string | null
  /** 仅一级有效；二级传了会被忽略 */
  effectNow?: boolean
}

export interface ScoreSearchRow {
  id: number
  studentNo: string
  studentName: string
  className: string
  term: string
  category: number
  dimension: number | null
  points: number
  reason: string
  occurredOn: string | null
  status: number
  deptName?: string | null
  reviewerName?: string | null
}

export interface ScoreSearchResult {
  total: number
  page: number
  size: number
  summary: { count: number; addPoints: number; deductPoints: number }
  list: ScoreSearchRow[]
}

export const scoreApi = {
  board: (params: {
    term?: string; keyword?: string; classId?: number; gradeId?: number
    page?: number; size?: number
  }) => request.get<never, PageResult<ScoreBoardRow>>('/scores/board', { params }),

  /** 按事由模糊搜索（Only ADMIN/EDITOR）。不限学期、不限状态，只排除逻辑删除 */
  searchByReason: (params: { q: string; page?: number; size?: number }) =>
    request.get<never, ScoreSearchResult>('/scores/search', { params }),

  summary: (studentId: number, term?: string) =>
    request.get<never, ScoreBoardRow>(`/scores/summary/${studentId}`, { params: { term } }),

  studentItems: (studentId: number, params: {
    term?: string; category?: number; dimension?: number; status?: number
  }) => request.get<never, ScoreItem[]>(`/scores/student/${studentId}`, { params }),

  itemDetail: (id: number) => request.get<never, ScoreItem>(`/scores/${id}`),

  create: (data: ScoreItemForm) => request.post<never, {
    id: number; status: number; statusText: string
    effectNow: boolean; duplicateWarning: string | null
  }>('/scores', data),

  update: (id: number, data: ScoreItemForm) => request.put(`/scores/${id}`, data),

  submit: (id: number) => request.post(`/scores/${id}/submit`),

  revoke: (id: number) => request.post(`/scores/${id}/revoke`),

  change: (id: number, data: {
    changeType: number
    dimension?: number
    category?: number
    points?: number
    reason?: string
    occurredOn?: string
    changeReason: string
    effectNow?: boolean
  }) => request.post<never, {
    newId: number; originId: number; status: number; statusText: string; effectNow: boolean
  }>(`/scores/${id}/change`, data),

  mine: (params: { status?: number; page?: number; size?: number }) =>
    request.get<never, { total: number; page: number; size: number; list: MyApplyRow[] }>(
      '/scores/mine', { params }),

  // ---- 批量导入 ----

  importTemplate: () => downloadImportTemplate(),

  /** 上传并逐行预览，不落库。大文件解析可能较慢，单独放宽超时 */
  importPreview: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request.post<never, ImportPreview>('/scores/import/preview', form, { timeout: 120000 })
  },

  batchImport: (data: { items: BatchImportItem[]; submitNow?: boolean; effectNow?: boolean }) =>
    request.post<never, BatchImportResult>('/scores/batch', data),

  revokeBatch: (batchNo: string) =>
    request.post<never, BatchRevokeResult>(
      `/scores/batch/${encodeURIComponent(batchNo)}/revoke`),

  // ---- 一级管理动作（ADMIN） ----

  /** 打回重做：待审 → 已驳回，理由必填 */
  returnForRedo: (id: number, reason: string) =>
    request.post(`/scores/${id}/return`, { reason }),

  /** 管理员撤回：待审 → 已撤销，理由可选 */
  adminRevoke: (id: number, reason?: string) =>
    request.post(`/scores/${id}/admin-revoke`, { reason: reason ?? null }),

  /** 管理员修正已通过记录：走变更链，不直接改原记录 */
  adminChange: (id: number, data: AdminChangeForm) =>
    request.post<never, {
      newId: number; originId: number; status: number; statusText: string
    }>(`/scores/${id}/admin-change`, data),
}

/**
 * 下载批量导入模板。
 *
 * 和导出一样：成功后端返回二进制流，出错返回 JSON，所以要按 Content-Type 区分。
 */
export async function downloadImportTemplate(): Promise<void> {
  const resp = await request.get('/scores/import/template', {
    responseType: 'blob',
    headers: { satoken: getToken() ?? '' },
  }) as unknown as { data: Blob; headers: Record<string, string> }

  const blob = resp.data
  const ctype = resp.headers['content-type'] ?? ''

  if (ctype.includes('application/json')) {
    const text = await blob.text()
    const body = JSON.parse(text)
    throw new Error(body.msg || '下载模板失败')
  }

  let filename = '批量导入模板.xlsx'
  const cd = resp.headers['content-disposition']
  if (cd) {
    const m = /filename\*=UTF-8''([^;]+)/i.exec(cd)
    if (m) {
      filename = decodeURIComponent(m[1])
    }
  }

  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

// ---------------------------------------------------------------- 审批

export const reviewApi = {
  pending: (params: {
    deptId?: number; classId?: number; category?: number; dimension?: number
    changeOnly?: boolean; page?: number; size?: number
  }) => request.get<never, { total: number; page: number; size: number; list: PendingRow[] }>(
    '/reviews/pending', { params }),

  approve: (id: number) => request.post(`/reviews/${id}/approve`),

  reject: (id: number, rejectReason: string) =>
    request.post(`/reviews/${id}/reject`, { rejectReason }),
}

// ---------------------------------------------------------------- 申诉

export const appealApi = {
  /** 三级提交申诉（仅已通过记录） */
  create: (data: CreateAppealForm) =>
    request.post<never, { id: number; status: number; statusText: string }>('/appeals', data),

  /** 我的申诉 */
  mine: (params: { status?: number; page?: number; size?: number }) =>
    request.get<never, PageResult<AppealRow>>('/appeals/mine', { params }),

  /** 一级待处理列表（按数据范围） */
  pending: (params: { page?: number; size?: number }) =>
    request.get<never, PageResult<AppealRow>>('/appeals/pending', { params }),

  detail: (id: number) => request.get<never, AppealRow>(`/appeals/${id}`),

  /** 受理：必须带更正内容，后端同一事务走变更链 */
  accept: (id: number, data: AppealReviewForm) =>
    request.post<never, {
      appealId: number; status: number; statusText: string
      newId: number; originId: number
    }>(`/appeals/${id}/accept`, data),

  reject: (id: number, reviewRemark: string) =>
    request.post(`/appeals/${id}/reject`, { reviewRemark }),

  withdraw: (id: number) => request.post(`/appeals/${id}/withdraw`),

  /** 钉钉发送失败后手动重发；未配置 webhook 时后端返回 502 */
  notifyRetry: (id: number) =>
    request.post<never, {
      notifyStatus: number; notifyStatusText: string; notifyError: string | null
    }>(`/appeals/${id}/notify-retry`),
}

// ---------------------------------------------------------------- 账号管理

export const userApi = {
  page: (params: {
    page?: number; size?: number; keyword?: string
    role?: string; scopeType?: number; status?: number
  }) => request.get<never, PageResult<UserRow>>('/users', { params }),

  create: (data: {
    username: string; realName: string; role: string
    scopeType: number; scopeId?: number
    password?: string; deptId?: number; remark?: string
  }) => request.post<never, CreateUserResult>('/users', data),

  update: (id: number, data: Record<string, unknown>) => request.put(`/users/${id}`, data),

  resetPassword: (id: number, newPassword?: string) =>
    request.post<never, {
      userId: number; username: string; newPassword: string; passwordGenerated: boolean
    }>(`/users/${id}/reset-password`, { newPassword }),

  changeStatus: (id: number, status: number) =>
    request.post(`/users/${id}/status`, { status }),
}

// ---------------------------------------------------------------- 元数据

export const metaApi = {
  classes: (gradeId?: number) =>
    request.get<never, ClassMeta[]>('/meta/classes', { params: { gradeId } }),
  grades: () => request.get<never, Grade[]>('/meta/grades'),
  depts: () => request.get<never, Dept[]>('/meta/depts'),
}

// ---------------------------------------------------------------- 系统配置

export interface SysConfigItem {
  id: number
  classId: number
  configKey: string
  configValue: string
  remark: string | null
  updatedBy: number | null
  updatedAt: string
}

export const configApi = {
  /** 读全局配置（当前学期等）；所有登录用户可读 */
  list: () => request.get<never, SysConfigItem[]>('/configs'),
}

// ---------------------------------------------------------------- 导出

/**
 * 导出 Excel。
 *
 * 成功后端返回二进制流，出错返回 JSON。
 * 所以不能用普通的 request 方法，要手动处理两种情况。
 */
export async function exportScores(params: {
  scope?: 'class' | 'grade' | 'college'
  classId?: number
  gradeId?: number
  term?: string
}): Promise<void> {
  const resp = await request.get('/export/scores', {
    params,
    responseType: 'blob',
    // 让后端把 Content-Disposition 暴露出来，否则读不到文件名
    headers: { satoken: getToken() ?? '' },
  }) as unknown as { data: Blob; headers: Record<string, string> }

  const blob = resp.data
  const ctype = resp.headers['content-type'] ?? ''

  // 后端出错时返回的是 JSON，转成文本读出来提示
  if (ctype.includes('application/json')) {
    const text = await blob.text()
    const body = JSON.parse(text)
    throw new Error(body.msg || '导出失败')
  }

  let filename = '量化积分.xlsx'
  const cd = resp.headers['content-disposition']
  if (cd) {
    const m = /filename\*=UTF-8''([^;]+)/i.exec(cd)
    if (m) {
      filename = decodeURIComponent(m[1])
    }
  }

  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
