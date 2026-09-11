import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');

  // Local development proxy targets. The API Gateway is the single entry
  // point in production; in local dev we also proxy /api/auth directly to
  // the Auth Service so the OTP/login flow works without the gateway running.
  const apiGatewayTarget = env.VITE_API_GATEWAY_URL || 'http://localhost:8080';
  const authServiceTarget = env.VITE_AUTH_SERVICE_URL || 'http://localhost:8081';

  return {
    plugins: [react()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
        '@api': fileURLToPath(new URL('./src/api', import.meta.url)),
        '@stores': fileURLToPath(new URL('./src/stores', import.meta.url)),
        '@components': fileURLToPath(new URL('./src/components', import.meta.url)),
        '@features': fileURLToPath(new URL('./src/features', import.meta.url)),
        '@lib': fileURLToPath(new URL('./src/lib', import.meta.url)),
        '@config': fileURLToPath(new URL('./src/config', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      proxy: {
        // Route auth calls straight to the Auth Service in local dev.
        '/api/auth': {
          target: authServiceTarget,
          changeOrigin: true,
          // Proxy WebSocket upgrades too, not just plain HTTP.
          ws: true,
          // Strip only the "/api" mount point: the Auth Service serves
          // "/auth/register/otp", so the "/auth" segment must survive.
          rewrite: (path) => path.replace(/^\/api/, ''),
        },
        // All other API calls flow through the API Gateway.
        '/api': {
          target: apiGatewayTarget,
          changeOrigin: true,
          // The chat WebSocket and the location SSE stream both run through
          // here; without `ws` the upgrade handshake is answered with a 400.
          ws: true,
        },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: mode !== 'production',
      rollupOptions: {
        output: {
          // One 800 KB chunk means nothing renders until all of it is parsed.
          // Splitting the rarely-changing vendors keeps them cached across
          // deploys and lets the app shell arrive first.
          manualChunks: {
            react: ['react', 'react-dom', 'react-router-dom'],
            mui: ['@mui/material', '@emotion/react', '@emotion/styled'],
            icons: ['@mui/icons-material'],
            data: ['@tanstack/react-query', 'axios', 'zustand'],
            forms: ['react-hook-form', '@hookform/resolvers', 'zod'],
          },
        },
      },
    },
  };
});
