# Build stage
FROM node:22-alpine AS builder

WORKDIR /app

# Upgrade npm to v11. node:22-alpine ships npm 10, which cannot read the
# optional/platform-specific entries in this lockfile (esbuild prebuilds).
RUN npm install -g npm@11

# Copy package manifests
COPY package.json package-lock.json ./

# Install dependencies deterministically
RUN npm ci --legacy-peer-deps

# Copy source code
COPY . .

# Build the SPA (Vite only — tsc type-checking is enforced separately by CI)
RUN npx vite build

# Runtime stage: the unprivileged nginx image. It runs as uid 101 (user "nginx"), keeps its
# pid file and temp dirs under /tmp, and listens on 8080, because an unprivileged user cannot
# bind a port below 1024. docs/adr/0011-threat-model.md ("Container escape") promises a
# non-root user for every image; deploy/docker-compose.yml points Traefik at port 8080.
FROM nginxinc/nginx-unprivileged:1.31-alpine

# Take Alpine's security fixes that landed after the base tag was last rebuilt
# (step 28: Trivy found libexpat CVE-2026-93990, HIGH, fixed in 2.8.5-r0, while
# the newest nginx:1.31-alpine still shipped 2.8.4-r0). apk needs root, so switch
# for this one layer and back.
USER root
RUN apk upgrade --no-cache
USER 101

# Copy built SPA to nginx document root
COPY --from=builder /app/dist /usr/share/nginx/html

# Copy nginx configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Expose port 8080 (unprivileged; Traefik's trove-web service port label says the same)
EXPOSE 8080

# Healthcheck: verify the React root element is served.
# `localhost` resolves to `::1` first inside this image, but nginx.conf's
# `listen 8080;` is IPv4-only, so the probe must hit 127.0.0.1 directly (step
# 27 finding F1 — was: the container never reported healthy, ever, at any
# caller). scripts/load-stack.sh no longer needs its own compose-level
# healthcheck override for this now that the fix lives here.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/ | grep -q '<div id="root"' || exit 1
