import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import legacy from '@vitejs/plugin-legacy' // <--- ADDED THIS IMPORT
import path from 'path'
import { fileURLToPath } from 'url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

export default defineConfig({
  plugins: [
    react(),
    legacy({
      // Targets older WebViews (Android 7.0+ standard)
      targets: ['defaults', 'not IE 11', 'chrome >= 49'],
      additionalLegacyPolyfills: ['regenerator-runtime/runtime']
    })
  ],
  base: './',
  build: {
    target: 'es2015',
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@components': path.resolve(__dirname, './src/components'),
      '@pages': path.resolve(__dirname, './src/pages'),
      '@services': path.resolve(__dirname, './src/services'),
      '@utils': path.resolve(__dirname, './src/utils'),
    },
  }
})