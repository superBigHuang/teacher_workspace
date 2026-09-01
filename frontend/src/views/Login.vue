<template>
  <div class="login-page">
    <!-- 背景装饰糖果 -->
    <div class="candy c1">🍬</div>
    <div class="candy c2">🧁</div>
    <div class="candy c3">🍭</div>
    <div class="candy c4">🍓</div>
    <div class="candy c5">🍪</div>

    <div class="login-card">
      <div class="hello">
        <div class="avatar">🐻</div>
        <h2>班主任工作台</h2>
        <p class="sub">{{ tab === 'login' ? '欢迎回来，今天也要开心呀～' : '注册新账号，开始你的减负之旅' }}</p>
      </div>

      <div class="tabs">
        <div class="tab" :class="{ on: tab === 'login' }" @click="tab = 'login'">🔑 登录</div>
        <div class="tab" :class="{ on: tab === 'register' }" @click="tab = 'register'">✨ 注册</div>
      </div>

      <!-- 登录 -->
      <div v-if="tab === 'login'" class="form">
        <el-input v-model="loginForm.username" size="large" placeholder="账号" :prefix-icon="User" class="field" />
        <el-input v-model="loginForm.password" type="password" size="large" show-password
          placeholder="密码" :prefix-icon="Lock" class="field" @keyup.enter="login" />
        <el-button type="primary" size="large" class="enter" :loading="loading" @click="login">进入工作台 🚪</el-button>
        <p class="hint">默认管理员：admin / 123456</p>
      </div>

      <!-- 注册 -->
      <div v-else class="form">
        <el-input v-model="regForm.username" size="large" placeholder="账号（至少 3 个字符）" :prefix-icon="User" class="field" />
        <el-input v-model="regForm.password" type="password" size="large" show-password
          placeholder="密码（至少 6 位）" :prefix-icon="Lock" class="field" />
        <el-input v-model="regForm.confirm" type="password" size="large" show-password
          placeholder="确认密码" :prefix-icon="Lock" class="field" @keyup.enter="register" />
        <el-input v-model="regForm.inviteCode" size="large" placeholder="邀请码（选填）" :prefix-icon="Ticket" class="field" @keyup.enter="register" />
        <el-button type="primary" size="large" class="enter candy-btn" :loading="loading" @click="register">注册并进入 🎀</el-button>
        <p class="hint">注册后自动获得一套空的班级脚手架，数据仅你可见</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Ticket } from '@element-plus/icons-vue'
import http from '../api/http'

const router = useRouter()
const tab = ref('login')
const loading = ref(false)
const loginForm = reactive({ username: '', password: '' })
const regForm = reactive({ username: '', password: '', confirm: '', inviteCode: '' })

/** 保存登录态并跳转工作台 */
function saveAuth(data) {
  localStorage.setItem('token', data.token)
  localStorage.setItem('displayName', data.displayName)
  localStorage.setItem('username', data.username || '')
  router.push('/dashboard')
}

async function login() {
  if (!loginForm.username.trim()) return ElMessage.warning('请输入账号')
  if (!loginForm.password) return ElMessage.warning('请输入密码')
  loading.value = true
  try {
    const data = await http.post('/auth/login', {
      username: loginForm.username.trim(),
      password: loginForm.password
    })
    saveAuth(data)
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function register() {
  const username = regForm.username.trim()
  if (username.length < 3) return ElMessage.warning('账号至少 3 个字符')
  if (regForm.password.length < 6) return ElMessage.warning('密码至少 6 位')
  if (regForm.password !== regForm.confirm) return ElMessage.warning('两次输入的密码不一致')
  loading.value = true
  try {
    const data = await http.post('/auth/register', {
      username,
      password: regForm.password,
      inviteCode: regForm.inviteCode.trim()
    })
    ElMessage.success('注册成功，欢迎加入 🎉')
    saveAuth(data)
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  position: relative;
  height: 100vh; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #ffe3f0 0%, #fff3d6 35%, #dcf5e8 70%, #dde9ff 100%);
  overflow: hidden;
}
/* 飘浮糖果装饰 */
.candy { position: absolute; font-size: 40px; opacity: .5; animation: float 6s ease-in-out infinite; }
.c1 { top: 12%; left: 10%; animation-delay: 0s; }
.c2 { top: 20%; right: 12%; font-size: 52px; animation-delay: 1.2s; }
.c3 { bottom: 15%; left: 16%; font-size: 48px; animation-delay: 2s; }
.c4 { bottom: 22%; right: 18%; animation-delay: .6s; }
.c5 { top: 50%; left: 6%; font-size: 36px; animation-delay: 2.6s; }
@keyframes float {
  0%, 100% { transform: translateY(0) rotate(-6deg); }
  50% { transform: translateY(-18px) rotate(8deg); }
}

.login-card {
  position: relative; z-index: 1;
  background: rgba(255,255,255,.92); backdrop-filter: blur(6px);
  border-radius: 28px; padding: 36px 40px 30px; width: 380px;
  box-shadow: 0 20px 50px rgba(255,150,190,.22), 0 4px 14px rgba(180,190,220,.18);
  border: 3px solid #fff;
  text-align: center;
}
.hello .avatar {
  width: 72px; height: 72px; margin: 0 auto 8px; border-radius: 50%;
  background: linear-gradient(135deg, #ffd9e8, #ffe9c7);
  display: flex; align-items: center; justify-content: center; font-size: 40px;
  box-shadow: 0 6px 16px rgba(255,170,200,.45);
}
h2 { margin: 10px 0 4px; color: #6b5b73; font-size: 22px; letter-spacing: 1px; }
.sub { color: #b7a6b8; margin: 0 0 20px; font-size: 13px; }

.tabs {
  display: flex; background: #fdf1f6; border-radius: 16px; padding: 5px; margin-bottom: 20px;
}
.tab {
  flex: 1; text-align: center; padding: 9px 0; border-radius: 12px; cursor: pointer;
  font-size: 15px; font-weight: 700; color: #c29bb0; transition: all .2s;
}
.tab.on {
  background: linear-gradient(135deg, #ff9ec4, #ffb38a);
  color: #fff; box-shadow: 0 4px 12px rgba(255,150,180,.45);
}

.field { margin-bottom: 14px; }
.enter {
  width: 100%; font-size: 16px; font-weight: 800; height: 46px; border-radius: 14px;
  border: none; margin-top: 2px;
}
.candy-btn { background: linear-gradient(135deg, #8fd3b0, #7fb8f0); }
.hint { color: #cdb9c9; font-size: 12px; margin: 14px 0 0; }
</style>
