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

# Runtime stage
FROM nginx:1.31-alpine

# Take Alpine's security fixes that landed after the base tag was last rebuilt
# (step 28: Trivy found libexpat CVE-2026-93990, HIGH, fixed in 2.8.5-r0, while
# the newest nginx:1.31-alpine still shipped 2.8.4-r0).
RUN apk upgrade --no-cache

# Copy built SPA to nginx document root
COPY --from=builder /app/dist /usr/share/nginx/html

# Copy nginx configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Expose port 80
EXPOSE 80

# Healthcheck: verify the React root element is served.
# `localhost` resolves to `::1` first inside this image, but nginx.conf's
# `listen 80;` is IPv4-only, so the probe must hit 127.0.0.1 directly (step
# 27 finding F1 — was: the container never reported healthy, ever, at any
# caller). scripts/load-stack.sh no longer needs its own compose-level
# healthcheck override for this now that the fix lives here.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ | grep -q '<div id="root"' || exit 1
