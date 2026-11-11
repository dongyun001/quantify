import type { AuditLog } from '@/api/types'

/** 审计快照字段 → 中文名。只翻译"人要看"的字段，id/时间戳之类的噪音直接不显示。 */
const FIELD_LABEL: Record<string, string> = {
  studentName: '学生', studentNo: '学号', className: '班级',
  deptName: '部门', term: '学期', category: '类型', dimension: '五育',
  points: '分值', reason: '事由', occurredOn: '日期', status: '状态',
  batchNo: '批次', username: '用户名', realName: '姓名', role: '角色',
  scopeType: '数据范围', scopeId: '范围对象', remark: '备注',
  changeType: '变更类型', rejectReason: '驳回理由', appealReason: '申诉理由',
  reviewRemark: '处理意见', originId: '原记录', supersededBy: '新记录',
}

/** CREATE 时按这个顺序展示，没值的跳过 */
const CREATE_FIELDS = [
  'studentName', 'studentNo', 'className', 'dimension', 'category', 'points',
  'reason', 'occurredOn', 'term', 'status',
  'username', 'realName', 'role', 'scopeType', 'remark',
]

const DIFF_FIELDS = [
  'studentName', 'className', 'dimension', 'category', 'points', 'reason',
  'occurredOn', 'term', 'status', 'deptName', 'batchNo',
  'username', 'realName', 'role', 'scopeType', 'remark', 'changeType',
]

/** 这些字段单独当「原因」展示，不再重复出现在细节里 */
const REASON_KEYS = ['rejectReason', 'appealReason', 'reviewRemark']

/** 各动作的"原因"取自哪个快照字段（顺序即优先级） */
const REASON_SOURCE: Record<string, string[]> = {
  REJECT: ['rejectReason'],
  RETURN: ['rejectReason'],
  SUBMIT: ['remark'],
  ADMIN_REVOKE: ['reason', 'remark'],
  ADMIN_CHANGE: ['reason', 'remark'],
  APPEAL_CREATE: ['appealReason'],
  APPEAL_REJECT: ['reviewRemark'],
  DISABLE: ['remark'],
}

const STATUS_TEXT: Record<number, string> = {
  0: '草稿', 1: '待审', 2: '已通过', 3: '已驳回', 4: '已撤销', 5: '已失效',
}
/** 申诉表的状态是另一套：0 待处理 / 1 已受理 / 2 已驳回 / 3 已撤回 */
const APPEAL_STATUS_TEXT: Record<number, string> = {
  0: '待处理', 1: '已受理', 2: '已驳回', 3: '已撤回',
}
const DIM_TEXT: Record<number, string> = { 1: '德', 2: '智', 3: '体', 4: '美', 5: '劳' }
const ROLE_TEXT: Record<string, string> = {
  ADMIN: '一级（管理员）', EDITOR: '二级（录入员）', VIEWER: '三级（查看员）',
}
const SCOPE_TEXT: Record<number, string> = { 1: '全院', 2: '本年级', 3: '本班' }

const TARGET_TEXT: Record<string, string> = {
  SCORE_ITEM: '积分流水', USER: '账号', SCORE_APPEAL: '申诉',
  CLASS: '班级', STUDENT: '学生',
}

const ACTION_TEXT: Record<string, string> = {
  LOGIN: '登录系统', LOGOUT: '退出登录', LOGIN_FAIL: '登录失败',
  CREATE: '新增', UPDATE: '修改', SUBMIT: '提交审批', APPROVE: '审批通过',
  REJECT: '驳回', REVOKE: '撤回', CHANGE: '发起变更', EXPORT: '导出 Excel',
  RESET_PASSWORD: '重置密码', ENABLE: '启用账号', DISABLE: '禁用账号',
  CHANGE_PASSWORD: '修改密码', ADMIN_CHANGE: '管理员修正', ADMIN_REVOKE: '管理员撤回',
  RETURN: '打回重做', BATCH_IMPORT: '批量导入', DINGTALK_NOTIFY: '钉钉通知',
  APPEAL_CREATE: '提交申诉', APPEAL_ACCEPT: '受理申诉', APPEAL_REJECT: '驳回申诉',
  APPEAL_WITHDRAW: '撤回申诉',
}

function parse(raw: string | null): Record<string, unknown> | null {
  if (!raw) return null
  try {
    const v = JSON.parse(raw)
    return v && typeof v === 'object' ? (v as Record<string, unknown>) : null
  } catch {
    return null
  }
}

function fmt(key: string, v: unknown, targetType: string): string {
  if (v === null || v === undefined || v === '') return '—'
  if (key === 'status') {
    const map = targetType === 'SCORE_APPEAL' ? APPEAL_STATUS_TEXT : STATUS_TEXT
    return map[Number(v)] ?? String(v)
  }
  if (key === 'category') return Number(v) === 2 ? '扣分' : '加分'
  if (key === 'dimension') return DIM_TEXT[Number(v)] ?? '未分类'
  if (key === 'role') return ROLE_TEXT[String(v)] ?? String(v)
  if (key === 'scopeType') return SCOPE_TEXT[Number(v)] ?? String(v)
  if (key === 'points') return `${v} 分`
  if (key === 'changeType') return Number(v) === 1 ? '分值更正' : '类别更正'
  return String(v)
}

