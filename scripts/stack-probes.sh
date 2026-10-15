#!/usr/bin/env bash
#
# stack-probes.sh — the hardening claims of plan §9 (risks 20, 26, 28, 30 and the CSRF rule),
# re-tested END TO END against the stack scripts/load-stack.sh stood up: through Traefik, TLS,
# real cookies and a real socket, not through MockMvc (collapse step 23, item 4(d)).
#
#   scripts/stack-probes.sh <workdir>      # the same <workdir> given to `load-stack.sh up`
#
# Prints one PASS/FAIL line per claim and exits non-zero if any claim fails. It creates three
# members through admin invites, enrols TOTP on one of them, and deletes the failed login_attempt
# rows its lockout probe wrote (so the load host is not left locked out). It exhausts the app's
# per-address login bucket in its LAST probe: wait a minute before signing in from this host.
#
# Needs: curl, jq, python3 (TOTP codes, stdlib only), docker (psql inside the postgres container).

set -uo pipefail

work="${1:?usage: $0 <workdir>}"
domain="${TROVE_DOMAIN:-trove.test}"
port="${TROVE_HTTPS_PORT:-18443}"
base="https://${domain}:${port}"
api="${base}/api"
run="$(date +%s)"

# The compose project load-stack.sh recorded for this <workdir> (never a real install's `trove`).
project=$(cat "${work}/.load-stack-project" 2>/dev/null) \
  || { echo "${work}/.load-stack-project missing — start the stack with scripts/load-stack.sh up ${work}" >&2; exit 2; }
env_value() { grep "^$1=" "${work}/.env" | cut -d= -f2-; }
c() { curl -sS -k --resolve "${domain}:${port}:127.0.0.1" "$@"; }
psql_() { docker exec "${project}-postgres-1" psql -U "$(env_value POSTGRES_USER | grep . || echo trove)" -d "$(env_value POSTGRES_DB | grep . || echo trove)" -tAc "$1"; }
json() { printf '%s' "$1" | jq -r "$2" 2>/dev/null; }

fails=0
verdict() { # name, ok|anything-else, evidence
  if [ "$2" = ok ]; then echo "PASS  $1 — $3"; else echo "FAIL  $1 — $3"; fails=$((fails + 1)); fi
}

totp() { # base32 secret -> current 6-digit code (RFC 6238, SHA-1, 30 s)
  python3 -c 'import sys,hmac,hashlib,struct,time,base64
s=sys.argv[1].upper(); k=base64.b32decode(s+"="*(-len(s)%8))
h=hmac.new(k,struct.pack(">Q",int(time.time())//30),hashlib.sha1).digest(); o=h[-1]&15
print("%06d"%((struct.unpack(">I",h[o:o+4])[0]&0x7fffffff)%1000000))' "$1"
}

login_body() { jq -nc --arg e "$1" --arg p "$2" '{email:$e,password:$p}'; }
bearer_login() { c -X POST "${api}/v1/auth/login" -H 'Content-Type: application/json' -d "$(login_body "$1" "$2")"; }

admin=$(json "$(bearer_login "$(env_value TROVE_ADMIN_EMAIL)" "$(env_value TROVE_ADMIN_PASSWORD)")" .data.accessToken)
[ -n "$admin" ] && [ "$admin" != null ] || { echo "admin sign-in failed — is the stack up?" >&2; exit 2; }

new_member() { # label -> prints "email password accessToken"
  local email="probe-${run}-$1@${domain}" password
  password="Pw!$(openssl rand -hex 10)"
  local token
  token=$(json "$(c -X POST "${api}/v1/admin/invites" -H "Authorization: Bearer ${admin}" \
    -H 'Content-Type: application/json' -d '{}')" .data.token)
  local body
  body=$(c -X POST "${api}/v1/auth/register" -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg t "$token" --arg e "$email" --arg p "$password" \
      '{inviteToken:$t,email:$e,password:$p,displayName:"Probe"}')")
  echo "$email $password $(json "$body" .data.accessToken)"
  sleep 0.5 # Traefik's ratelimit-auth is 5/s per address
}

read -r a_email a_pass a_token <<<"$(new_member a)"
read -r b_email b_pass b_token <<<"$(new_member b)"
read -r t_email t_pass t_token <<<"$(new_member totp)"

# ─── 0. Links the server mints carry the public URL, not the dev default (step 28) ─────────
# trove.auth.public-url falls back to http://localhost:5173 when TROVE_PUBLIC_URL is not passed
# to trove-core; every invite and admin reset link handed to a member would then be dead.
# The evidence prints scheme and host only: the rest of the link is a live credential.
public_url_ok() { case "$1" in "https://${domain}/"*) echo ok ;; *) echo no ;; esac; }
origin_of() { printf '%s' "$1" | cut -d/ -f1-3; }
invite_url=$(json "$(c -X POST "${api}/v1/admin/invites" -H "Authorization: Bearer ${admin}" \
  -H 'Content-Type: application/json' -d '{}')" .data.url)
