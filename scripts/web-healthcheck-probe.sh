#!/usr/bin/env bash
#
# web-healthcheck-probe.sh — direct Docker probe for step-23 finding F1: the
# apps/web image's own HEALTHCHECK (not a compose override) reported unhealthy
# forever because `wget http://localhost/` resolves `localhost` to `::1`
# inside the (IPv6-less-listening) nginx container. Builds the image fresh,
# runs it standalone (no compose, no Traefik), and reads Docker's own health
# verdict via `docker inspect`.
#
# Usage: scripts/web-healthcheck-probe.sh [name]
#   name (default trove-web-healthcheck-probe) is both the image tag, rebuilt
#   fresh each run, and the throwaway container's name (sanitised to Docker's
#   container-name charset), so a caller that namespaces its containers
#   passes one argument and gets both.
#
# Exits 0 and prints "PASS" if the container reaches "healthy" before the
# HEALTHCHECK's own start-period + retries*interval elapse; exits 1 and
# prints "FAIL" (with the last few health-log entries) otherwise.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tag="${1:-trove-web-healthcheck-probe}"
container="${tag//[^a-zA-Z0-9_.-]/-}"

cleanup() { docker rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "building ${tag} from apps/web/Dockerfile..."
docker build -t "$tag" "${REPO_ROOT}/apps/web" || { echo "FAIL — image build failed"; exit 1; }

cleanup
docker run -d --name "$container" -p 0:80 "$tag" >/dev/null

# HEALTHCHECK is --interval=30s --timeout=3s --start-period=5s --retries=3:
# give it start-period + retries*interval + slack before giving up.
deadline=$((SECONDS + 5 + 3 * 30 + 15))
status=""
while [ "$SECONDS" -lt "$deadline" ]; do
  status=$(docker inspect --format '{{.State.Health.Status}}' "$container" 2>/dev/null || echo "")
  echo "  $(date +%H:%M:%S) health status: ${status:-<none>}"
  [ "$status" = "healthy" ] && break
  [ "$status" = "unhealthy" ] && break
  sleep 3
done

if [ "$status" = "healthy" ]; then
  echo "PASS — ${container} reported healthy"
  exit 0
else
  echo "FAIL — ${container} reported '${status:-<timed out>}', not healthy"
  echo "--- last health log entries ---"
  docker inspect --format '{{range .State.Health.Log}}{{.ExitCode}}: {{.Output}}{{end}}' "$container" 2>/dev/null | tail -20
  exit 1
fi
