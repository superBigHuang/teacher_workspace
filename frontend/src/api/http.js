import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const http = axios.create({ baseURL: '/api', timeout: 30000 })

// 请求带上 token
http.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = 'Bearer ' + token
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
    return Promise.reject(new Error(body.msg || '请求失败'))
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
