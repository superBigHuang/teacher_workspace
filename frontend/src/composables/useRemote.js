import { ref } from 'vue'
import http from '../api/http'
import { setRemoteActive } from './usePets'

/** SSE 遥控连接状态（模板可显示"遥控已连接"） */
export const remoteConnected = ref(false)

/** 指令处理函数集合（各页面订阅：Seat 处理 pets.changed，MainLayout 处理 navigate、evolve、spotlight） */
const handlers = new Set()

/** 订阅遥控指令；返回注销函数（组件卸载时调用） */
export function onRemoteCmd(fn) {
  handlers.add(fn)
  return () => handlers.delete(fn)
}

let es = null
let reconnectTimer = null
let stopped = false

function teardown() {
  if (es) { es.close(); es = null }
}

function markOffline() {
  remoteConnected.value = false
  setRemoteActive(false)
}

/**
 * 启动遥控通道：先用 JWT 换 60 秒一次性 ticket，再建 EventSource 长连。
 * EventSource 原生重连会复用旧 URL（ticket 已失效），故 onerror 时关闭旧连接、
 * 重新换票建连；断线期间 pending 进化回到"自动补播"旧行为（setRemoteActive(false)）。
 */
export async function startRemote() {
  stopped = false
  if (es) return
  if (!localStorage.getItem('token')) return
  try {
    const data = await http.post('/remote/ticket')
    if (stopped || !localStorage.getItem('token')) return
    const source = new EventSource(`/api/remote/events?ticket=${data.ticket}`)
    es = source
    source.addEventListener('cmd', (e) => {
      let msg
      try { msg = JSON.parse(e.data) } catch { return }
      if (msg.action === 'connected') {
        remoteConnected.value = true
        setRemoteActive(true)
        return
      }
      handlers.forEach(fn => {
        try { fn(msg.action, msg.payload || {}) } catch (err) { /* 单个处理器异常不影响其他端 */ }
      })
    })
    source.onerror = () => {
      teardown()
      markOffline()
      if (!stopped && localStorage.getItem('token')) {
        reconnectTimer = setTimeout(startRemote, 3000)
      }
    }
  } catch (e) {
    markOffline()
    if (!stopped && localStorage.getItem('token')) {
      reconnectTimer = setTimeout(startRemote, 5000)
    }
  }
}

/** 关闭遥控通道（退出登录时调用） */
export function stopRemote() {
  stopped = true
  clearTimeout(reconnectTimer)
  teardown()
  markOffline()
}

/** 发送遥控指令（手机端调用）：广播给同账号所有在线连接（PC 投影/其他手机） */
export function sendCmd(action, payload) {
  return http.post('/remote/cmd', { action, payload })
}
