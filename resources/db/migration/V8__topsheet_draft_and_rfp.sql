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

-- Enforce one active DRAFT per provider/period
CREATE UNIQUE INDEX uq_draft_per_provider_period ON topsheets(provider_id, billing_period) WHERE status = 'draft';

-- Batch sequence table for generating batch numbers per provider
CREATE TABLE batch_sequences (
    provider_id UUID PRIMARY KEY REFERENCES providers(id),
    current_value INT NOT NULL DEFAULT 0
);
