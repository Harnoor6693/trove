/**
 * Build-time CSP compatibility gate (readiness gap P3-18).
 *
 * The deployed policy is `script-src 'self'` with no `'unsafe-inline'` and no
 * nonce plumbing (`deploy/traefik/dynamic/middlewares.yml`, `csp-web`). A single
 * inline `<script>` in the built shell would therefore be silently dropped by the
 * browser and the app would render a blank page — the kind of failure that only
 * shows up in production, because the dev server has no CSP at all.
 *
 * So it is checked here rather than trusted: any `<script>` with a body, any
 * `on*=` attribute, and any `javascript:` URL in the emitted HTML fails the build.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'

const DIST = new URL('../dist/', import.meta.url).pathname

function htmlFiles(dir) {
  const out = []
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) out.push(...htmlFiles(full))
    else if (entry.endsWith('.html')) out.push(full)
  }
  return out
}

const violations = []

for (const file of htmlFiles(DIST)) {
  const html = readFileSync(file, 'utf8')

  for (const match of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)) {
    if (match[2].trim().length > 0) {
      violations.push(`${file}: inline <script> body (${match[2].trim().slice(0, 60)}…)`)
    }
  }
  for (const match of html.matchAll(/\son[a-z]+\s*=/gi)) {
    violations.push(`${file}: inline event handler attribute (${match[0].trim()})`)
  }
  if (/javascript:/i.test(html)) {
    violations.push(`${file}: javascript: URL`)
  }
}

if (violations.length > 0) {
  process.stderr.write(
    'CSP check failed — the deployed policy is `script-src \'self\'`:\n' +
      violations.map((v) => `  - ${v}\n`).join('')
  )
  process.exit(1)
}

process.stdout.write('CSP check: no inline scripts, handlers or javascript: URLs in dist/\n')
