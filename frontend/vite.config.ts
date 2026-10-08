import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The build goes straight into Spring Boot's static resources, so one jar serves UI and API.
// In dev, /api is proxied to the backend on :8080.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../backend/src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
