<template>
  <div class="layout">
    <!-- 侧边栏 -->
    <aside class="sidebar" :class="{ collapsed }">
      <div class="brand">
        <div class="logo">师</div>
        <div v-show="!collapsed" class="brand-text">
          <div class="brand-title">班主任工作台</div>
          <div class="brand-sub">减负 · 不增负</div>
        </div>
      </div>

      <div class="collapse-btn" @click="collapsed = !collapsed">
        <el-icon><DArrowLeft v-if="!collapsed" /><DArrowRight v-else /></el-icon>
        <span v-show="!collapsed">收起</span>
      </div>

      <nav>
        <router-link v-for="item in navs" :key="item.path" :to="item.path"
          class="nav-item" :style="{ '--nav': item.color, '--navbg': item.bg }">
          <el-icon class="nav-icon"><component :is="item.icon" /></el-icon>
          <span v-show="!collapsed" class="nav-text">{{ item.title }}</span>
        </router-link>
      </nav>

      <!-- 底部用户区 -->
      <div class="user-area">
        <div class="user-card">
          <div class="user-avatar" title="点击设置头像" @click="avatarDlg = true">{{ avatar }}</div>
          <div v-show="!collapsed" class="user-info">
            <div class="user-name">{{ displayName }}</div>
            <div class="user-school">{{ school || '未设置学校' }}</div>
          </div>
        </div>
        <div class="user-actions">
          <router-link to="/settings" class="action-item" title="个人设置">
            <el-icon><Setting /></el-icon>
            <span v-show="!collapsed">个人设置</span>
          </router-link>
          <div class="action-item logout" @click="logout" title="退出登录">
            <el-icon><SwitchButton /></el-icon>
            <span v-show="!collapsed">退出登录</span>
          </div>
        </div>
      </div>
    </aside>

    <!-- 内容区 -->
    <main class="content">
      <router-view />
    </main>

    <!-- 头像选择弹窗 -->
    <el-dialog v-model="avatarDlg" title="选择头像" width="380px" align-center>
      <div class="avatar-grid">
        <button v-for="a in AVATARS" :key="a" class="avatar-opt"
                :class="{ active: avatar === a }" @click="pickAvatar(a)">{{ a }}</button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Setting } from '@element-plus/icons-vue'
import http from '../api/http'
import { startRemote, stopRemote, onRemoteCmd } from '../composables/useRemote'
import { playHeld } from '../composables/usePets'

/** 可选头像 emoji（动物/笑脸/职业，马卡龙风格） */
const AVATARS = [
  '🐻','🐰','🐱','🐶','🦊','🐼','🐨','🦁','🐯','🐸','🐵','🐷','🐮','🐧','🦄','🐢',
  '🐳','🦋','🐥','🦉','🐹','🐙','🦕','🐝','🦜','🐬','🦩','🐈','🐇','🦔',
  '😊','🤓','😎','🥰','🤗','👩‍🏫','👨‍🏫','🧑‍🏫','🌞','🌈','⭐','🍀'
]

const router = useRouter()
const collapsed = ref(false)
const displayName = ref(localStorage.getItem('displayName') || '老师')
const school = ref('')
const avatar = ref(localStorage.getItem('avatar') || '🐻')
const avatarDlg = ref(false)

/** 拉取当前用户信息（显示名/学校/头像），供侧边栏底部展示 */
onMounted(async () => {
  try {
    const me = await http.get('/auth/me')
    displayName.value = me.displayName || '老师'
    school.value = me.school || ''
    localStorage.setItem('displayName', displayName.value)
    const p = await http.get('/settings/profile')
    if (p.avatar) { avatar.value = p.avatar; localStorage.setItem('avatar', p.avatar) }
  } catch (e) { /* 未登录等情况忽略 */ }
  // 启动手机遥控 SSE 通道（投影场景：手机发指令，PC 执行）
  startRemote()
  offRemote = onRemoteCmd(handleRemoteCmd)
})

let offRemote = null
onUnmounted(() => { offRemote?.(); stopRemote() })

/**
 * 遥控指令处理（全局级）：
 * - navigate：PC 切到指定页面
 * - evolve.playAll / evolve.play：先切到座次表页（演出组件挂在该页），等渲染后播放待播进化
 * - spotlight：切到座次表并高亮目标学生座位 3 秒（.remote-spot 样式在 Seat.vue 定义）
 */
function handleRemoteCmd(action, payload) {
  if (action === 'navigate' && payload.path) {
    router.push(payload.path)
    return
  }
  if (action === 'evolve.playAll' || action === 'evolve.play' || action === 'spotlight') {
    const onSeat = router.currentRoute.value.path === '/seat'
    const run = () => {
      if (action === 'evolve.playAll') playHeld()
      else if (action === 'evolve.play') playHeld(payload.studentId)
      else if (action === 'spotlight') {
        const el = document.querySelector(`[data-sid="${payload.studentId}"]`)
        if (!el) return
        el.classList.add('remote-spot')
        setTimeout(() => el.classList.remove('remote-spot'), 3000)
      }
    }
    if (onSeat) run()
    else { router.push('/seat'); setTimeout(run, 600) }
  }
}

/** 选择头像：即时保存到个人设置并刷新侧边栏 */
async function pickAvatar(a) {
  avatar.value = a
  localStorage.setItem('avatar', a)
  avatarDlg.value = false
  try {
    await http.put('/settings/profile', { avatar: a })
    ElMessage.success('头像已更新')
  } catch (e) { /* 网络异常时本地已切换，下次进页面重试 */ }
}

