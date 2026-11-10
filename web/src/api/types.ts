// 与后端 docs/接口文档.md 一一对应

export type Role = 'ADMIN' | 'EDITOR' | 'VIEWER'

/** 统一响应体。HTTP 状态码一律 200，错误看 code */
export interface R<T = unknown> {
  code: number
  msg: string
  data: T
}

export interface PageResult<T> {
  total: number
  page: number
  size: number
  list: T[]
}

export interface LoginVO {
  token: string
  userId: number
  username: string
  realName: string
  role: Role
  deptId: number | null
  deptName: string | null
  pwdChanged: boolean
}

export interface CurrentUser {
  userId: number
  username: string
  realName: string
  role: Role
  deptId: number | null
  deptName: string | null
  remark: string | null
  scopeType: 1 | 2 | 3
  scopeName: string
  scopeId: number
  studentId: number | null
  classId: number | null
  className: string | null
  gradeId: number | null
  gradeName: string | null
  pwdChanged: boolean
  classCount: number
}

export interface Student {
  id: number
  classId: number
  studentNo: string
  name: string
  baseScore: number
  status: number
  className?: string
  gradeName?: string
}

/** 积分总表的一行 */
export interface ScoreBoardRow {
  rank: number
  studentId: number
  studentNo: string
  name: string
  classId: number
  className: string
  gradeName: string
  addScore: number
  /** 扣分是负数，与原表一致 */
  deductScore: number
  baseScore: number
  totalScore: number
  addRemark?: string
  deductRemark?: string
}

/** 一条积分流水 */
export interface ScoreItem {
  id: number
  classId: number
  deptId: number | null
  deptName?: string | null
  studentId: number
  studentNo?: string
  studentName?: string
  className?: string
  term: string
  category: 1 | 2
  /** 五育类别：1德 2智 3体 4美 5劳；null = 历史未分类 */
  dimension: number | null
  dimensionText: string | null
  points: number
  reason: string
  occurredOn: string | null
  evidenceUrl: string | null
  status: number
  rejectReason: string | null
  changeType: number
  originId: number | null
  supersededBy: number | null
  submittedBy: number | null
  submittedAt: string | null
  submitterName?: string
  reviewedBy: number | null
  reviewedAt: string | null
  reviewerName?: string
  createdAt: string
}

/** 「我的申请」列表项 —— 能不能改由后端算好，前端不重复判断 */
export interface MyApplyRow {
  id: number
  studentId: number
  studentNo: string
  studentName: string
  className: string
  category: number
  categoryText: string
  dimension: number | null
  dimensionText: string | null
  signedPoints: number
  reason: string
  occurredOn: string | null
  status: number
  statusText: string
  rejectReason: string | null
  changeType: number
  originId: number | null
  submittedAt: string | null
  canEdit: boolean
  canRevoke: boolean
}

/** 批量导入预览的一行 */
export interface ImportPreviewRow {
  rowNo: number
  studentNo: string
  studentName: string
  studentId: number | null
  className: string | null
  dimension: number | null
  dimensionText: string | null
  category: number | null
  points: number | null
  reason: string
  occurredOn: string | null
  /** 该行的错误，多个用「；」连接 */
  error: string | null
  /** 不阻断导入的警告 */
  warning: string | null
  /** 学号/姓名相关错误：预览页无法修正，需回 Excel 改 */
  identityInvalid: boolean
}

export interface ImportPreviewError {
  rowNo: number
  message: string
}

export interface ImportPreview {
  total: number
  validCount: number
  invalidCount: number
  canImport: boolean
  rows: ImportPreviewRow[]
  errors: ImportPreviewError[]
}

export interface BatchImportItem {
  studentId: number
  dimension: number
  category: number
  points: number
  reason: string
  occurredOn?: string | null
}

export interface BatchImportResult {
  batchNo: string
  created: number
  status: number
  statusText: string
}

export interface BatchRevokeResult {
  batchNo: string
  revoked: number
}