verdict "invite link starts with https://${domain}/" "$(public_url_ok "$invite_url")" "$(origin_of "$invite_url")/..."
reset_url=$(json "$(c -X POST "${api}/v1/auth/password/reset-request" -H "Authorization: Bearer ${admin}" \
  -H 'Content-Type: application/json' -d "$(jq -nc --arg e "$a_email" '{email:$e}')")" .data.url)
verdict "admin reset link starts with https://${domain}/" "$(public_url_ok "$reset_url")" "$(origin_of "$reset_url")/..."

# ─── 1. Security headers on both routers (risk 30; decision 22 for the Traefik-owned ones) ──
for url in "${base}/" "${api}/v1/status"; do
  h=$(c -o /dev/null -D - "$url" | tr -d '\r')
  count() { printf '%s\n' "$h" | grep -ci "^$1:"; }
  value() { printf '%s\n' "$h" | grep -i "^$1:" | head -1 | cut -d' ' -f2-; }
  csp=$(value content-security-policy)
  script_src=$(printf '%s' "$csp" | tr ';' '\n' | grep -E '^ ?(script-src|default-src)' | head -1)
  if [ "$(count content-security-policy)" = 1 ] && printf '%s' "$csp" | grep -Eq "(script-src 'self'|default-src 'none')" \
     && ! printf '%s' "$script_src" | grep -q unsafe-inline; then r=ok; else r=no; fi
  verdict "CSP on ${url#"$base"}" "$r" "$(count content-security-policy)x: ${csp}"
  for name in referrer-policy permissions-policy strict-transport-security x-frame-options x-content-type-options; do
    [ "$(count "$name")" = 1 ] && r=ok || r=no
    verdict "${name} on ${url#"$base"}" "$r" "$(count "$name")x: $(value "$name")"
  done
done

# ─── 2. CSRF: a cookie-mode mutation without the double-submit token is refused ────────────
# As the SPA does it: any GET plants XSRF-TOKEN, and the cookie-mode login is itself a POST that
# needs the double-submit pair (it is refused 403 without it).
jar=$(mktemp); trap 'rm -f "$jar"' EXIT
c -c "$jar" -o /dev/null "${api}/v1/status"
xsrf=$(awk '$6 == "XSRF-TOKEN" {print $7}' "$jar" | tail -1)
c -b "$jar" -c "$jar" -o /dev/null -X POST "${api}/v1/auth/login" -H 'Content-Type: application/json' \
  -H 'X-Trove-Auth-Mode: cookie' -H 'X-Requested-With: XMLHttpRequest' -H "X-XSRF-TOKEN: ${xsrf}" \
  -d "$(login_body "$b_email" "$b_pass")"
xsrf=$(awk '$6 == "XSRF-TOKEN" {print $7}' "$jar" | tail -1)
grep -q '__Host-trove_access' "$jar" || echo "      (cookie-mode login did not set __Host-trove_access)"
account='{"sourceKind":"MANUAL","displayName":"csrf probe","accountType":"OTHER"}'
cookie_post() { c -b "$jar" -o /tmp/stack-probe-body.$$ -w '%{http_code}' -X POST "${api}/v1/accounts" \
  -H 'Content-Type: application/json' -H 'X-Trove-Auth-Mode: cookie' "$@" -d "$account"; }
