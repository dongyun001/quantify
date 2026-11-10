import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '@/api'
import { clearToken, setToken } from '@/api/request'
import type { CurrentUser, Role } from '@/api/types'

export const useUserStore = defineStore('user', () => {
  const user = ref<CurrentUser | null>(null)
  const loaded = ref(false)

  const role = computed<Role | null>(() => user.value?.role ?? null)

  /** 一级：全能，能审核、能录入、能开户 */
  const isAdmin = computed(() => role.value === 'ADMIN')
  /** 二级：能录入 */
  const isEditor = computed(() => role.value === 'EDITOR')
  /** 三级：只读 */
  const isViewer = computed(() => role.value === 'VIEWER')
  /** 能不能录入（一级和二级都可以）—— 和后台 Role.canWrite 保持一致 */
  const canWrite = computed(() => isAdmin.value || isEditor.value)

  async function login(username: string, password: string) {
    const vo = await authApi.login(username, password)
    setToken(vo.token)
    await fetchMe()
    return vo
  }

  async function fetchMe() {
    user.value = await authApi.me()
    loaded.value = true
    return user.value
  }

  async function logout() {
    try {
      await authApi.logout()
    } catch {
      // 后端失败也要清本地状态
    }
    clearToken()
    user.value = null
    loaded.value = false
  }

  return { user, loaded, role, isAdmin, isEditor, isViewer, canWrite, login, fetchMe, logout }
})
