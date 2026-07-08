import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// 개발: /api → Spring(8087) 프록시. 빌드: server 정적 리소스로 출력(단일 배포물)
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/api': 'http://localhost:8087',
    },
  },
  build: {
    outDir: '../server/src/main/resources/static',
    emptyOutDir: true,
  },
})
