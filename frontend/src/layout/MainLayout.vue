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

      <div class="sidebar-foot" @click="logout">
        <el-icon><SwitchButton /></el-icon>
        <span v-show="!collapsed">退出登录</span>
      </div>
    </aside>

    <!-- 内容区 -->
    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const collapsed = ref(false)

const navs = [
  { path: '/dashboard', title: '工作台', icon: 'DataAnalysis', color: '#f06292', bg: '#fde4ee' },
  { path: '/seat', title: '座次表', icon: 'Grid', color: '#5b9bd5', bg: '#e3eefc' },
  { path: '/duty', title: '值日表·班委', icon: 'Brush', color: '#52b788', bg: '#e4f6ec' },
  { path: '/score', title: '成绩分析', icon: 'TrendCharts', color: '#9b6dd6', bg: '#f0e8fb' },
  { path: '/roster', title: '花名册', icon: 'Notebook', color: '#d9a52e', bg: '#fbf2d6' },
  { path: '/parent', title: '家长联系方式', icon: 'Phone', color: '#e08a4b', bg: '#fdeede' },
  { path: '/timetable', title: '课程表', icon: 'Calendar', color: '#f06292', bg: '#fde4ee' },
  { path: '/todo', title: '待办事项', icon: 'CircleCheck', color: '#43b581', bg: '#e4f6ec' }
]

function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('displayName')
  router.push('/login')
}
</script>

<style scoped>
.layout { display: flex; min-height: 100vh; }
.sidebar {
  width: 212px; flex-shrink: 0; background: #fff; padding: 18px 12px;
  display: flex; flex-direction: column; transition: width .2s;
  box-shadow: 2px 0 12px rgba(120,130,160,.06);
}
.sidebar.collapsed { width: 76px; }
.brand { display: flex; align-items: center; gap: 10px; padding: 4px 6px 14px; }
.logo {
  width: 44px; height: 44px; border-radius: 12px; flex-shrink: 0;
  background: linear-gradient(135deg, #f78fb3, #f4a8c6);
  color: #fff; font-size: 22px; font-weight: 800; display: flex; align-items: center; justify-content: center;
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
.sidebar-foot {
  display: flex; align-items: center; justify-content: center; gap: 6px;
  padding: 10px; color: #b0b3c0; cursor: pointer; font-size: 13px;
}
.sidebar-foot:hover { color: #f06292; }
.content { flex: 1; padding: 22px 26px; overflow: auto; }
</style>
