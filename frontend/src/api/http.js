import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

/**
 * 判定当前是否属于测试/演示环境：
 * 1. 访问域名包含 demo 或 dev（如 demo.yourdomain.com / dev.yourdomain.com）
 * 2. URL 查询参数显式指定 ?env=demo（本地 localhost:5173 联调测试库）
 * 3. 本地存储持久化标记 force_env === 'demo'
 */
export function checkIsDemo() {
  const host = window.location.hostname.toLowerCase()
  if (host.includes('demo') || host.includes('dev')) return true
  const params = new URLSearchParams(window.location.search)
  const env = params.get('env')
  if (env === 'demo' || env === 'dev') {
    localStorage.setItem('force_env', 'demo')
    return true
  }
  if (env === 'master' || env === 'prod') {
    localStorage.removeItem('force_env')
    return false
  }
  return localStorage.getItem('force_env') === 'demo'
}

const http = axios.create({ baseURL: '/api', timeout: 30000 })

// 请求带上 token 及动态切库数据源请求头
http.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = 'Bearer ' + token
  config.headers['X-Datasource'] = checkIsDemo() ? 'demo' : 'master'
  return config
})

// 响应统一处理：code=200 返回 data，否则报错
http.interceptors.response.use(
  resp => {
    // 文件下载直接返回原始响应
    if (resp.config.responseType === 'blob') return resp
    const body = resp.data
    if (body.code === 200) return body.data
    ElMessage.error(body.msg || '请求失败')
    // 业务失败：把 code/data 挂到 Error 上，调用方可读（如登录失败的 needCaptcha）
    const e = new Error(body.msg || '请求失败')
    e.code = body.code
    e.data = body.data
    return Promise.reject(e)
  },
  err => {
    if (err.response && err.response.status === 401) {
      localStorage.removeItem('token')
      router.push('/login')
      ElMessage.error('请先登录')
    } else {
      ElMessage.error(err.message || '网络异常')
    }
    return Promise.reject(err)
  }
)

export default http
