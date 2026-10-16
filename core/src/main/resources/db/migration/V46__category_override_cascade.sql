-- B1-18: a pinned transaction used to make its category undeletable (409 with no explanation).
-- Deleting the category now removes the pin; the transaction falls back to automatic
-- categorisation (transaction.category_id is already ON DELETE SET NULL).
ALTER TABLE category_override DROP CONSTRAINT category_override_category_id_fkey;
ALTER TABLE category_override
    ADD CONSTRAINT category_override_category_id_fkey
    FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE CASCADE;
