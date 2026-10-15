# Trove SLO Results

This file described a Gatling simulation suite targeting the abandoned
API gateway (`/api/auth/register` via Authentik OIDC). That suite is gone.
Load testing today is k6, replacing Gatling — see
[`tests/performance/trove-load.js`](../../tests/performance/trove-load.js)'s
header comment for the run commands (`scripts/load-stack.sh k6 <workdir>`).

No k6 run has been recorded in this document yet. When one is, replace this
file with the measured percentiles against the endpoints k6 exercises,
rather than the old Gatling target table.
