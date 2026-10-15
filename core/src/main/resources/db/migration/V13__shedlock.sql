-- V13 — shedlock (collapse plan §3 V13, §5.6).
--
-- ShedLock's own table, in the schema ShedLock 6 documents for the JdbcTemplate provider. One
-- copy: the pre-collapse tree had this table in the model-manager and notification services with
-- identical definitions, which is exactly the duplication §5.14 removes.
--
-- It is not a Trove domain table and nothing in finance.trove reads or writes it directly —
-- net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider owns every statement
-- against it. It is here because Flyway owns the schema and ddl-auto is `validate`, so a table
-- created by a library at runtime would either fail or drift.
--
-- What the columns mean, because the failure they prevent is not obvious from their names:
-- `lock_until` is when the holder's claim expires (defaultLockAtMostFor, so a process killed
-- mid-job cannot wedge a schedule for ever); `locked_at`/`locked_by` are for a human reading the
-- table during an incident. There is exactly one row per lock NAME, updated in place — the table
-- does not grow with the number of executions.

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,

    CONSTRAINT shedlock_pkey PRIMARY KEY (name)
);
