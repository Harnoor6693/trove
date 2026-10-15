#!/usr/bin/env bash
#
# load-stack.sh — stand up deploy/docker-compose.yml on one machine for a load or security run
# (collapse step 23), WITHOUT writing a single file into deploy/.
#
#   scripts/load-stack.sh up   <workdir>   generate throwaway secrets, render Traefik, build, start
#   scripts/load-stack.sh k6   <workdir>   run tests/performance/trove-load.js in Docker against it
#   scripts/load-stack.sh down <workdir>   stop and DELETE the stack's volumes (it is throwaway)
#
# ITS OWN COMPOSE PROJECT, NEVER A REAL INSTALL'S. deploy/docker-compose.yml says `name: trove`
# and deploy/trove defaults to the same project, so running this under `-p trove` on a host that
# carries Trove would recreate the live containers with throwaway secrets and `down -v` would
# delete the live database. This script therefore runs as TROVE_LOAD_PROJECT (default trove-load,
# `trove` itself is refused), writes <workdir>/.load-stack-project on `up`, and:
#   - `up` refuses while any container or volume of the deploy project (TROVE_PROJECT_NAME,
#     default trove) exists, while one of the compose file's fixed-name networks (trove_edge,
#     trove_data, ...) belongs to another project — compose only WARNS about that and joins it,
#     after which the two stacks share the `postgres` DNS name — or while the load project
#     already has resources that this <workdir> did not create;
#   - `down` refuses unless <workdir>/.load-stack-project names the load project.
#
# What it does differently from `deploy/trove up`, and why:
#   - .env, the rendered traefik.runtime.yml and a compose override live in <workdir>, so the
#     checked-in deploy/ directory is never touched (deploy/trove writes .env and the runtime file
#     next to the compose file).
#   - TLS is a self-signed certificate for TROVE_DOMAIN (default trove.test) served from a Traefik
#     file-provider entry, on the websecure entrypoint with `tls=true`. A local machine cannot
#     complete an ACME challenge, and `TROVE_TLS_MODE=off` would serve plain HTTP — which hides
#     HSTS (Traefik only sends it over TLS) and breaks the __Host- cookies (Secure). Everything
#     else is deploy/docker-compose.yml and deploy/traefik/dynamic/middlewares.yml as committed.
#   - Ports default to 18080/18443 so nothing on 80/443 is disturbed.
#   - Every secret is generated here with openssl, printed nowhere, and dies with `down`.
#
# Needs: docker (compose v2), openssl, awk; core/target/*-exec.jar already packaged
# (./mvnw --batch-mode -pl core -am clean package -DskipTests).

set -euo pipefail

REPO_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
COMPOSE_FILE="${REPO_ROOT}/deploy/docker-compose.yml"

cmd="${1:-}"
work="${2:-}"
[ -n "$cmd" ] && [ -n "$work" ] || { echo "usage: $0 up|k6|down <workdir>" >&2; exit 2; }
mkdir -p "$work"
work=$(cd -- "$work" && pwd)

domain="${TROVE_DOMAIN:-trove.test}"
https_port="${TROVE_HTTPS_PORT:-18443}"

project="${TROVE_LOAD_PROJECT:-trove-load}"
live_project="${TROVE_PROJECT_NAME:-trove}"
marker="${work}/.load-stack-project"
fixed_networks="trove_edge trove_data trove_ai trove_monitoring trove_socket_proxy"

refuse() { echo "load-stack: REFUSED — $*" >&2; exit 3; }
owned_by() { # project -> container and volume names carrying that compose project label
  { docker ps -aq --filter "label=com.docker.compose.project=$1"
    docker volume ls -q --filter "label=com.docker.compose.project=$1"; } 2>/dev/null
}
marker_matches() { [ -f "$marker" ] && [ "$(cat "$marker")" = "$project" ]; }

preflight_up() {
  [ "$project" != "$live_project" ] && [ "$project" != trove ] \
    || refuse "project '${project}' is the name a real install uses; set TROVE_LOAD_PROJECT to something else"
  [ -z "$(owned_by "$live_project")" ] \
    || refuse "compose project '${live_project}' (a Trove install, running or stopped) has containers or volumes on this host: $(owned_by "$live_project" | head -3 | tr '\n' ' ')"
  local net owner
  for net in $fixed_networks; do
    owner=$(docker network inspect -f '{{index .Labels "com.docker.compose.project"}}' "$net" 2>/dev/null) || continue
    [ "$owner" = "$project" ] \
      || refuse "network ${net} belongs to project '${owner:-none}'; compose would join it and the two stacks would share service DNS names (postgres, trove-core)"
  done
  [ -z "$(owned_by "$project")" ] || marker_matches \
    || refuse "project '${project}' already has containers or volumes that ${work} did not create (no matching ${marker})"
}

compose() {
  docker compose -p "$project" -f "$COMPOSE_FILE" -f "${work}/override.yml" --env-file "${work}/.env" "$@"
}

