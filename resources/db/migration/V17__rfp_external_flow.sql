-- External RFP generation + release-to-finance flow.
--
-- Add the 'rfp_assigned' status: DRAFT -> COMPILED (confirm) -> RFP_ASSIGNED
-- (external RFP generation) -> APPROVED (secretary releases to finance). Postgres
-- forbids *using* a newly added enum value in the same transaction that added it
-- (SQLSTATE 55P04; see V9/V11), but here we only ADD the label and add a column that
-- does not reference it, so this is safe in a single migration.
ALTER TYPE topsheet_status ADD VALUE IF NOT EXISTS 'rfp_assigned' AFTER 'compiled';

-- Per-line unique key returned by the external RFP system, used to link the RFP and
-- to reconcile when releasing the payment transaction to finance.
ALTER TABLE topsheet_details ADD COLUMN rfp_unique_key TEXT;
