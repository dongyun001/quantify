import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { menusForRole } from '@/layout/menu'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/Login.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/change-password',
    name: 'changePassword',
    component: () => import('@/views/ChangePassword.vue'),
    meta: { title: '修改密码' },
  },
  {
    path: '/',
    component: () => import('@/layout/AppLayout.vue'),
    redirect: '/board',
    children: [
      {
        path: 'board',
        name: 'board',
        component: () => import('@/views/ScoreBoard.vue'),
        meta: { title: '积分总表' },
      },
      {
        path: 'search',
        name: 'reasonSearch',
        component: () => import('@/views/ReasonSearch.vue'),
        meta: { title: '事由搜索', roles: ['ADMIN', 'EDITOR'] },
      },
      {
        path: 'appeals',
        name: 'appeals',
        component: () => import('@/views/Appeals.vue'),
        meta: { title: '我的申诉', roles: ['VIEWER'] },
      },
      {
        path: 'student/:id',
        name: 'studentDetail',
        component: () => import('@/views/StudentDetail.vue'),
        meta: { title: '学生明细' },
      },
      {
        path: 'entry',
        name: 'entry',
        component: () => import('@/views/ScoreEntry.vue'),
        meta: { title: '录入申请', roles: ['ADMIN', 'EDITOR'] },
      },
      {
        path: 'import',
        name: 'import',
        component: () => import('@/views/ScoreImport.vue'),
        meta: { title: '批量导入', roles: ['ADMIN', 'EDITOR'] },
      },
      {
        path: 'review',
        name: 'review',
        component: () => import('@/views/Review.vue'),
        meta: { title: '待审', roles: ['ADMIN'] },
      },
      {
        path: 'appeal-review',
        name: 'appealReview',
        component: () => import('@/views/AppealReview.vue'),
        meta: { title: '申诉审批', roles: ['ADMIN'] },
      },
      {
        path: 'classes',
        name: 'classes',
        component: () => import('@/views/ClassManage.vue'),
        meta: { title: '班级管理', roles: ['ADMIN'] },
      },
      {
        path: 'students',
        name: 'students',
        component: () => import('@/views/StudentManage.vue'),
        meta: { title: '学生管理', roles: ['ADMIN'] },
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('@/views/UserManage.vue'),
        meta: { title: '账号管理', roles: ['ADMIN'] },
      },
      {
        path: 'logs',
        name: 'logs',
        component: () => import('@/views/AuditLogs.vue'),
        meta: { title: '操作日志', roles: ['ADMIN'] },
      },
      {
        path: 'account',
        name: 'account',
        component: () => import('@/views/ChangePassword.vue'),
        meta: { title: '我的账号' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'notFound',
    component: () => import('@/views/NotFound.vue'),
    meta: { public: true },
  },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

/**
 * 三层防御里的第二层（第一层是菜单过滤，第三层也是唯一真正有效的是后端校验）。
 *
 * 这里拦住的是"手输 URL"的情况。改了前端代码或直接调接口照样能绕过，
 * 所以后端必须独立校验一遍——绝不能指望这一层。
 */
router.beforeEach(async (to) => {
  const store = useUserStore()

  if (to.meta.public) {
    return true
  }

  if (!store.loaded) {
    try {
      await store.fetchMe()
    } catch {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  // 还在用初始密码：只放行改密页
  if (store.user && !store.user.pwdChanged && to.name !== 'changePassword') {
    return { path: '/change-password' }
  }

  const allow = to.meta.roles as string[] | undefined
  if (allow && store.role && !allow.includes(store.role)) {
    // 没权限就送回首页，不报错——用户可能只是手滑输了地址
    return { path: '/board' }
  }

  return true
})

router.afterEach((to) => {
  const t = (to.meta.title as string) || ''
  document.title = t ? `${t} · 量化积分管理系统` : '量化积分管理系统'
})

export { menusForRole }
export default router
