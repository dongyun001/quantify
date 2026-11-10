<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="title">量化积分管理系统</div>
      <div class="subtitle">软件工程学院</div>

      <!--
        回车提交不能挂在 <el-form> 上：Element Plus 不会把原生 keyup 事件转发到组件根节点，
        @keyup.enter 写在那里永远不会触发。挂到 <el-input> 上才行——ElInput 会把
        未识别的属性透传到内部的 <input>。
      -->
      <el-form ref="formRef" :model="form" :rules="rules">
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            placeholder="用户名"
            size="large"
            :prefix-icon="User"
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            show-password
            :prefix-icon="Lock"
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-button type="primary" size="large" class="submit" :loading="loading" @click="onSubmit">
          登 录
        </el-button>
      </el-form>

      <div class="hint">
        账号由管理员创建。首次登录后需要修改初始密码。
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const store = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({ username: '', password: '' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function onSubmit() {
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return

  loading.value = true
  try {
    const vo = await store.login(form.username, form.password)

    // 初始密码未改：直接跳改密页，别的不让用
    if (!vo.pwdChanged) {
      ElMessage.warning('这是初始密码，请先修改')
      await router.push('/change-password')
      return
    }

    const redirect = (route.query.redirect as string) || '/board'
    await router.push(redirect)
    ElMessage.success(`欢迎，${vo.realName}`)
  } catch (e) {
    // 错误提示已经在 axios 拦截器里统一弹过了
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2d3d 0%, #2c4a6b 100%);
}

.login-card {
  width: 380px;
  padding: 8px 4px;
}

.title {
  font-size: 20px;
  font-weight: 600;
  text-align: center;
}

.subtitle {
  font-size: 13px;
  color: #909399;
  text-align: center;
  margin: 6px 0 26px;
}

.submit {
  width: 100%;
  margin-top: 4px;
}

.hint {
  margin-top: 18px;
  font-size: 12px;
  color: #a8abb2;
  text-align: center;
  line-height: 1.6;
}
</style>