s1=$(cookie_post -H 'X-Requested-With: XMLHttpRequest'); t1=$(json "$(cat /tmp/stack-probe-body.$$)" .error.type)
s2=$(cookie_post -H "X-XSRF-TOKEN: ${xsrf}"); t2=$(json "$(cat /tmp/stack-probe-body.$$)" .error.type)
s3=$(cookie_post -H 'X-Requested-With: XMLHttpRequest' -H "X-XSRF-TOKEN: ${xsrf}")
rm -f /tmp/stack-probe-body.$$
[ "$s1" = 403 ] && [ "$s2" = 403 ] && [ "$s3" = 201 ] && r=ok || r=no
verdict "CSRF on a cookie-mode POST /v1/accounts" "$r" \
  "no X-XSRF-TOKEN -> ${s1} ${t1##*/}; no X-Requested-With -> ${s2} ${t2##*/}; both -> ${s3}"

# ─── 3. JWT type confusion: a totp_challenge token is refused everywhere but login/totp (risk 28)
enrol=$(c -X POST "${api}/v1/auth/totp/setup" -H "Authorization: Bearer ${t_token}" \
  -H 'Content-Type: application/json' -d "$(jq -nc --arg p "$t_pass" '{password:$p}')")
secret=$(json "$enrol" .data.secret)
c -o /dev/null -X POST "${api}/v1/auth/totp/confirm" -H "Authorization: Bearer ${t_token}" \
  -H 'Content-Type: application/json' -d "{\"code\":\"$(totp "$secret")\"}"
