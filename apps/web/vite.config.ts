import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': resolve(import.meta.dirname, 'src'),
      '@components': resolve(import.meta.dirname, 'src/components'),
      '@hooks': resolve(import.meta.dirname, 'src/hooks'),
      '@pages': resolve(import.meta.dirname, 'src/pages'),
      '@stores': resolve(import.meta.dirname, 'src/stores'),
      '@api': resolve(import.meta.dirname, 'src/api'),
      '@styles': resolve(import.meta.dirname, 'src/styles'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    // CSP compatibility (readiness gap P3-18). The deployed policy is
    // `script-src 'self'` with no 'unsafe-inline' and no nonce plumbing, so every
    // script must be a separate, content-hashed file. Vite does this by default;
    // it is written out explicitly because a change here breaks the page silently
    // in production and nowhere else. `npm run build` runs
    // scripts/check-csp-compat.mjs afterwards to prove it.
    rolldownOptions: {
      output: {
        entryFileNames: 'assets/[name]-[hash].js',
        chunkFileNames: 'assets/[name]-[hash].js',
        assetFileNames: 'assets/[name]-[hash][extname]',
      },
    },
  },
  test: {
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    globals: true,
    environment: 'jsdom',
    environmentOptions: {
      jsdom: {
        url: 'http://localhost',
      },
    },
    setupFiles: ['src/test/setup.ts'],
    css: {
      modules: {
        classNameStrategy: 'non-scoped',
      },
    },
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      include: ['src/**'],
      exclude: [
        'src/api/generated/**',
        'src/test/**',
        'src/main.tsx',
        'src/App.tsx',
        'src/router/index.tsx',
        'src/pages/**',
        'scripts/**',
        '**/*.d.ts',
      ],
      // Floor thresholds at current baseline (Phase 16). Raise to 80% in Phase 17
      // once Vitest unit tests are added for the 13 src/components/ui/Ql* components.
      thresholds: {
        statements: 50,
        branches: 20,
        functions: 50,
        lines: 50,
      },
    },
  },
})
