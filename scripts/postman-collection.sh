#!/usr/bin/env bash
#
# postman-collection.sh — regenerate docs/postman/trove-core.postman_collection.json from
# openapi/trove-core.yaml (step 26). The file is
# NEVER hand-edited: run this after any spec change and commit the diff.
#
# Tools run only through pinned `npx -y <pkg>@<version>` (never added to package.json):
#   openapi-to-postmanv2@6.3.3 — converts the spec to a v2.1 collection.
#
# Determinism: the converter's schema faker picks a non-deterministic value for any field
# with no explicit spec `example` (a random enum branch, a random string matching a
# `pattern`, and — one field, a multipart form field, even with the faker disabled — a
# random uuid), and mints a fresh random id on every folder/request/response. Two runs of
# this script must produce a byte-identical file (the drift check in Task 26.3/27); this
# script gets there by generating with the faker off and spec examples preferred, dropping
# generated response examples entirely (cosmetic only; newman and a human both work from the
# `request` object, not the saved responses), pinning the one remaining faked value, and
# stripping every id before writing the file with sorted keys.
#
# Needs: node/npx, jq.

set -euo pipefail

REPO_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
SPEC="${REPO_ROOT}/openapi/trove-core.yaml"
OUT="${REPO_ROOT}/docs/postman/trove-core.postman_collection.json"
CONVERTER_VERSION="6.3.3"

tmp=$(mktemp -d)
trap 'rm -rf "${tmp}"' EXIT

# The servlet context path, read from the spec's own `servers` block rather than guessed
# (a recorded design decision). It is a bare path
# (`/api`); the server listens on 8080 locally, so the collection's baseUrl is that path
# behind http://localhost:8080.
context_path=$(awk '/^servers:/{f=1; next} f && /url:/{print $NF; exit}' "${SPEC}")
base_url="http://localhost:8080${context_path}"

npx -y "openapi-to-postmanv2@${CONVERTER_VERSION}" \
  -s "${SPEC}" -o "${tmp}/raw.json" -p \
  -O folderStrategy=Tags,parametersResolution=Example,schemaFaker=false

cat > "${tmp}/transform.jq" << 'JQEOF'
def loginTestScript:
  [
    "const body = pm.response.json();",
    "if (body && body.data && body.data.challengeToken) {",
    "    throw new Error('Login returned a TOTP challengeToken instead of a session; call POST /v1/auth/login/totp with {challengeToken, code}.');",
    "}",
    "if (!(body && body.success && body.data && body.data.accessToken)) {",
    "    throw new Error('Login did not return an access token (status ' + pm.response.code + '): ' + pm.response.text());",
    "}",
    "pm.environment.set('accessToken', body.data.accessToken);",
    "pm.environment.set('refreshToken', body.data.refreshToken);"
  ];

# One request per tag that a smoke run can call without any fixture beyond a signed-in
# user: login (to get a token), the two unauthenticated/self reads, and one list-shaped
# read per resource domain. Tags with no safe read (Account security: every route mutates;
# Imports: the upload mutates and every other route needs an existing batch id) are left out.
def smokeDefs:
  [
    {"tag":"Authentication","method":"POST","path":["v1","auth","login"]},
    {"tag":"Status","method":"GET","path":["v1","status"]},
    {"tag":"Me","method":"GET","path":["v1","auth","me"]},
    {"tag":"Administration","method":"GET","path":["v1","admin","users"]},
    {"tag":"Categories","method":"GET","path":["v1","categories"]},
    {"tag":"Accounts","method":"GET","path":["v1","accounts"]},
    {"tag":"Transactions","method":"GET","path":["v1","transactions"]},
    {"tag":"Dashboard","method":"GET","path":["v1","dashboard","today"]},
    {"tag":"AI","method":"GET","path":["v1","ai","settings"]},
    {"tag":"Net Worth","method":"GET","path":["v1","networth","assets"]},
    {"tag":"Subscriptions","method":"GET","path":["v1","subscriptions"]},
    {"tag":"Budgets","method":"GET","path":["v1","budgets"]},
    {"tag":"Tax export","method":"GET","path":["v1","tax-export","runs"]},
    {"tag":"Notifications","method":"GET","path":["v1","notifications"]},
    {"tag":"Receipts","method":"GET","path":["v1","receipts"]},
    {"tag":"Webhooks","method":"GET","path":["v1","webhooks"]}
  ];

(.info._postman_id = "00000000-0000-0000-0000-000000000026")
| (.variable = [{"key":"baseUrl","value":$baseUrl,"type":"string"}])
| (.auth = {"type":"bearer","bearer":[{"key":"token","value":"{{accessToken}}","type":"string"}]})
| (.event = [{"listen":"prerequest","script":{"type":"text/javascript","exec":[
    "pm.request.headers.upsert({ key: \"X-Trove-Auth-Mode\", value: \"bearer\" });",
    "pm.request.headers.upsert({ key: \"X-Requested-With\", value: \"XMLHttpRequest\" });"
  ]}}])
# Drop generated response examples (see the file header comment: their only job here was
# to carry non-deterministic faked values).
| walk(if type=="object" and has("request") then .response = [] else . end)
# The one field the faker still randomises with schemaFaker off (see file header): pin it
# next to the spec's own fixed-uuid examples.
| walk(
    if type=="object" and has("request")
       and (.request.url.path? == ["v1","imports"])
       and (.request.method == "POST")
    then .request.body.formdata |= (map(if .key=="accountId" then .value="00000000-0000-4000-8000-0000000000c1" else . end))
    else . end
  )
# The spec's own example body (a fixed literal email/password) is fine for a human
# reading the request in the Postman UI, but it is what actually got SENT until this fix:
# the login request ignored the environment's email/password entirely, so a caller who
# followed docs/postman/README.md ("set the environment's email and password ... Send")
# always authenticated as the literal spec example instead — a 401 on every real account
# (found running the newman Smoke folder against a live server). Template the body from the environment.
| walk(
    if type=="object" and has("request")
       and (.request.url.path? == ["v1","auth","login"])
       and (.request.method == "POST")
    then .request.body.raw = "{\n  \"email\": \"{{email}}\",\n  \"password\": \"{{password}}\"\n}"
       | .event = ((.event // []) + [{"listen":"test","script":{"type":"text/javascript","exec": loginTestScript}}])
    else . end
  )
| (.item) as $origItems
| ((smokeDefs | map(
      . as $d
      | ($origItems[] | select(.name==$d.tag) | .item[]
         | select(.request.method==$d.method and .request.url.path==$d.path))
    )) ) as $smokeItems
# A folder-level test script so a 401/500 anywhere in Smoke is a counted newman failure
# instead of silently passing (the folder previously had zero assertions of its own: a
# fully-broken run — every request 401ing on a bad login — still reported "0 failed").
# Scoped to Smoke only, not the whole collection, because the full collection is also used
# for manual exploration where a deliberate 4xx is expected.
| .item = [{"name":"Smoke","item": $smokeItems,"event":[{"listen":"test","script":{"type":"text/javascript","exec":["pm.test('Successful response', function () { pm.response.to.be.success; });"]}}]}] + .item
| del(.. .id?)
JQEOF

jq -S --arg baseUrl "${base_url}" -f "${tmp}/transform.jq" "${tmp}/raw.json" > "${OUT}"

echo "Wrote ${OUT}"
