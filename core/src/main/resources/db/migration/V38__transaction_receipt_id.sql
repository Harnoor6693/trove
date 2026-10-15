-- Collapse step 14 (docs/recovery/06-COLLAPSE-PLAN.md §3 V27's trailing ALTER, §3's FK list).
--
-- The other half of the receipt/transaction link. §3's foreign-key list names both halves —
-- `receipt.matched_transaction_id` (V37) and `transaction.receipt_id` (here) — because each side
-- answers a different question without a join: "which transaction is this receipt attached to"
-- from the receipt screen, and "does this transaction have a receipt" from the ledger row, which
-- is also what step 15's tax export reads for its `receiptId` column.
--
-- WHAT KEEPS THE TWO CONSISTENT. Exactly one method writes either column:
-- `finance.trove.receipts.receipt.ReceiptLinkService#link(householdId, receiptId,
-- transactionId)`. It is `@Transactional`, it writes both sides plus the clearing of any previous
-- partner in that one transaction, and nothing else in the tree writes `receipt
-- .matched_transaction_id` or `transaction.receipt_id` at all — the matching stage and the manual
-- PATCH both go through it. Two half-written links is the failure this shape exists to prevent,
-- and the guarantee is "one writer", not "two writers that agree".
--
-- ONE WRITER IS NOT THE SAME AS ONE WRITER AT A TIME, which is why that method reads the
-- transaction it is about to write with SELECT ... FOR UPDATE
-- (`TransactionQueryService#findOneForUpdate`). At READ COMMITTED two mates attaching two
-- different receipts to this row would otherwise both see `receipt_id` as null and both write it.
--
-- AND WHY THERE IS NO UNIQUE INDEX HERE. A partial `UNIQUE (receipt_id) WHERE receipt_id IS NOT
-- NULL` would look like the stronger answer and is the wrong instrument: moving a link legitimately
-- passes through a state where the old transaction has not yet been cleared and the new one has
-- been set, Hibernate decides the order of those two UPDATEs within one flush, and a Postgres
-- unique index is checked per statement and cannot be deferred. The constraint would therefore
-- reject a correct move at random. The lock orders the writers instead, which is what was
-- actually missing.
--
-- A SEPARATE FILE from V37 on purpose: it depends on `receipt` existing, it is the only statement
-- this step makes against another step's table (V19's `transaction`, step 6's), and a reviewer
-- must be able to see that one-line change without reading three CREATE TABLEs to find it.
--
-- ON DELETE SET NULL rather than CASCADE, on both halves: deleting a receipt must not delete the
-- transaction it was attached to, and deleting a transaction must not delete the photograph of
-- what was bought.

ALTER TABLE transaction
    ADD COLUMN receipt_id UUID NULL REFERENCES receipt(id) ON DELETE SET NULL;

CREATE INDEX transaction_receipt_id_idx ON transaction (receipt_id);
