import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
    // Native fs-change events don't reliably propagate across the
    // devcontainer/WSL bind mount, so HMR silently serves stale files
    // without this — fall back to polling.
    watch: {
      usePolling: true,
      interval: 300,
    },
  },
})
