import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 前端 5173，/api 代理到后端 8088
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8088', changeOrigin: true }
    }
  }
})