const navs = [
  { path: '/dashboard', title: '工作台', icon: 'DataAnalysis', color: '#f06292', bg: '#fde4ee' },
  { path: '/seat', title: '座次表', icon: 'Grid', color: '#5b9bd5', bg: '#e3eefc' },
  { path: '/duty', title: '值日表·班委', icon: 'Brush', color: '#52b788', bg: '#e4f6ec' },
  { path: '/score', title: '成绩分析', icon: 'TrendCharts', color: '#9b6dd6', bg: '#f0e8fb' },
  { path: '/comment', title: '期末评语', icon: 'EditPen', color: '#e06aa8', bg: '#fde7f3' },
  { path: '/roster', title: '花名册', icon: 'Notebook', color: '#d9a52e', bg: '#fbf2d6' },
  { path: '/parent', title: '家长联系方式', icon: 'Phone', color: '#e08a4b', bg: '#fdeede' },
  { path: '/timetable', title: '课程表', icon: 'Calendar', color: '#f06292', bg: '#fde4ee' },
  { path: '/todo', title: '待办事项', icon: 'CircleCheck', color: '#43b581', bg: '#e4f6ec' }
]

function logout() {
  stopRemote()
  localStorage.removeItem('token')
  localStorage.removeItem('displayName')
  localStorage.removeItem('username')
  router.push('/login')
}
</script>

<style scoped>
.layout { display: flex; min-height: 100vh; }
.sidebar {
  width: 212px; flex-shrink: 0;
  background: linear-gradient(180deg, #fffdf8 0%, #fff5ef 100%);
  padding: 18px 12px;
  display: flex; flex-direction: column; transition: width .2s;
  box-shadow: 2px 0 16px rgba(220,170,190,.12);
  border-right: 3px solid #fff;
}
.sidebar.collapsed { width: 76px; }
.brand { display: flex; align-items: center; gap: 10px; padding: 4px 6px 14px; }
.logo {
  width: 46px; height: 46px; border-radius: 16px; flex-shrink: 0;
  background: linear-gradient(135deg, #ff9ec4, #ffb38a);
  color: #fff; font-size: 22px; font-weight: 800; display: flex; align-items: center; justify-content: center;
  box-shadow: 0 5px 14px rgba(255,160,190,.5);
}
.brand-title { font-size: 17px; font-weight: 800; color: #4a4a55; }
.brand-sub { font-size: 12px; color: #b0b3c0; margin-top: 2px; }
.collapse-btn {
  display: flex; align-items: center; justify-content: center; gap: 6px;
  background: #f2f4f9; border-radius: 10px; padding: 8px; color: #8a8fa3;
  cursor: pointer; font-size: 13px; margin-bottom: 12px;
}
nav { display: flex; flex-direction: column; gap: 8px; flex: 1; }
.nav-item {
  display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-radius: 12px;
  text-decoration: none; color: #6a6f82; font-size: 15px; font-weight: 600;
  transition: all .15s;
}
.nav-item .nav-icon { font-size: 18px; color: var(--nav); }
.nav-item:hover { background: var(--navbg); }
.nav-item.router-link-active {
  background: var(--navbg); color: var(--nav);
  box-shadow: inset 3px 0 0 var(--nav);
}
/* 底部用户区 */
.user-area { margin-top: 10px; }
.user-card {
  display: flex; align-items: center; gap: 10px; padding: 10px;
  background: linear-gradient(135deg, #fff0f6, #fff7e8);
  border-radius: 16px; margin-bottom: 8px;
}
.user-avatar {
  width: 40px; height: 40px; border-radius: 50%; flex-shrink: 0;
  background: #fff; display: flex; align-items: center; justify-content: center; font-size: 22px;
  box-shadow: 0 3px 8px rgba(255,170,200,.4);
  cursor: pointer; transition: transform .15s, box-shadow .15s;
}
.user-avatar:hover { transform: scale(1.12); box-shadow: 0 5px 14px rgba(255,150,190,.55); }

/* 头像选择弹窗 */
.avatar-grid { display: grid; grid-template-columns: repeat(8, 1fr); gap: 8px; }
.avatar-opt {
  border: 2px solid transparent; border-radius: 12px; background: #f7f8fc;
  font-size: 26px; padding: 6px 0; cursor: pointer; transition: transform .12s, background .12s;
}
.avatar-opt:hover { transform: scale(1.12); background: #fff0f6; }
.avatar-opt.active { border-color: #f78fb3; background: #fde4ee; box-shadow: 0 0 0 3px rgba(247,143,179,.25); }
.user-name { font-size: 14px; font-weight: 800; color: #6b5b73; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.user-school { font-size: 11px; color: #b7a6b8; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.user-actions { display: flex; flex-direction: column; gap: 4px; }
.action-item {
  display: flex; align-items: center; gap: 8px; padding: 9px 12px; border-radius: 12px;
  color: #9a8fa0; cursor: pointer; font-size: 13px; text-decoration: none; transition: all .15s;
}
.action-item:hover { background: #fdf1f6; color: #f06292; }
.action-item.logout:hover { background: #fdf1f1; color: #f07070; }
.sidebar.collapsed .user-card { justify-content: center; }
.sidebar.collapsed .action-item { justify-content: center; }
.content { flex: 1; padding: 22px 26px; overflow: auto; }
</style>
