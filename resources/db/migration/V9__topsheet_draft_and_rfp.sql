-- Add 'draft' to topsheet_status enum
ALTER TYPE topsheet_status ADD VALUE IF NOT EXISTS 'draft' BEFORE 'compiled';

-- Make invoice_number nullable (DRAFT topsheets don't have one yet)
ALTER TABLE topsheets ALTER COLUMN invoice_number DROP NOT NULL;

-- Add batch_number column to topsheets
ALTER TABLE topsheets ADD COLUMN batch_number TEXT;
CREATE INDEX idx_topsheets_batch_number ON topsheets(batch_number);

-- Add RFP fields to topsheet_details
ALTER TABLE topsheet_details ADD COLUMN rfp_number TEXT;
ALTER TABLE topsheet_details ADD COLUMN rfp_sort_order SMALLINT;

-- NOTE: the partial unique index enforcing "one active DRAFT per provider/period"
-- lives in V11. Postgres forbids using a newly added enum value ('draft', added
-- above) in the same transaction that added it (SQLSTATE 55P04), and Flyway wraps
-- each migration in one transaction — so the index predicate WHERE status = 'draft'
-- must run in a later migration, after this one commits the new enum label.

-- Batch sequence table for generating batch numbers per provider
CREATE TABLE batch_sequences (
    provider_id UUID PRIMARY KEY REFERENCES providers(id),
    current_value INT NOT NULL DEFAULT 0
);
