import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
  {
    path: '/',
    component: () => import('../layout/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', meta: { title: '工作台' }, component: () => import('../views/Dashboard.vue') },
      { path: 'seat', name: 'seat', meta: { title: '座次表' }, component: () => import('../views/Seat.vue') },
      { path: 'duty', name: 'duty', meta: { title: '值日表·班委' }, component: () => import('../views/Duty.vue') },
      { path: 'score', name: 'score', meta: { title: '成绩分析' }, component: () => import('../views/Score.vue') },
      { path: 'roster', name: 'roster', meta: { title: '花名册' }, component: () => import('../views/Roster.vue') },
      { path: 'parent', name: 'parent', meta: { title: '家长联系方式' }, component: () => import('../views/Parent.vue') },
      { path: 'timetable', name: 'timetable', meta: { title: '课程表' }, component: () => import('../views/Timetable.vue') },
      { path: 'todo', name: 'todo', meta: { title: '待办事项' }, component: () => import('../views/Todo.vue') },
      { path: 'daily', name: 'daily', meta: { title: '班级日常' }, component: () => import('../views/Daily.vue') }
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
