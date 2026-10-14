import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

export default defineConfig({
  plugins: [uni()],
  server: {
    port: 5174,
    host: '127.0.0.1',
    proxy: {
      // H5 调试时把 /api 代理到后端；小程序端不走代理（用 manifest 里的 baseUrl）
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  }
})