sleep 0.5
challenge=$(json "$(bearer_login "$t_email" "$t_pass")" .data.challengeToken)
refused=""; accepted=""
for route in /v1/auth/me /v1/me /v1/accounts /v1/transactions /v1/dashboard/today /v1/budgets /v1/receipts /v1/admin/users; do
  s=$(c -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${challenge}" "${api}${route}")
  [ "$s" = 401 ] && refused="${refused} ${route}" || accepted="${accepted} ${route}=${s}"
done
sleep $(( 31 - $(date +%s) % 30 )) # a fresh 30 s window: the confirm code must not be replayed
s=$(c -o /dev/null -w '%{http_code}' -X POST "${api}/v1/auth/login/totp" -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg t "$challenge" --arg c "$(totp "$secret")" '{challengeToken:$t,code:$c}')")
[ -n "$challenge" ] && [ "$challenge" != null ] && [ -z "$accepted" ] && [ "$s" = 200 ] && r=ok || r=no
verdict "typ=totp_challenge as a bearer token" "$r" \
  "401 on${refused}${accepted:+; NOT refused:${accepted}}; POST /v1/auth/login/totp with it -> ${s}"

# ─── 4. The app limiter runs after authentication and keys on the user (risk 20) ───────────
# /api/v1/ai/** is 30/min burst 60 per key. Member A spends 75; member B, same address, is untouched.
a_codes=$(seq 75 | xargs -P 8 -I{} curl -sS -k --resolve "${domain}:${port}:127.0.0.1" -o /dev/null \
  -w '%{http_code}\n' -H "Authorization: Bearer ${a_token}" "${api}/v1/ai/settings" | sort | uniq -c | tr -s ' ' | tr '\n' ',')
a_type=$(json "$(c -H "Authorization: Bearer ${a_token}" "${api}/v1/ai/settings")" .error.type)
b_status=$(c -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${b_token}" "${api}/v1/ai/settings")
printf '%s' "$a_codes" | grep -q ' 429' && [ "${a_type##*/}" = rate-limited ] && [ "$b_status" = 200 ] && r=ok || r=no
verdict "per-user bucket on GET /v1/ai/settings" "$r" \
  "member A x75: ${a_codes% ,}; then A -> ${a_type##*/}; member B from the same address -> ${b_status}"

# ─── 5. Forged X-Forwarded-For is keyed on the real address; 20 failures lock it (risk 26) ──
since=$(psql_ "select now()")
nobody="nobody-${run}@${domain}"
for i in $(seq 20); do
  c -o /dev/null -X POST "${api}/v1/auth/login" -H 'Content-Type: application/json' \
    -H "X-Forwarded-For: 203.0.113.${i}" -H "X-Real-IP: 198.51.100.${i}" -d "$(login_body "$nobody" wrong-password-x)"
  sleep 0.3
done
body=$(c -X POST "${api}/v1/auth/login" -H 'Content-Type: application/json' \
  -H 'X-Forwarded-For: 203.0.113.99' -d "$(login_body "$a_email" "$a_pass")")
locked=$(json "$body" .error.type)
ips=$(psql_ "select coalesce(host(ip),'NULL') || ' x' || count(*) from login_attempt where at >= '${since}' group by ip" | tr '\n' ' ')
addrs=$(psql_ "select distinct coalesce(host(ip),'NULL') from login_attempt where at >= '${since}'")
psql_ "delete from login_attempt where at >= '${since}' and not success" >/dev/null
# Not forged is not enough: if trove-core stopped trusting Traefik's X-Forwarded-For it would key
# every client on its socket peer — Traefik, on trove_edge — and one attacker's 20 failures would
# lock out everybody. So: exactly one non-NULL address, and not one of trove_edge's.
edge_subnets=$(docker network inspect trove_edge -f '{{range .IPAM.Config}}{{.Subnet}} {{end}}' 2>/dev/null)
outside_edge=$(python3 -c 'import sys,ipaddress
a,nets=sys.argv[1].split(),sys.argv[2].split()
print("yes" if len(a)==1 and a[0]!="NULL" and nets and not any(ipaddress.ip_address(a[0]) in ipaddress.ip_network(n) for n in nets) else "no")' \
  "$addrs" "$edge_subnets" 2>/dev/null || echo no)
[ "${locked##*/}" = account-locked ] && ! printf '%s' "$ips" | grep -q '203\.0\.113\|198\.51\.100' \
  && [ "$outside_edge" = yes ] && r=ok || r=no
verdict "forged X-Forwarded-For through Traefik" "$r" \
  "20 failures with 20 different forged addresses recorded as: ${ips}(one non-NULL address outside trove_edge ${edge_subnets% }: ${outside_edge}); the 21st (correct password) -> ${locked##*/} (probe rows deleted afterwards)"

# ─── 6. The edge limiter: the eleventh rapid call to /api/v1/auth is Traefik's 429 ───────────
sleep 3
edge=$(seq 15 | xargs -P 15 -I{} curl -sS -k --resolve "${domain}:${port}:127.0.0.1" -o /dev/null \
  -w '%{http_code}\n' "${api}/v1/auth/providers" | sort | uniq -c | tr -s ' ' | tr '\n' ',')
edge_body=$(c "${api}/v1/auth/providers"; for i in $(seq 12); do c -o /dev/null "${api}/v1/auth/providers"; done; c "${api}/v1/auth/providers")
printf '%s' "$edge" | grep -q ' 429' && r=ok || r=no
verdict "Traefik ratelimit-auth (5/s, burst 10)" "$r" \
  "15 concurrent GET /api/v1/auth/providers: ${edge% ,} (refused body: '$(printf '%s' "$edge_body" | tail -c 40 | tr -d '\n')')"

# ─── 7. The app's own login bucket, behind the edge: paced under Traefik's limit (risk 20) ────
sleep 3
n=0; s=200; t=""
while [ "$n" -lt 260 ] && [ "$s" != 429 ]; do
  n=$((n + 1))
  body=$(c -w '\n%{http_code}' -X POST "${api}/v1/auth/login" -H 'Content-Type: application/json' -d "$(login_body "$a_email" "$a_pass")")
  s=$(printf '%s' "$body" | tail -1); t=$(json "$(printf '%s' "$body" | sed '$d')" .error.type)
  sleep 0.25
done
[ "$s" = 429 ] && [ "${t##*/}" = rate-limited ] && r=ok || r=no
verdict "trove-core login bucket (60/min, burst 120, per address)" "$r" \
  "correct-password logins at ~4/s: attempt ${n} -> ${s} ${t##*/}"

echo "---- ${fails} claim(s) failed"
exit $(( fails > 0 ))
