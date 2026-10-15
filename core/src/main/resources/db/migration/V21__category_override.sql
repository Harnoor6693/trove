-- Collapse step 6 (docs/recovery/06-COLLAPSE-PLAN.md §3 V19-row, §6 D4).
--
-- WHY THIS TABLE IS HERE AND NOT IN STEP 5. Its `transaction_id` needs a real FK to
-- `transaction`, and that table did not exist until V19 of this step. Step 5 deferred the table
-- for exactly that reason (§7), which is also why §1.5's ledger list omits it: the list was
-- written from step 5's tables. It IS household-scoped, like every other ledger table.
--
-- TENANCY. `household_id` NOT NULL is the scope: a pin is a decision about the shared ledger, so
-- a household mate must see the same category on the same transaction, not their own private
-- one. `user_id` records WHO pinned it, for audit, and is nullable so removing a member does not
-- silently unpin their corrections. That is the change from categorisation-service V3, whose
-- UNIQUE (user_id, transaction_id) allowed two members of one household to hold contradictory
-- pins on one row.
--
-- UNIQUE (transaction_id): one pin per transaction. PATCH /v1/transactions/{id} upserts it, so
-- correcting a category twice replaces the pin instead of accumulating rows.
--
-- ON DELETE CASCADE from `transaction`: a pin on a deleted transaction is not evidence of
-- anything. `category_id` keeps V3's plain reference (NO ACTION), so a category still pinned by
-- a member cannot be deleted out from under it.

CREATE TABLE category_override (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id   UUID        NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id        UUID        NULL REFERENCES users(id) ON DELETE SET NULL,
    transaction_id UUID        NOT NULL REFERENCES transaction(id) ON DELETE CASCADE,
    category_id    UUID        NOT NULL REFERENCES category(id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT category_override_transaction_unique UNIQUE (transaction_id)
);

CREATE INDEX category_override_household_id_idx ON category_override (household_id);
CREATE INDEX category_override_user_id_idx      ON category_override (user_id);
CREATE INDEX category_override_category_id_idx  ON category_override (category_id);
