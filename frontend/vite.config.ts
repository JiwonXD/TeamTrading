import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { viteSingleFile } from 'vite-plugin-singlefile'

export default defineConfig({
  plugins: [react(), viteSingleFile()],
  base: './',
  // VITE_BACKEND=server로 띄우면 같은 주소의 /api, /ws, 소셜 로그인 경로를 백엔드(8080)로 넘긴다
  server: {
    // E2E가 쓰는 결과 폴더가 바뀔 때 화면이 새로고침되지 않게 한다
    watch: { ignored: ['**/test-results/**', '**/playwright-report/**'] },
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
      // 소셜 로그인 시작과 콜백
      '/oauth2': 'http://localhost:8080',
      '/login/oauth2': 'http://localhost:8080',
    },
  },
})
