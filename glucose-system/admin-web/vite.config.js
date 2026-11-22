import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发代理到本地后端 8083;生产由 Nginx 反代
export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (/\/node_modules\/(?:vue|@vue|vue-router|pinia)\//.test(id)) return 'vue-runtime'
        }
      }
    }
  },
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8083',
        changeOrigin: true
      }
    }
  }
})
