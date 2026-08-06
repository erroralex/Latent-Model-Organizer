/**
 * The configuration engine for the Vite build system.
 *
 * This configuration defines the build and development environment for the
 * Latent Model Organizer frontend, including:
 * - Plugin Integration: Enables Vue 3 SFC support and DevTools.
 * - Development Proxy: Configures a proxy for /api requests to the Java backend (localhost:8080).
 * - Electron Compatibility: Enforces strictPort for consistent port allocation.
 * - Alias Resolution: Defines the @ alias for cleaner src directory paths.
 */

import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

export default defineConfig({
  base: './',
  plugins: [
    vue(),
    // vueDevTools(), // Disabled to remove "Toggle Vue Dev tools" overlay
  ],
  server: {
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
      }
    }
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/__tests__/**/*.spec.js'],
  },
})
