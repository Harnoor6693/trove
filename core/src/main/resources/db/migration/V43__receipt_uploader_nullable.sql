-- V43 — receipt.user_id becomes nullable with ON DELETE SET NULL (collapse step 18; ⚙ 17).
--
-- THE DEFECT THIS FIXES. V37 line 61 declares
--     user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE
-- and that is the one foreign key in the whole schema that disagrees with its own table's tenancy.
-- V37's own header says so in as many words: "These are ORDINARY household ledger rows ...
-- household_id is the SCOPE and user_id means 'uploaded by'. A household mate can see, download
-- and delete a receipt somebody else in the household photographed."
--
-- Under the CASCADE, deleting ONE member silently deletes the HOUSEHOLD'S receipts — shared ledger
-- rows that two other people still rely on — and leaves their image files on the volume with
-- nothing pointing at them. Every other household-scoped table already does the right thing:
-- `user_id UUID NULL REFERENCES users(id) ON DELETE SET NULL` in V18, V19, V20, V21, V23, V25,
-- V26, V27, V28, V29 and V30, and the identical nullable SET NULL shape under the name
-- `created_by` in V14 and V15. Owner decision 17 in schema form: the shared ledger wins and the
-- author is nulled.
--
-- GAP ROW 16'S "deletes receipt files" IS SUPERSEDED. That sentence was written on 2026-09-03
-- against the OLD per-user receipt table. V37 scoped receipts to the household, and the merged
-- code is the fact: a departing member's receipts and their images STAY, because they are the
-- household's. What does go with the member is what is genuinely theirs, including their export
-- archives — and those are files, so the purge deletes them explicitly.
--
-- CODE CONSEQUENCES, carried in the same change: `Receipt.userId` becomes nullable and its two
-- readers handle null — `ReceiptPipelineOrchestrator` takes the uploader id from the row, and
-- `VisionExtractionStage` resolves an AI gateway for it. A receipt whose uploader has been purged
-- degrades exactly as an AI-off install already does (REVIEW_REQUIRED, no exception, no 500 in the
-- orphan sweep) rather than throwing.
--
-- V37 IS NOT EDITED. Flyway runs with out-of-order=false in production and an edited applied
-- migration breaks every existing install; the constraint is dropped and re-added here instead.
-- The constraint name is Postgres's own default for a single-column FK on this table
-- (`<table>_<column>_fkey`), which is what V37 got by writing the reference inline.

ALTER TABLE receipt ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE receipt DROP CONSTRAINT receipt_user_id_fkey;

ALTER TABLE receipt
    ADD CONSTRAINT receipt_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;
