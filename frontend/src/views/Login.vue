<template>
  <div class="login-page">
    <div class="login-card">
      <div class="lock">🔒</div>
      <h2>请输入密码</h2>
      <p class="sub">进入班主任工作台</p>
      <el-input v-model="password" type="password" size="large" placeholder="请输入密码"
        class="pwd" @keyup.enter="login" :prefix-icon="Lock" />
      <el-button type="primary" size="large" class="enter" :loading="loading" @click="login">进入</el-button>
      <p class="hint">默认密码 123456（登录后可修改）</p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Lock } from '@element-plus/icons-vue'
import http from '../api/http'

const router = useRouter()
const password = ref('')
const loading = ref(false)

async function login() {
  if (!password.value) return
  loading.value = true
  try {
    const data = await http.post('/auth/login', { password: password.value })
    localStorage.setItem('token', data.token)
    localStorage.setItem('displayName', data.displayName)
    router.push('/dashboard')
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #fce4ec 0%, #e3eefc 50%, #e4f6ec 100%);
}
.login-card {
  background: #fff; border-radius: 20px; padding: 42px 46px; width: 360px;
  box-shadow: 0 16px 48px rgba(150,160,190,.25); text-align: center;
}
.lock { font-size: 52px; }
h2 { margin: 14px 0 4px; color: #4a4a55; }
.sub { color: #b0b3c0; margin: 0 0 24px; font-size: 14px; }
.pwd { margin-bottom: 16px; }
.enter { width: 100%; font-size: 17px; font-weight: 700; height: 44px; }
.hint { color: #c0c4d0; font-size: 12px; margin: 16px 0 0; }
</style>
