import type { IncomingMessage, ServerResponse } from 'node:http'
import { defineConfig, loadEnv, type Connect, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import * as dataApi from './api/data.ts'

/**
 * Serves /api/data from the Vercel Function in api/ during `vite` and
 * `vite preview`, so the app reaches MongoDB locally without `vercel dev`.
 */
function vercelApi(): Plugin {
  const handlers: Record<string, ((request: Request) => Promise<Response>) | undefined> = dataApi

  // MONGODB_URI comes from the environment, a .env file, or the file Atlas
  // generated (lowest priority). No VITE_ prefix: it never reaches the browser.
  function loadMongoEnv(mode: string) {
    Object.assign(process.env, loadEnv(mode, process.cwd(), 'MONGODB_'))
    try {
      process.loadEnvFile('atlas-credentials.env')
    } catch {
      // no Atlas file here
    }
  }

  async function handle(req: IncomingMessage, res: ServerResponse) {
    const handler = handlers[req.method ?? '']
    if (!handler) {
      res.statusCode = 405
      res.end()
      return
    }
    const headers = new Headers()
    for (const [name, value] of Object.entries(req.headers)) {
      if (value !== undefined) headers.set(name, Array.isArray(value) ? value.join(', ') : value)
    }
    const chunks: Buffer[] = []
    for await (const chunk of req) chunks.push(chunk as Buffer)
    const hasBody = req.method !== 'GET' && req.method !== 'HEAD'
    const response = await handler(
      // connect strips the mount path from req.url; only the query string matters here
      new Request(new URL(req.url ?? '/', 'http://localhost'), {
        method: req.method,
        headers,
        body: hasBody ? Buffer.concat(chunks) : undefined,
      }),
    )
    res.statusCode = response.status
    response.headers.forEach((value, name) => res.setHeader(name, value))
    res.end(Buffer.from(await response.arrayBuffer()))
  }

  const middleware: Connect.NextHandleFunction = (req, res, next) => {
    handle(req, res).catch(next)
  }

  return {
    name: 'vercel-api',
    configureServer(server) {
      loadMongoEnv(server.config.mode)
      server.middlewares.use('/api/data', middleware)
    },
    configurePreviewServer(server) {
      loadMongoEnv(server.config.mode)
      server.middlewares.use('/api/data', middleware)
    },
  }
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), vercelApi()],
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
