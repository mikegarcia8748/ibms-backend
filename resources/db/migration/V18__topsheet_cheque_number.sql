-- To fully pay a topsheet, Finance records the cheque number used to pay the accounts.
-- No new status: APPROVED -> PAID is unchanged; we only persist the cheque number.
-- Nullable so pre-existing PAID rows stay valid; new payments always populate it
-- (enforced in the pay use case, not the DB). Reuses the existing 'paid' enum label,
-- so there is NO `ALTER TYPE ... ADD VALUE` and the Postgres same-transaction enum
-- restriction (55P04; see V9/V17) does not apply -- a plain nullable TEXT column is
-- safe as a standalone Flyway migration.
ALTER TABLE topsheets ADD COLUMN cheque_number TEXT;
