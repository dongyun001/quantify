import type { Role } from '@/api/types'

export interface MenuItem {
  path: string
  title: string
  icon: string
  /** 允许看到这个菜单的角色。空表示所有登录用户都能看 */
  roles?: Role[]
}

/**
 * 侧边栏菜单。
 *
 * 按角色渲染只是为了"不显示不该点的按钮"——真正的防线在后端。
 * 三级（VIEWER）登录后看不到「录入申请」「待审」「账号管理」三个入口。
 */
export const menus: MenuItem[] = [
  { path: '/board', title: '积分总表', icon: 'Histogram' },
  { path: '/appeals', title: '我的申诉', icon: 'ChatDotRound', roles: ['VIEWER'] },
  { path: '/entry', title: '录入申请', icon: 'EditPen', roles: ['ADMIN', 'EDITOR'] },
  { path: '/import', title: '批量导入', icon: 'Upload', roles: ['ADMIN', 'EDITOR'] },
  { path: '/review', title: '待审', icon: 'Checked', roles: ['ADMIN'] },
  { path: '/appeal-review', title: '申诉审批', icon: 'Stamp', roles: ['ADMIN'] },
  { path: '/classes', title: '班级管理', icon: 'School', roles: ['ADMIN'] },
  { path: '/students', title: '学生管理', icon: 'Avatar', roles: ['ADMIN'] },
  { path: '/users', title: '账号管理', icon: 'UserFilled', roles: ['ADMIN'] },
  { path: '/logs', title: '操作日志', icon: 'Document', roles: ['ADMIN'] },
  { path: '/account', title: '我的账号', icon: 'Setting' },
]

export function menusForRole(role: Role | null): MenuItem[] {
  if (!role) return []
  return menus.filter((m) => !m.roles || m.roles.includes(role))
}
