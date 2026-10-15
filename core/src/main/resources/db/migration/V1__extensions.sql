-- V1 — Postgres extensions, created once for the whole catalog (collapse plan §3).
--
-- pgcrypto: gen_random_uuid() for every table's primary key default.
-- pg_trgm:  trigram indexes behind the transaction `q` search of gap P2-15 (step 6).
--
-- Both are IF NOT EXISTS so the migration is a no-op against a catalog that already carries
-- them (the pgvector/pgvector:pg16 image ships the contrib modules, it does not enable them).

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