export interface AuditDesc {
  /** 一句话说清这条日志干了什么 */
  text: string
  /** 这次操作的「原因」，从快照里取：驳回理由 / 申诉理由 / 处理意见 / 撤回原因 */
  reason?: string
  /** 需要展开看的细节，如「状态：待审 → 已通过」 */
  details: string[]
}

function pickReason(action: string, snaps: Array<Record<string, unknown> | null>): string | undefined {
  const keys = REASON_SOURCE[action]
  if (!keys) return undefined
  for (const snap of snaps) {
    if (!snap) continue
    for (const k of keys) {
      const v = snap[k]
      if (typeof v === 'string' && v.trim()) return v.trim()
    }
  }
  return undefined
}

export function describeAudit(row: AuditLog): AuditDesc {
  const before = parse(row.beforeJson)
  const after = parse(row.afterJson)
  const action = ACTION_TEXT[row.action] ?? row.action
  const target = TARGET_TEXT[row.targetType] ?? row.targetType
  const details: string[] = []
  const reason = pickReason(row.action, [after, before])

  // 登录类：没有业务快照，最多带个用户名
  if (row.action === 'LOGIN_FAIL') {
    const name = after?.username ? `：${after.username}` : ''
    return { text: `登录失败${name}`, details }
  }
  if (row.action === 'LOGIN' || row.action === 'LOGOUT') {
    return { text: action, details }
  }
  if (row.action === 'EXPORT') {
    const theTerm = after?.term ? `，学期 ${after.term}` : ''
    const cnt = after?.classes ? `，${after.classes} 个班` : ''
    return { text: `导出 Excel${theTerm}${cnt}`, details }
  }
  if (row.action === 'RESET_PASSWORD' || row.action === 'CHANGE_PASSWORD') {
    return { text: `${action}${target === '账号' ? `（${target} #${row.targetId}）` : ''}`, details }
  }

  // 新增：把关键字段按固定顺序铺开
  if (row.action === 'CREATE' && after) {
    const parts: string[] = []
    for (const k of CREATE_FIELDS) {
      const v = after[k]
      if (k === 'studentName') {
        // 后端已按快照里的 studentId 回填姓名/学号；都没有才退化成 #id
        const who = row.studentName || v
        const no = row.studentNo || after.studentNo
        if (who) parts.push(`学生 ${who}${no ? `（${no}）` : ''}`)
        else if (after.studentId) parts.push(`学生 #${after.studentId}`)
        continue
      }
      if (v === null || v === undefined || v === '') continue
      if (k === 'reason') { parts.push(`事由「${v}」`); continue }
      if (k === 'points') { parts.push(`${after.category === 2 ? '扣' : '加'} ${v} 分`); continue }
      if (k === 'category') continue // 已并入分值那句
      if (k === 'dimension') { parts.push(`${DIM_TEXT[Number(v)] ?? '未分类'}育`); continue }
      parts.push(`${FIELD_LABEL[k] ?? k} ${fmt(k, v, row.targetType)}`)
    }
    const head = target === '账号' ? '创建账号' : `新增${target}`
    return { text: `${head}：${parts.join('，') || '（无字段）'}`, reason, details }
  }

  // 其余动作：只列变化字段
  if (before && after) {
    for (const k of DIFF_FIELDS) {
      if (!(k in before) && !(k in after)) continue
      const a = before[k]
      const b = after[k]
      if (JSON.stringify(a) === JSON.stringify(b)) continue
      details.push(`${FIELD_LABEL[k] ?? k}：${fmt(k, a, row.targetType)} → ${fmt(k, b, row.targetType)}`)
    }
  } else if (after) {
    for (const k of DIFF_FIELDS) {
      if (after[k] === null || after[k] === undefined || after[k] === '') continue
      details.push(`${FIELD_LABEL[k] ?? k}：${fmt(k, after[k], row.targetType)}`)
    }
  }

  // 原因类字段已单独展示，细节里去掉，避免同一句话出现两遍
  const cleaned = details.filter((d) => !REASON_KEYS.some((k) => d.startsWith(`${FIELD_LABEL[k]}：`)))

  const suffix = cleaned.length ? '' : '（无字段变化）'
  const who = row.studentName
    ? ` — ${row.studentName}${row.studentNo ? `（${row.studentNo}）` : ''}${row.className ? ` · ${row.className}` : ''}`
    : ''
  return { text: `${action}${target ? `（${target} #${row.targetId}）` : ''}${who}${suffix}`, reason, details: cleaned }
}

/** 对象列：把 SCORE_ITEM#343 说成「积分流水 #343」 */
export function targetText(row: AuditLog): string {
  const t = TARGET_TEXT[row.targetType] ?? row.targetType
  return row.targetId ? `${t} #${row.targetId}` : t
}
