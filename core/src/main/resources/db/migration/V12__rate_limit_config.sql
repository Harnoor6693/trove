-- V12 — rate_limit_config (collapse plan §3 V12, §5.8).
--
-- The rules RateLimitFilter enforces, in a table rather than in application.yml so that an admin
-- can retune a limit on a running install (the editing endpoint arrives in a later step; the
-- reload job that picks a change up is here, §5.6 `rate-limit-reload`, every five minutes).
--
-- endpoint_pattern is an Ant pattern matched against the FULL request URI including the /api
-- servlet context path, because that is the string an operator sees in a log line and in
-- audit_log.path. RateLimitService picks the LONGEST matching pattern, so '/api/v1/auth/login'
-- wins over '/api/v1/auth/**' which wins over '/api/**'.
--
-- requests_per_min is the greedy refill rate; burst_capacity is the bucket's maximum, i.e. how
-- much a caller may spend at once after a quiet period. Capacity >= rate always, or the bucket
-- can never fill.
--
-- The seeds are §3 V12's final list. Two notes on the numbers:
--
--   §3 V12 drafts /api/v1/auth/login at 10 per minute and /api/v1/auth/register at 5. Those two
--   numbers are RAISED here, deliberately, and the reason is a conflict between two controls that
--   the draft did not have in front of it. §1.5's per-address gate refuses a client after TWENTY
--   FAILED logins in ten minutes, with 429 `account-locked`. At ten requests per minute that gate
--   can never fire: the request limiter refuses the eleventh call first, counting attempts that
--   SUCCEEDED as well as ones that failed, and answering `rate-limited` — a slug that tells an
--   operator less and an attacker no more. The smarter control has to be the binding one, so the
--   login ceiling is set above the gate it must not pre-empt. LockoutIT is the test that would
--   have found this: it drives twenty-one failures and asserts the slug it gets back.
--
--   This row is therefore a FLOOD ceiling, not the brute-force control. Credential stuffing is
--   counted by login_attempt and refused by LockoutService, which sees a slow attacker no
--   request-rate rule can. The two are layered on purpose.
--
--   The /api/v1/chat/**, /api/v1/ai/**, /api/v1/receipts/upload and /api/v1/accounts/*/sync rows
--   name routes that no controller serves yet; steps 12, 14 and 16 add them. A rule for a path
--   nobody serves is inert, and seeding it now means those steps cannot land an expensive
--   endpoint with no ceiling on it.

CREATE TABLE rate_limit_config (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_pattern VARCHAR(200) NOT NULL UNIQUE,
    requests_per_min INTEGER      NOT NULL CHECK (requests_per_min > 0),
    burst_capacity   INTEGER      NOT NULL CHECK (burst_capacity > 0),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT rate_limit_config_capacity_covers_rate CHECK (burst_capacity >= requests_per_min)
);

INSERT INTO rate_limit_config (endpoint_pattern, requests_per_min, burst_capacity) VALUES
    ('/api/v1/auth/login',          60,  120),
    ('/api/v1/auth/register',       20,   40),
    ('/api/v1/auth/**',             60,  120),
    ('/api/v1/chat/**',             60,  120),
    ('/api/v1/ai/**',               30,   60),
    ('/api/v1/receipts/upload',     30,   60),
    ('/api/v1/accounts/*/sync',     10,   20),
    ('/api/**',                    600, 1200);