/** 待审列表项 */
export interface PendingRow {
  id: number
  studentId: number
  studentNo: string
  studentName: string
  classId: number
  className: string
  category: number
  categoryText: string
  dimension: number | null
  dimensionText: string | null
  signedPoints: number
  reason: string
  occurredOn: string | null
  deptId: number | null
  deptName: string | null
  submitterName: string | null
  submittedAt: string | null
  changeType: number
  changeTypeText: string | null
  originId: number | null
  evidenceUrl: string | null
  /** 自己提交的不能自己审 */
  canReview: boolean
}

/** 申诉列表项 / 详情（后端返回同一组字段） */
export interface AppealRow {
  id: number
  scoreItemId: number
  studentId: number
  studentNo: string
  studentName: string
  classId: number
  className: string
  term: string
  appealType: number
  appealTypeText: string
  appealReason: string
  evidenceUrl: string | null
  /** 0 待处理 / 1 已受理 / 2 已驳回 / 3 已撤回 */
  status: number
  statusText: string
  resultChangeId: number | null
  /** 0 未发 / 1 已发送 / 2 发送失败 */
  notifyStatus: number
  notifyStatusText: string
  notifyError: string | null
  reviewedBy: number | null
  reviewedByName: string | null
  reviewedAt: string | null
  reviewRemark: string | null
  createdBy: number
  createdByName: string
  createdAt: string
  scoreCategory: number | null
  scoreCategoryText: string
  scoreDimension: number | null
  scoreDimensionText: string | null
  scorePoints: number | null
  /** 带符号：扣分为负 */
  scoreSignedPoints: number
  scoreReason: string | null
  scoreOccurredOn: string | null
  scoreStatus: number | null
  scoreStatusText: string
}

export type AppealDetail = AppealRow

export interface CreateAppealForm {
  scoreItemId: number
  appealType: number
  appealReason: string
  evidenceUrl?: string | null
}

export interface AppealReviewForm {
  reviewRemark?: string
  changeType: number
  dimension?: number
  category?: number
  points?: number
  reason?: string
  occurredOn?: string | null
}

export interface AdminChangeForm {
  changeType: number
  dimension?: number
  category?: number
  points?: number
  reason?: string
  occurredOn?: string | null
  changeReason: string
}

export interface UserRow {
  id: number
  username: string
  realName: string
  role: Role
  roleText: string
  deptId: number | null
  deptName: string | null
  remark: string | null
  scopeType: 1 | 2 | 3
  scopeId: number
  scopeName: string
  scopeDetail: string
  status: number
  pwdChanged: boolean
  lastLoginAt: string | null
  createdAt: string
}

export interface CreateUserResult {
  id: number
  username: string
  realName: string
  role: Role
  scopeName: string
  /** 明文密码【唯一一次】出现的地方，刷新就没了 */
  initialPassword: string
  passwordGenerated: boolean
}

export interface ClassMeta {
  id: number
  name: string
  gradeId: number
  gradeName: string
  studentCount: number
}

/** 班级管理页的一行（含停用班与班号） */
export interface ClassItem {
  id: number
  name: string
  gradeId: number
  gradeName: string
  classNo: number
  status: number
  /** 在册学生数 */
  studentCount: number
}

/** 学生管理页的一行 */
export interface StudentItem {
  id: number
  classId: number
  studentNo: string
  name: string
  baseScore: number
  status: number
  className?: string
  gradeName?: string
}

export interface Dept {
  id: number
  deptName: string
  fullName: string | null
}

export interface Grade {
  id: number
  gradeName: string
  enrollYear: number
}

export interface AuditLog {
  id: number
  classId: number
  deptId: number | null
  deptName?: string | null
  targetType: string
  targetId: number
  action: string
  beforeJson: string | null
  afterJson: string | null
  operatorId: number
  operatorName?: string
  operatorIp: string | null
  createdAt: string
}
