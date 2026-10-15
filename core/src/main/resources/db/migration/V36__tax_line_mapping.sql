-- Collapse step 15 (docs/recovery/06-COLLAPSE-PLAN.md §3 V42, §2.9, §4.12, §6 "Tax export").
--
-- Re-based V-number: §3 prints this table as V42 under the plan's ORIGINAL numbering; only its
-- columns and constraints come from that row (§3's re-basing note).
--
-- WHAT THIS TABLE IS. A label, and nothing else: for one jurisdiction and one category, the line
-- code and wording a human reads off a tax form, plus a boolean the seed asserts or a member
-- overrides. Owner decision 2 and §9 risk 15: NOTHING in this table or this step carries a rate,
-- a bracket, a computed figure, or the receipt GST/PST/HST split (that is step 14's, CA-only,
-- receipt-scoped and never exported). services/tax-engine is DELETED ENTIRELY (§2.4) and nothing
-- is moved from it.
--
-- TWO TIERS IN ONE TABLE. `user_id IS NULL` is the install-wide SEED row; `user_id = <member>` is
-- that member's own override. `TaxLineMappingService.resolve` reads both in ONE query and lets
-- the override win, so a household mate's override is invisible to everybody else — a filing is
-- individual (§1.5, §6).
--
-- `NULLS NOT DISTINCT` IS LOAD-BEARING, and §2.9 and §3 both say why. Under the plain-UNIQUE
-- default every NULL is distinct from every other NULL, so the seed row is not unique against
-- itself: two seeds for one (jurisdiction, category) would silently coexist, `ON CONFLICT` could
-- not target the seed, and `resolve` would have to pick between two rows that both claim to be
-- global. Postgres 16 is pinned (§2.13) and step 5's V15/V14 already use the clause in this same
-- catalog. The upsert behind `PUT /v1/tax-export/lines/{categoryId}` targets exactly this
-- constraint, which is the other half of the reason it has to be declared this way.
--
-- `category_id` IS ON DELETE CASCADE AND DELIBERATELY NOT RESTRICT. A household deleting its own
-- category must not be blocked by a tax label somebody attached to it; the label is derived
-- information and the category is the fact. Every SEEDED row points at a SYSTEM category
-- (household_id IS NULL), which step 5 already made undeletable through the API, so the cascade
-- can only ever reach a household's own category or a member's own override of one.
--
-- SEEDS: both jurisdictions, for every one of the 51 SYSTEM categories V14 seeded, joined BY SLUG
-- and never by name. The slug is the stable id (handoff 11 §8). The join is written as
-- `FROM (VALUES ...) s JOIN category c ON c.household_id IS NULL AND c.slug = s.slug` rather than
-- as `SELECT id FROM category WHERE name = <slug>` precisely because V15's own blocker was the
-- latter shape: an unmatched row yielded NULL into a NOT NULL FK and halted Flyway on every boot.
-- Written this way a typo produces NO ROW, and `TaxExportSeedIT` then fails on the count rather
-- than the application failing to start.
--
-- `NONE` IS A REAL SEEDED VALUE, NOT A PLACEHOLDER. Most personal categories have no line on any
-- form in either country; seeding them onto a business line such as T2125 9270 or Schedule C 27a
-- would be a WRONG line code, and a wrong line code on a tax form is worse than a blank one. So
-- those rows carry `line_code = 'NONE'` with a label that says so and `deductible = false`. That
-- is different again from having no row at all, which is what a household's OWN category has: it
-- is exported with empty tax-line cells and is still a row in the file.
--
-- No money column here either, so `SchemaDefaultsIT` needs no allowlist entry from this step.

CREATE TABLE tax_line_mapping (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    jurisdiction VARCHAR(2)    NOT NULL CHECK (jurisdiction IN ('CA', 'US')),
    category_id  UUID          NOT NULL REFERENCES category(id) ON DELETE CASCADE,
    user_id      UUID          NULL REFERENCES users(id) ON DELETE CASCADE,
    line_code    VARCHAR(20)   NOT NULL,
    line_label   VARCHAR(200)  NOT NULL,
    deductible   BOOLEAN       NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT tax_line_mapping_scope_unique
        UNIQUE NULLS NOT DISTINCT (jurisdiction, category_id, user_id)
);

-- The unique constraint's own index already serves the resolve query, whose leading predicate is
-- `jurisdiction = ?`. These two exist for the other two access paths: the cascade from `category`
-- and the cascade from `users`.
CREATE INDEX tax_line_mapping_category_id_idx ON tax_line_mapping (category_id);
CREATE INDEX tax_line_mapping_user_id_idx ON tax_line_mapping (user_id);

INSERT INTO tax_line_mapping (jurisdiction, category_id, user_id, line_code, line_label,
                              deductible)
SELECT s.jurisdiction, c.id, NULL, s.line_code, s.line_label, s.deductible
FROM (VALUES
    -- ---------------------------------------------------------------- Canada (T2125 / T1)
    ('CA'::varchar(2), 'food.dining'::varchar(60), 'NONE'::varchar(20),
        'No tax line - personal expense'::varchar(200), false),
    ('CA', 'food.restaurants', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'food.grocery', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'food.coffee', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'food.fastfood', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'transport.general', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'transport.fuel', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'transport.transit', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'transport.parking', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'transport.rideshare', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'shopping.online', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'shopping.clothing', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'shopping.electronics', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'shopping.home', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'entertainment.general', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'entertainment.streaming', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'entertainment.movies', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'entertainment.games', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'health.general', '33099',
        'T1 line 33099 - Medical expenses for self, spouse and dependants', true),
    ('CA', 'health.pharmacy', '33099',
        'T1 line 33099 - Medical expenses for self, spouse and dependants', true),
    ('CA', 'health.doctor', '33099',
        'T1 line 33099 - Medical expenses for self, spouse and dependants', true),
    ('CA', 'health.fitness', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'housing.general', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'housing.rent', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'housing.mortgage', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'utilities.general', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'utilities.electricity', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'utilities.internet', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'utilities.water', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'insurance.general', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'insurance.auto', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'insurance.home', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'insurance.life', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'education.general', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'education.tuition', '32300',
        'T1 line 32300 - Tuition, education and textbook amounts', true),
    ('CA', 'education.books', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'travel.general', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'travel.flights', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'travel.hotels', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'travel.carrental', 'NONE', 'No tax line - personal expense', false),
    ('CA', 'business.general', '9270', 'T2125 line 9270 - Other expenses', true),
    ('CA', 'business.office', '8810', 'T2125 line 8810 - Office expenses', true),
    ('CA', 'business.software', '8810', 'T2125 line 8810 - Office expenses', true),
    ('CA', 'transfers.general', 'NONE', 'No tax line - transfer between accounts', false),
    ('CA', 'transfers.bank', 'NONE', 'No tax line - transfer between accounts', false),
    ('CA', 'transfers.p2p', 'NONE', 'No tax line - transfer between accounts', false),
    ('CA', 'income.general', '13000', 'T1 line 13000 - Other income', false),
    ('CA', 'income.salary', '10100', 'T1 line 10100 - Employment income', false),
    ('CA', 'income.freelance', '13500', 'T1 line 13500 - Self-employment business income', false),
    ('CA', 'income.investments', '12100',
        'T1 line 12100 - Interest and other investment income', false),
    ('CA', 'system.uncategorised', 'NONE', 'No tax line - uncategorised', false),

    -- ------------------------------------------- United States (Schedule C / Schedule A / 1040)
    ('US', 'food.dining', 'NONE', 'No tax line - personal expense', false),
    ('US', 'food.restaurants', 'NONE', 'No tax line - personal expense', false),
    ('US', 'food.grocery', 'NONE', 'No tax line - personal expense', false),
    ('US', 'food.coffee', 'NONE', 'No tax line - personal expense', false),
    ('US', 'food.fastfood', 'NONE', 'No tax line - personal expense', false),
    ('US', 'transport.general', 'NONE', 'No tax line - personal expense', false),
    ('US', 'transport.fuel', 'NONE', 'No tax line - personal expense', false),
    ('US', 'transport.transit', 'NONE', 'No tax line - personal expense', false),
    ('US', 'transport.parking', 'NONE', 'No tax line - personal expense', false),
    ('US', 'transport.rideshare', 'NONE', 'No tax line - personal expense', false),
    ('US', 'shopping.online', 'NONE', 'No tax line - personal expense', false),
    ('US', 'shopping.clothing', 'NONE', 'No tax line - personal expense', false),
    ('US', 'shopping.electronics', 'NONE', 'No tax line - personal expense', false),
    ('US', 'shopping.home', 'NONE', 'No tax line - personal expense', false),
    ('US', 'entertainment.general', 'NONE', 'No tax line - personal expense', false),
    ('US', 'entertainment.streaming', 'NONE', 'No tax line - personal expense', false),
    ('US', 'entertainment.movies', 'NONE', 'No tax line - personal expense', false),
    ('US', 'entertainment.games', 'NONE', 'No tax line - personal expense', false),
    ('US', 'health.general', 'SchA-1',
        'Schedule A line 1 - Medical and dental expenses', true),
    ('US', 'health.pharmacy', 'SchA-1',
        'Schedule A line 1 - Medical and dental expenses', true),
    ('US', 'health.doctor', 'SchA-1',
        'Schedule A line 1 - Medical and dental expenses', true),
    ('US', 'health.fitness', 'NONE', 'No tax line - personal expense', false),
    ('US', 'housing.general', 'NONE', 'No tax line - personal expense', false),
    ('US', 'housing.rent', 'NONE', 'No tax line - personal expense', false),
    ('US', 'housing.mortgage', 'SchA-8a',
        'Schedule A line 8a - Home mortgage interest and points', true),
    ('US', 'utilities.general', 'NONE', 'No tax line - personal expense', false),
    ('US', 'utilities.electricity', 'NONE', 'No tax line - personal expense', false),
    ('US', 'utilities.internet', 'NONE', 'No tax line - personal expense', false),
    ('US', 'utilities.water', 'NONE', 'No tax line - personal expense', false),
    ('US', 'insurance.general', 'NONE', 'No tax line - personal expense', false),
    ('US', 'insurance.auto', 'NONE', 'No tax line - personal expense', false),
    ('US', 'insurance.home', 'NONE', 'No tax line - personal expense', false),
    ('US', 'insurance.life', 'NONE', 'No tax line - personal expense', false),
    ('US', 'education.general', 'NONE', 'No tax line - personal expense', false),
    ('US', 'education.tuition', 'F8863',
        'Form 8863 - Education credits (qualified tuition)', true),
    ('US', 'education.books', 'NONE', 'No tax line - personal expense', false),
    ('US', 'travel.general', 'NONE', 'No tax line - personal expense', false),
    ('US', 'travel.flights', 'NONE', 'No tax line - personal expense', false),
    ('US', 'travel.hotels', 'NONE', 'No tax line - personal expense', false),
    ('US', 'travel.carrental', 'NONE', 'No tax line - personal expense', false),
    ('US', 'business.general', 'SchC-27a', 'Schedule C line 27a - Other expenses', true),
    ('US', 'business.office', 'SchC-18', 'Schedule C line 18 - Office expense', true),
    ('US', 'business.software', 'SchC-18', 'Schedule C line 18 - Office expense', true),
    ('US', 'transfers.general', 'NONE', 'No tax line - transfer between accounts', false),
    ('US', 'transfers.bank', 'NONE', 'No tax line - transfer between accounts', false),
    ('US', 'transfers.p2p', 'NONE', 'No tax line - transfer between accounts', false),
    ('US', 'income.general', 'Sch1-8', 'Schedule 1 line 8 - Other income', false),
    ('US', 'income.salary', 'F1040-1a', 'Form 1040 line 1a - Wages, salaries and tips', false),
    ('US', 'income.freelance', 'SchC-1', 'Schedule C line 1 - Gross receipts or sales', false),
    ('US', 'income.investments', 'SchB-1',
        'Schedule B - Interest and ordinary dividends', false),
    ('US', 'system.uncategorised', 'NONE', 'No tax line - uncategorised', false)
) AS s (jurisdiction, slug, line_code, line_label, deductible)
JOIN category c ON c.household_id IS NULL AND c.slug = s.slug;
