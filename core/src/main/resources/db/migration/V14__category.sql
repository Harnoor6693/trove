-- Collapse step 5 (docs/recovery/06-COLLAPSE-PLAN.md §3 V14, §4.5).
--
-- V10 (user_preferences, step 4) is REDUNDANT and stays UNUSED: preferences live on
-- `users` (V3), not on a second table. Recorded here per step-4's instruction to note
-- it in the first migration of a later step.
--
-- Tenancy: household_id NULL means a SYSTEM category (seeded below, read-only through
-- the API); household_id NOT NULL means a household's own category, fully editable by
-- any member of that household (owner decision: one shared household ledger, §1.5).
-- `is_system` is deliberately NOT a stored column — it is computed in code as
-- `householdId == null`, so there is exactly one source of truth for "is this a system
-- row" instead of a flag that could disagree with the tenancy column (this is precisely
-- the shape of the old service's G3 mass-assignment bug: a client-supplied `system`
-- field that a merge could flip).
--
-- Hierarchy: `parent_id` is a nullable self-reference because the old model
-- (categorisation-service V1__category.sql, Category.java) had the column — but the old
-- seed never populated it on any of the 51 rows either, so this migration does not
-- invent a taxonomy tree that never existed. Cycle prevention is enforced in
-- CategoryService, not the database.
--
-- Slugs: dotted-namespace style. Four of them are exactly the slugs
-- 06-COLLAPSE-PLAN.md §3's V15 row names for the old community-merchant-registry
-- slug-to-name mapping (food.coffee -> Coffee Shops, health.pharmacy -> Pharmacy,
-- food.grocery -> Groceries, shopping.online -> Shopping) so that a merchant-registry
-- entry keyed by one of those four strings resolves directly to this table by slug. The
-- plan's fifth mapped slug, food.delivery -> Restaurants, is NOT encoded as any row's
-- own identity slug here: it was always an ALIAS onto the Restaurants category, not a
-- distinct category, and CommunityMerchantRegistry resolves it by category NAME
-- ("Restaurants"), not by this table's slug column.
--
-- Naming: CA/US-neutral. The old seed's "E-Transfer" (Canada-specific, Interac) is
-- renamed "Person-to-Person Transfer" so the same 51-row seed serves both supported
-- jurisdictions (owner decision: CA+US, docs/recovery memory
-- trove-open-decisions.md) without a second seed set.

CREATE TABLE category (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id UUID NULL REFERENCES households(id) ON DELETE CASCADE,
    slug         VARCHAR(60) NOT NULL,
    name         VARCHAR(100) NOT NULL,
    icon         VARCHAR(50),
    parent_id    UUID NULL REFERENCES category(id) ON DELETE SET NULL,
    created_by   UUID NULL REFERENCES users(id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT category_household_slug_unique UNIQUE NULLS NOT DISTINCT (household_id, slug)
);

CREATE INDEX category_household_id_idx ON category (household_id);
CREATE INDEX category_parent_id_idx ON category (parent_id);

INSERT INTO category (slug, name, icon) VALUES
    ('food.dining', 'Food & Dining', 'utensils'),
    ('food.restaurants', 'Restaurants', 'fork-knife'),
    ('food.grocery', 'Groceries', 'shopping-cart'),
    ('food.coffee', 'Coffee Shops', 'coffee'),
    ('food.fastfood', 'Fast Food', 'hamburger'),
    ('transport.general', 'Transport', 'car'),
    ('transport.fuel', 'Gas & Fuel', 'gas-pump'),
    ('transport.transit', 'Public Transit', 'bus'),
    ('transport.parking', 'Parking', 'parking'),
    ('transport.rideshare', 'Ride Share', 'car-side'),
    ('shopping.online', 'Shopping', 'shopping-bag'),
    ('shopping.clothing', 'Clothing', 'tshirt'),
    ('shopping.electronics', 'Electronics', 'laptop'),
    ('shopping.home', 'Home & Garden', 'home'),
    ('entertainment.general', 'Entertainment', 'gamepad'),
    ('entertainment.streaming', 'Streaming', 'play-circle'),
    ('entertainment.movies', 'Movies & Theatre', 'film'),
    ('entertainment.games', 'Games', 'gamepad-2'),
    ('health.general', 'Health', 'heart-pulse'),
    ('health.pharmacy', 'Pharmacy', 'pill'),
    ('health.doctor', 'Doctor', 'stethoscope'),
    ('health.fitness', 'Fitness', 'dumbbell'),
    ('housing.general', 'Housing', 'building'),
    ('housing.rent', 'Rent', 'key'),
    ('housing.mortgage', 'Mortgage', 'house-chimney'),
    ('utilities.general', 'Utilities', 'zap'),
    ('utilities.electricity', 'Electricity', 'lightbulb'),
    ('utilities.internet', 'Internet', 'wifi'),
    ('utilities.water', 'Water', 'droplets'),
    ('insurance.general', 'Insurance', 'shield'),
    ('insurance.auto', 'Auto Insurance', 'car-crash'),
    ('insurance.home', 'Home Insurance', 'shield-home'),
    ('insurance.life', 'Life Insurance', 'shield-heart'),
    ('education.general', 'Education', 'graduation-cap'),
    ('education.tuition', 'Tuition', 'university'),
    ('education.books', 'Books', 'book-open'),
    ('travel.general', 'Travel', 'plane'),
    ('travel.flights', 'Flights', 'plane-takeoff'),
    ('travel.hotels', 'Hotels', 'bed'),
    ('travel.carrental', 'Car Rental', 'car-garage'),
    ('business.general', 'Business', 'briefcase'),
    ('business.office', 'Office Supplies', 'paperclip'),
    ('business.software', 'Software', 'code'),
    ('transfers.general', 'Transfers', 'arrow-left-right'),
    ('transfers.bank', 'Bank Transfer', 'bank'),
    ('transfers.p2p', 'Person-to-Person Transfer', 'send'),
    ('income.general', 'Income', 'trending-up'),
    ('income.salary', 'Salary', 'wallet'),
    ('income.freelance', 'Freelance', 'laptop-2'),
    ('income.investments', 'Investments', 'bar-chart'),
    ('system.uncategorised', 'Uncategorised', 'circle-help');