write_env() {
  [ -f "${work}/.env" ] && return 0
  umask 077
  cat > "${work}/.env" <<EOF
TROVE_DOMAIN=${domain}
ACME_EMAIL=acme@${domain}
TROVE_HTTP_PORT=${TROVE_HTTP_PORT:-18080}
TROVE_HTTPS_PORT=${https_port}
TROVE_ROUTER_ENTRYPOINT=websecure
TROVE_ROUTER_TLS_LABEL=tls=true
POSTGRES_PASSWORD=$(openssl rand -hex 24)
TROVE_JWT_SECRET=$(openssl rand -hex 32)
TROVE_ENCRYPTION_KEY=$(openssl rand -hex 32)
TROVE_ADMIN_EMAIL=admin@${domain}
TROVE_ADMIN_PASSWORD=Load-$(openssl rand -hex 12)
# Interpolated even though the monitoring profile stays off (compose resolves every service).
GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 16)
JAVA_OPTS=${JAVA_OPTS:--Xmx1536m -XX:+UseZGC}
TROVE_CORE_MEM_LIMIT=${TROVE_CORE_MEM_LIMIT:-2g}
EOF
}

render_traefik() {
  mkdir -p "${work}/traefik/dynamic" "${work}/traefik/certs"
  # Same substitutions as deploy/trove's render_traefik(); the HTTP->HTTPS redirect is kept.
  awk -v email="acme@${domain}" -v ca="https://acme-staging-v02.api.letsencrypt.org/directory" '
    /__HTTP_REDIRECT_(BEGIN|END)__/ { next }
    { gsub(/__ACME_EMAIL__/, email); gsub(/__ACME_CA_SERVER__/, ca); print }
  ' "${REPO_ROOT}/deploy/traefik/traefik.yml" > "${work}/traefik/traefik.runtime.yml"
  cp "${REPO_ROOT}/deploy/traefik/dynamic/middlewares.yml" "${work}/traefik/dynamic/"
  if [ ! -f "${work}/traefik/certs/local.crt" ]; then
    openssl req -x509 -newkey rsa:2048 -nodes -days 7 -subj "/CN=${domain}" \
      -addext "subjectAltName=DNS:${domain}" \
      -keyout "${work}/traefik/certs/local.key" -out "${work}/traefik/certs/local.crt" 2>/dev/null
  fi
  cat > "${work}/traefik/dynamic/local-cert.yml" <<EOF
tls:
  certificates:
    - certFile: /etc/traefik/certs/local.crt
      keyFile: /etc/traefik/certs/local.key
EOF
  # Step 27 fixed F1 at its source (apps/web/Dockerfile's HEALTHCHECK now probes
  # 127.0.0.1, not localhost/::1), so the web service no longer needs its own
  # healthcheck override here — only the traefik volume mount remains.
  cat > "${work}/override.yml" <<EOF
services:
  traefik:
    volumes:
      - ${work}/traefik:/etc/traefik:ro
EOF
}

case "$cmd" in
  up)
    preflight_up
    ls "${REPO_ROOT}"/core/target/*-exec.jar >/dev/null 2>&1 \
      || { echo "package core first: ./mvnw -pl core -am clean package -DskipTests" >&2; exit 1; }
    write_env
    render_traefik
    compose config --quiet
    printf '%s\n' "$project" > "$marker"
    compose up -d --build --wait --wait-timeout 400
    curl -fsS -k --resolve "${domain}:${https_port}:127.0.0.1" \
      "https://${domain}:${https_port}/api/v1/status"
    echo
    ;;
  k6)
    # The container reaches the host's published port through Docker's host-gateway alias;
    # `localhost` inside the k6 container is the container itself.
    # .env is read, not sourced: JAVA_OPTS holds an unquoted space, which compose accepts and a
    # shell does not.
    env_value() { grep "^$1=" "${work}/.env" | cut -d= -f2-; }
    docker run --rm -i --add-host "${domain}:host-gateway" \
      -v "${REPO_ROOT}/tests/performance:/scripts:ro" -v "${work}:/out" \
      -e BASE_URL="https://${domain}:${https_port}/api" \
      -e ADMIN_EMAIL="$(env_value TROVE_ADMIN_EMAIL)" -e ADMIN_PASSWORD="$(env_value TROVE_ADMIN_PASSWORD)" \
      -e VUS="${VUS:-50}" -e RAMP="${RAMP:-1m}" -e DURATION="${DURATION:-10m}" -e THINK="${THINK:-1}" \
      grafana/k6:latest run --summary-export /out/k6-summary.json /scripts/trove-load.js
    ;;
  down)
    marker_matches || refuse "${marker} is missing or does not name '${project}': this <workdir> did not create that stack, so its volumes are not this script's to delete"
    compose down -v
    rm -f "$marker"
    ;;
  *)
    echo "usage: $0 up|k6|down <workdir>" >&2; exit 2 ;;
esac
