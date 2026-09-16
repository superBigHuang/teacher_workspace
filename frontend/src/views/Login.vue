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
        <p class="sub">欢迎回来，今天也要开心呀～</p>
      </div>

      <!-- 登录（自助注册已下线，账号请联系管理员开通） -->
      <div class="form">
        <el-input v-model="loginForm.username" size="large" placeholder="账号" :prefix-icon="User" class="field" />
        <el-input v-model="loginForm.password" type="password" size="large" show-password
          placeholder="密码" :prefix-icon="Lock" class="field" @keyup.enter="login" />
        <!-- 连续登录失败 3 次后出现：算术图形验证码，点图可换一张 -->
        <div v-if="needCaptcha" class="captcha-row field">
          <img :src="captchaImg" class="captcha-img" title="看不清？点我换一张" @click="loadCaptcha">
          <el-input v-model="captchaCode" size="large" placeholder="验证码" class="captcha-input" @keyup.enter="login" />
        </div>
        <el-button type="primary" size="large" class="enter" :loading="loading" @click="login">进入工作台 🚪</el-button>
        <p class="hint">还没有账号？请联系管理员开通</p>
      </div>
    </div>

    <!-- ICP 备案号（工信部要求底部展示并链接至备案系统） -->
    <a class="beian" href="https://beian.miit.gov.cn/" target="_blank" rel="noopener">浙ICP备2026075470号</a>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import http from '../api/http'

const router = useRouter()
const loading = ref(false)
const loginForm = reactive({ username: '', password: '' })

// 验证码（失败 3 次后由后端 needCaptcha 拉起）
const needCaptcha = ref(false)
const captchaImg = ref('')
const captchaId = ref('')
const captchaCode = ref('')

/** 拉取一张算术验证码图（点击图片也可刷新） */
async function loadCaptcha() {
  const d = await http.get('/auth/captcha')
  captchaId.value = d.captchaId
  captchaImg.value = d.img
  captchaCode.value = ''
}

/** 保存登录态并跳转工作台 */
function saveAuth(data) {
  localStorage.setItem('token', data.token)
  localStorage.setItem('displayName', data.displayName)
  localStorage.setItem('username', data.username || '')
  localStorage.setItem('role', data.role || 'TEACHER')
  router.push('/dashboard')
}

async function login() {
  if (!loginForm.username.trim()) return ElMessage.warning('请输入账号')
  if (!loginForm.password) return ElMessage.warning('请输入密码')
  loading.value = true
  const payload = { username: loginForm.username.trim(), password: loginForm.password }
  if (needCaptcha.value) {
    payload.captchaId = captchaId.value
    payload.captchaCode = captchaCode.value.trim()
  }
  try {
    const data = await http.post('/auth/login', payload)
    needCaptcha.value = false
    saveAuth(data)
  } catch (e) {
    // 后端要求验证码（失败达阈值/验证码错误）：拉起并刷新验证码图
    if (e.data?.needCaptcha) {
      needCaptcha.value = true
      loadCaptcha()
    }
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

.field { margin-bottom: 14px; }
.captcha-row { display: flex; gap: 10px; align-items: center; }
.captcha-img {
  height: 40px; width: 118px; border-radius: 10px; cursor: pointer; flex-shrink: 0;
  border: 1px solid #f0dce8; background: #fff;
}
.captcha-input { flex: 1; }
.enter {
  width: 100%; font-size: 16px; font-weight: 800; height: 46px; border-radius: 14px;
  border: none; margin-top: 2px;
}
.hint { color: #cdb9c9; font-size: 12px; margin: 14px 0 0; }
.beian {
  position: absolute; bottom: 14px; left: 0; right: 0; z-index: 2;
  text-align: center; font-size: 12px; color: #b39db0; text-decoration: none;
}
.beian:hover { color: #f06292; }
</style>
