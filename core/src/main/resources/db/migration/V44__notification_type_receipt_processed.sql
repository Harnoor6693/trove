-- V44 — RECEIPT_PROCESSED joins the notification type vocabulary (collapse step 18; ⚙ 28).
--
-- The receipt-processed wire was specified by collapse step 16 and deferred to step 14, which
-- shipped the publisher (`ReceiptEventPublisher`) and neither listener. Step 18 closes it, and the
-- three halves have to land together or none of them is valid: this CHECK widening, the
-- `NotificationType.RECEIPT_PROCESSED` constant with the spec's `NotificationTypeCode` enum, and
-- the two listeners. A CHECK value no producer can write is a promise the schema cannot keep; a
-- producer whose value the CHECK refuses fails its INSERT at run time.
--
-- V32 AND V33 ARE NOT EDITED. Flyway runs with out-of-order=false in production, so an edited
-- applied migration breaks every existing install. The constraints are dropped and re-added here
-- with the same names V32 and V33 gave them, and the value lists are copied from those files
-- rather than retyped.
--
-- Both `type` columns are already VARCHAR(40) (V32 fixed the source's VARCHAR(20), which would
-- have rejected 'SUBSCRIPTION_PRICE_CHANGE' at 25 characters), so 'RECEIPT_PROCESSED' at
-- seventeen needs no width change.

ALTER TABLE notification_log DROP CONSTRAINT notification_log_type_check;

ALTER TABLE notification_log
    ADD CONSTRAINT notification_log_type_check
    CHECK (type IN ('BUDGET_THRESHOLD', 'SUBSCRIPTION_PRICE_CHANGE', 'SYNC_FAILED',
                    'WEEKLY_DIGEST', 'RECEIPT_PROCESSED'));

ALTER TABLE in_app_notification DROP CONSTRAINT in_app_notification_type_check;

ALTER TABLE in_app_notification
    ADD CONSTRAINT in_app_notification_type_check
    CHECK (type IN ('BUDGET_THRESHOLD', 'SUBSCRIPTION_PRICE_CHANGE', 'SYNC_FAILED',
                    'WEEKLY_DIGEST', 'RECEIPT_PROCESSED'));
