-- Collapse step 5 (docs/recovery/06-COLLAPSE-PLAN.md §3 V15, §4.5, §6 D4).
--
-- Per-HOUSEHOLD learned merchant-to-category mappings, generalising the old service's
-- per-nothing (globally unique, no tenancy column at all) design onto the shared-ledger
-- model. `household_id` is NOT NULL on every row — unlike `category`, this table has no
-- "system" tier: a merchant string is never global, because the same string can be a
-- counterparty's or an employer's name that only makes sense inside one household's own
-- transaction history.
--
-- `UNIQUE (household_id, normalised_merchant)`: one row per normalised merchant per
-- household, regardless of `match_type` or `source`. An EXACT rule for "STARBUCKS" and a
-- PREFIX rule for "STAR" are different key values and coexist; two rules that would both
-- key on the identical normalised string cannot, which is what makes
-- MerchantMappingWriter's upsert idempotent (`ON CONFLICT (household_id,
-- normalised_merchant) DO UPDATE`) rather than merely best-effort.
--
-- `source` records how a row came to exist: RULE = a household member created it
-- explicitly through the rules UI; USER = self-learned from a member correcting a
-- transaction's category (step 6's write path, via MerchantMappingWriter); AI = a
-- confident AI categorisation result (step 17). Nothing in this step ever writes a USER
-- or AI row — MerchantMappingWriterIT exercises the writer directly since no listener
-- calls it yet ("no listener yet" is deliberate, §6: the CategorisationListener seam is
-- step 16's).
--
-- `priority` orders PREFIX rows against each other when more than one stored prefix
-- could match the same input merchant (e.g. "STAR" and "STARBUCKS COFFEE" both prefix
-- "STARBUCKS COFFEE DOWNTOWN") — the rules controller's reorder endpoint writes it.
--
-- D4 fixes carried into this design (docs/recovery/01-FINDINGS.md D4, applied in the
-- categorisation package, not merely in this schema):
--   1. The old self-invocation bug (`categorise` called `persistMerchantMapping` on
--      `this`, bypassing the REQUIRES_NEW proxy) cannot recur: MerchantMappingWriter is
--      a distinct Spring bean, injected rather than called on `this`.
--   2. The old inverted-prefix-query bug (`findByMerchantStartingWith` was called with
--      the FULL merchant string as the argument, requiring the STORED value to start
--      with it, when stored PREFIX rows are meant to be short) is fixed by writing the
--      lookup the right way round: the stored `normalised_merchant` must be a leading
--      substring of the SUPPLIED cleaned merchant name.
--   3. This table is no longer seeded with zero rows and abandoned — see
--      CommunityMerchantRegistry for the in-memory system-category fallback tier that
--      replaces the old dead "seeded but never read" split.

CREATE TABLE merchant_category_map (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id        UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    normalised_merchant  VARCHAR(255) NOT NULL,
    match_type          VARCHAR(10) NOT NULL CHECK (match_type IN ('EXACT', 'PREFIX')),
    category_id         UUID NOT NULL REFERENCES category(id) ON DELETE RESTRICT,
    confidence          NUMERIC(4,3) NOT NULL,
    source              VARCHAR(10) NOT NULL CHECK (source IN ('RULE', 'USER', 'AI')),
    priority            INTEGER NOT NULL DEFAULT 0,
    created_by          UUID NULL REFERENCES users(id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT merchant_category_map_household_merchant_unique UNIQUE (household_id, normalised_merchant)
);

CREATE INDEX merchant_category_map_household_id_idx ON merchant_category_map (household_id);
CREATE INDEX merchant_category_map_household_type_idx
    ON merchant_category_map (household_id, match_type);
CREATE INDEX merchant_category_map_category_id_idx ON merchant_category_map (category_id);
