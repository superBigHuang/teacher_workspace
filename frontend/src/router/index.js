import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
  // 打印页：独立路由不套侧边栏布局，A4 定向，数据由生成页写入 sessionStorage
  { path: '/print/report', name: 'printReport', meta: { title: '家长成绩通知单' }, component: () => import('../views/ScoreReport.vue') },
  { path: '/print/comments', name: 'printComments', meta: { title: '期末评语册' }, component: () => import('../views/CommentBook.vue') },
  {
    path: '/',
    component: () => import('../layout/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', meta: { title: '工作台' }, component: () => import('../views/Dashboard.vue') },
      { path: 'seat', name: 'seat', meta: { title: '座次表' }, component: () => import('../views/Seat.vue') },
      { path: 'duty', name: 'duty', meta: { title: '值日表·班委' }, component: () => import('../views/Duty.vue') },
      { path: 'pet', name: 'pet', meta: { title: '班级宠物园' }, component: () => import('../views/PetGarden.vue') },
      { path: 'score', name: 'score', meta: { title: '成绩分析' }, component: () => import('../views/Score.vue') },
      { path: 'comment', name: 'comment', meta: { title: '期末评语' }, component: () => import('../views/Comment.vue') },
      { path: 'roster', name: 'roster', meta: { title: '花名册' }, component: () => import('../views/Roster.vue') },
      { path: 'parent', name: 'parent', meta: { title: '家长联系方式' }, component: () => import('../views/Parent.vue') },
      { path: 'timetable', name: 'timetable', meta: { title: '课程表' }, component: () => import('../views/Timetable.vue') },
      { path: 'todo', name: 'todo', meta: { title: '待办事项' }, component: () => import('../views/Todo.vue') },
      { path: 'daily', name: 'daily', meta: { title: '班级日常' }, component: () => import('../views/Daily.vue') },
      { path: 'settings', name: 'settings', meta: { title: '个人设置' }, component: () => import('../views/Settings.vue') }
    ]
  }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (to.path !== '/login' && !token) return '/login'
  if (to.path === '/login' && token) return '/dashboard'
})

export default router
