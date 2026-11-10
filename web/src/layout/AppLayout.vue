<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="brand">
        <div class="brand-mark">积</div>
        <div>
          <div class="brand-title">量化积分</div>
          <div class="brand-sub">软件工程学院</div>
        </div>
      </div>
      <el-menu
        :default-active="activePath"
        class="menu"
        router
      >
        <el-menu-item v-for="m in visibleMenus" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="crumb">{{ currentTitle }}</div>
        <div class="spacer" />
        <div class="who">
          <el-tag :type="roleTagType" size="small" effect="dark">{{ roleText }}</el-tag>
          <span class="name">{{ store.user?.realName }}</span>
          <el-tag size="small" type="info">{{ scopeText }}</el-tag>
        </div>
        <el-button link type="danger" @click="onLogout">退出</el-button>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { menusForRole } from '@/layout/menu'

const store = useUserStore()
const route = useRoute()
const router = useRouter()

const visibleMenus = computed(() => menusForRole(store.role))
const activePath = computed(() => '/' + (route.path.split('/')[1] || 'board'))
const currentTitle = computed(() => (route.meta.title as string) || '')

const roleText = computed(() => {
  switch (store.role) {
    case 'ADMIN': return '一级 · 审核员'
    case 'EDITOR': return '二级 · 录入员'
    case 'VIEWER': return '三级 · 查看员'
    default: return ''
  }
})

const roleTagType = computed(() => {
  switch (store.role) {
    case 'ADMIN': return 'danger'
    case 'EDITOR': return 'warning'
    default: return 'info'
  }
})

/** 数据范围：全院 / 本年级 / 本班，把具体班级名也带上更直观 */
const scopeText = computed(() => {
  const u = store.user
  if (!u) return ''
  if (u.scopeType === 3) return u.className || '本班'
  if (u.scopeType === 2) return u.gradeName || '本年级'
  return `全院（${u.classCount} 个班）`
})

async function onLogout() {
  await ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' })
  await store.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout { height: 100vh; }

.aside {
  background: #fff;
  border-right: 1px solid #f2f3f5;
  display: flex;
  flex-direction: column;
}

.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 16px 16px 14px;
}
.brand-mark {
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  border-radius: 2px;
  background: #165dff;
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 500;
}
.brand-title {
  color: #1d2129;
  font-size: 15px;
  font-weight: 500;
  letter-spacing: 0.2px;
}
.brand-sub {
  color: #86909c;
  font-size: 12px;
  margin-top: 1px;
}

.menu {
  border-right: none;
  background: transparent;
  flex: 1;
  padding: 2px 8px;
}
.menu :deep(.el-menu-item) {
  height: 38px;
  line-height: 38px;
  margin: 1px 0;
  border-radius: 2px;
  color: #4e5969;
  font-size: 14px;
}
.menu :deep(.el-menu-item:hover) {
  background: #f7f8fa;
  color: #1d2129;
}
.menu :deep(.el-menu-item.is-active) {
  background: #e8f3ff;
  color: #165dff;
  font-weight: 500;
}

.header {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 54px;
  padding: 0 20px;
  background: #fff;
  border-bottom: 1px solid #f2f3f5;
}
.header .spacer { flex: 1; }
.crumb { font-size: 15px; font-weight: 500; color: #1d2129; }
.who { display: flex; align-items: center; gap: 8px; }
.who .name { font-size: 14px; }

.main {
  padding: 0;
  background: #f7f8fa;
  overflow: auto;
}
</style>
