-- Enforce one active DRAFT per provider/period.
--
-- Split out of V9 (topsheet_draft_and_rfp): the 'draft' enum value is added to
-- topsheet_status in V9, and Postgres refuses to reference a newly added enum
-- value in the same transaction that added it (SQLSTATE 55P04 "unsafe use of new
-- value"). Flyway runs each migration in a single transaction, so the predicate
-- WHERE status = 'draft' can only be used here, once V9 has committed the label.
CREATE UNIQUE INDEX uq_draft_per_provider_period
    ON topsheets(provider_id, billing_period)
    WHERE status = 'draft';
