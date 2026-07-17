-- =====================================================================
--  ISP Billing Management System (IBMS) — PostgreSQL Schema
--  Target backend: Ktor + PostgreSQL (replaces Firestore)
--
--  Conventions
--   * UUID primary keys (gen_random_uuid) instead of Firestore string IDs.
--   * `legacy_id` columns hold the original Firestore doc IDs so the ETL
--     migration can preserve references; drop them after cut-over.
--   * Money is numeric(14,2). Never use float for currency.
--   * Timestamps are timestamptz (UTC). Billing periods are 'YYYY-MM' text
--     with a CHECK; installation/contract fields are DATE.
--   * Mandatory-proof invariants that Firestore enforced in app code are
--     enforced here with NOT NULL + FKs to the `attachments` table.
--
--  Apply with Flyway/Liquibase as V1__init.sql (recommended) or psql.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS citext;     -- case-insensitive email

-- ---------------------------------------------------------------------
--  Enumerated types (mirror src/types.ts unions)
-- ---------------------------------------------------------------------
CREATE TYPE user_role            AS ENUM ('sysadmin', 'secretary', 'payables', 'finance', 'pending');
CREATE TYPE store_type           AS ENUM ('puregold', 'puremart');
CREATE TYPE store_status         AS ENUM ('active', 'closed', 'inactive');
CREATE TYPE provider_status      AS ENUM ('active', 'inactive');
CREATE TYPE account_status       AS ENUM ('active', 'termination_requested', 'terminated', 'transferred', 'inactive');
CREATE TYPE topsheet_status      AS ENUM ('compiled', 'approved', 'paid');   -- 'approved' adds the Finance sign-off step
CREATE TYPE topsheet_line_status AS ENUM ('billed', 'paid');
CREATE TYPE attachment_purpose   AS ENUM ('installation_proof', 'closure_proof', 'subscription_proof',
                                          'deactivation_proof', 'transfer_proof', 'ocr_source');

-- =====================================================================
--  1. USERS  (RBAC — replaces  users/{uid})
-- =====================================================================
CREATE TABLE users (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    google_sub       TEXT UNIQUE,                       -- OIDC subject from Google Workspace
    email            CITEXT NOT NULL UNIQUE,
    name             TEXT NOT NULL,
    first_name       TEXT,
    middle_initial   TEXT,
    last_name        TEXT,
    employee_number  TEXT,
    role             user_role NOT NULL DEFAULT 'pending',
    legacy_id        TEXT UNIQUE,                        -- old Firebase UID
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_users_role ON users (role);

-- =====================================================================
--  2. PROVIDERS  (ISPs — replaces  providers/{id})
-- =====================================================================
CREATE TABLE providers (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                 TEXT NOT NULL UNIQUE,
    payment_schedule_day SMALLINT NOT NULL CHECK (payment_schedule_day BETWEEN 1 AND 31),
    status               provider_status NOT NULL DEFAULT 'active',
    deactivated_at       TIMESTAMPTZ,
    legacy_id            TEXT UNIQUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================================================================
--  3. STORES  (retail branches — replaces  stores/{id})
-- =====================================================================
CREATE TABLE stores (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_type                store_type NOT NULL,
    branch_code               TEXT NOT NULL,
    name                      TEXT NOT NULL,
    region                    TEXT,
    province                  TEXT,
    city                      TEXT,
    barangay                  TEXT,
    postal                    TEXT,
    status                    store_status NOT NULL DEFAULT 'active',
    closed_reason             TEXT,
    proof_of_installation_id  UUID NOT NULL,   -- mandatory (was proofOfInstallationUrl); FK added via ALTER after attachments exists
    proof_of_closure_id       UUID,
    created_by                UUID REFERENCES users (id),
    legacy_id                 TEXT UNIQUE,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_stores_branch_code ON stores (branch_code);   -- branch code is a business key
CREATE INDEX idx_stores_status ON stores (status);
-- NOTE: proof_of_installation_id is declared before the attachments table exists;
-- the FK is added at the end via ALTER TABLE (see bottom) to avoid ordering issues.

-- =====================================================================
--  4. ACCOUNTS  (ISP circuits — replaces  accounts/{id})
-- =====================================================================
CREATE TABLE accounts (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_number            TEXT NOT NULL,
    circuit_id                TEXT,
    provider_id               UUID NOT NULL REFERENCES providers (id),
    store_id                  UUID NOT NULL REFERENCES stores (id),
    plan_name                 TEXT,
    service_type              TEXT,
    speed                     TEXT,
    contract_duration_months  INT,
    contract_start_date       DATE,
    contract_end_date         DATE,
    notes                     TEXT,
    installation_fee          NUMERIC(14,2),
    rate                      NUMERIC(14,2) NOT NULL,        -- Monthly Recurring Charge (MRC)
    installation_date         DATE NOT NULL,
    billing_period_label      TEXT,                          -- descriptive, e.g. "1st to 30th"
    is_prorated               BOOLEAN NOT NULL DEFAULT FALSE,
    status                    account_status NOT NULL DEFAULT 'active',
    termination_requested_at  TIMESTAMPTZ,                   -- start of the 30-day grace window
    created_by                UUID REFERENCES users (id),
    legacy_id                 TEXT UNIQUE,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_account_number_per_provider UNIQUE (provider_id, account_number)
);
CREATE INDEX idx_accounts_store    ON accounts (store_id);
CREATE INDEX idx_accounts_provider ON accounts (provider_id);
CREATE INDEX idx_accounts_status   ON accounts (status);

-- =====================================================================
--  5. ATTACHMENTS  (all proof files — replaces base64 blobs / Storage URLs)
--     Files live in object storage (GCS/S3); rows hold metadata + key.
-- =====================================================================
CREATE TABLE attachments (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    purpose       attachment_purpose NOT NULL,
    entity_type   TEXT,                          -- 'store' | 'account' | 'transfer' | 'ocr' (soft link for audit)
    entity_id     UUID,
    storage_key   TEXT NOT NULL,                 -- object storage key/path
    content_type  TEXT,
    size_bytes    BIGINT,
    uploaded_by   UUID REFERENCES users (id),
    legacy_url    TEXT,                           -- original base64/URL captured during ETL
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_attachments_entity ON attachments (entity_type, entity_id);

-- Deferred FKs from stores -> attachments (table now exists)
ALTER TABLE stores
    ADD CONSTRAINT fk_store_install_proof FOREIGN KEY (proof_of_installation_id) REFERENCES attachments (id),
    ADD CONSTRAINT fk_store_closure_proof FOREIGN KEY (proof_of_closure_id)      REFERENCES attachments (id);

-- Account subscription proofs (0..3) + deactivation/transfer proofs, normalized.
CREATE TABLE account_attachments (
    account_id     UUID NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    attachment_id  UUID NOT NULL REFERENCES attachments (id),
    PRIMARY KEY (account_id, attachment_id)
);

-- =====================================================================
--  6. INVOICE SEQUENCES  (atomic per-provider counter — replaces
--     Firestore runTransaction on  sequences/{invoiceCounter_<provider>})
--     Increment with:  UPDATE ... SET current_value = current_value + 1 RETURNING
-- =====================================================================
CREATE TABLE invoice_sequences (
    provider_id    UUID PRIMARY KEY REFERENCES providers (id),
    prefix         TEXT NOT NULL,                 -- provider acronym, e.g. 'CONV-'
    current_value  INT  NOT NULL DEFAULT 0
);

-- =====================================================================
--  7. TOPSHEETS  (monthly compilation — replaces  topsheets/{id})
-- =====================================================================
CREATE TABLE topsheets (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_number         TEXT NOT NULL UNIQUE,           -- '<ACRONYM>-YYYYMM-XXXX'
    billing_period         CHAR(7) NOT NULL CHECK (billing_period ~ '^[0-9]{4}-[0-9]{2}$'),
    provider_id            UUID REFERENCES providers (id),
    provider_name          TEXT,                            -- snapshot at compile time
    account_count          INT NOT NULL,
    total_amount           NUMERIC(14,2) NOT NULL,
    status                 topsheet_status NOT NULL DEFAULT 'compiled',
    compiler_id            UUID NOT NULL REFERENCES users (id),
    approved_by_finance_id UUID REFERENCES users (id),
    approved_at            TIMESTAMPTZ,
    paid_at                TIMESTAMPTZ,
    legacy_id              TEXT UNIQUE,
    compilation_date       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_topsheets_provider_period ON topsheets (provider_id, billing_period);
CREATE INDEX idx_topsheets_status ON topsheets (status);

-- =====================================================================
--  8. TOPSHEET DETAILS  (line items — replaces topsheets/{id}/details/{id})
-- =====================================================================
CREATE TABLE topsheet_details (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    topsheet_id      UUID NOT NULL REFERENCES topsheets (id) ON DELETE CASCADE,
    account_id       UUID NOT NULL REFERENCES accounts (id),
    billing_period   CHAR(7) NOT NULL,
    prorated_amount  NUMERIC(14,2) NOT NULL,
    full_amount      NUMERIC(14,2) NOT NULL,          -- MRC
    status           topsheet_line_status NOT NULL DEFAULT 'billed',
    -- denormalized snapshots (values as they were at compile time)
    branch_code      TEXT,
    store_name       TEXT,
    circuit_id       TEXT,
    account_number   TEXT,
    account_status   TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_topsheet_account UNIQUE (topsheet_id, account_id)
);
CREATE INDEX idx_details_account ON topsheet_details (account_id);
-- Prevents billing the same account twice in one period (the billedAccountIds guard):
CREATE UNIQUE INDEX uq_account_per_period ON topsheet_details (account_id, billing_period);

-- =====================================================================
--  9. TRANSFERS  (circuit relocation audit — replaces  transfers/{id})
-- =====================================================================
CREATE TABLE transfers (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    old_store_id     UUID NOT NULL REFERENCES stores (id),
    new_store_id     UUID NOT NULL REFERENCES stores (id),
    old_account_id   UUID NOT NULL REFERENCES accounts (id),
    new_account_id   UUID NOT NULL REFERENCES accounts (id),
    proof_id         UUID REFERENCES attachments (id),
    requested_by_id  UUID NOT NULL REFERENCES users (id),
    transfer_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    legacy_id        TEXT UNIQUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================================================================
--  10. ACTIVITIES  (audit log — replaces  activities/{id})
-- =====================================================================
CREATE TABLE activities (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID REFERENCES users (id),
    user_email   TEXT,
    user_name    TEXT,
    action       TEXT NOT NULL,
    details      TEXT,
    entity_type  TEXT,
    entity_id    UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_activities_created ON activities (created_at DESC);
CREATE INDEX idx_activities_user    ON activities (user_id);

-- =====================================================================
--  11. EMAIL LOG  (replaces  system_emails/{id} + mail/{id})
--      Delivery moves server-side (MailerSend/SMTP) triggered by events.
-- =====================================================================
CREATE TABLE email_log (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type              TEXT,          -- new_store | new_account | deactivation_requested | ...
    from_email        TEXT,
    to_emails         TEXT[] NOT NULL,
    subject           TEXT,
    body_text         TEXT,
    body_html         TEXT,
    status            TEXT NOT NULL DEFAULT 'queued',   -- queued | sent | failed | simulated
    provider_response TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at           TIMESTAMPTZ
);

-- =====================================================================
--  12. OCR TEMPLATES  (per-telco parsing configs — was hardcoded
--      KNOWN_TEMPLATES in server.ts + the DB-driven matchedOcrConfig)
-- =====================================================================
CREATE TABLE ocr_templates (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_key             TEXT NOT NULL UNIQUE,          -- 'globe-corporate', 'converge-flexibiz', ...
    provider_id            UUID REFERENCES providers (id),
    isp_name               TEXT,
    format_name            TEXT NOT NULL,
    ai_prompt_instruction  TEXT,
    account_number_pattern TEXT,
    amount_pattern         TEXT,
    due_date_pattern       TEXT,
    invoice_number_pattern TEXT,
    billing_period_pattern TEXT,
    detector_keyword       TEXT,
    sample_file_text       TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================================================================
--  13. OCR INGESTION  (bulk-upload pipeline persistence + reconciliation)
--      Supports BulkUploadModal: statement -> extracted rows -> match to
--      accounts -> reconcile against compiled topsheet amounts.
-- =====================================================================
CREATE TABLE ocr_batches (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    uploaded_by    UUID REFERENCES users (id),
    provider_id    UUID REFERENCES providers (id),
    billing_month  CHAR(7),
    file_name      TEXT,
    source_id      UUID REFERENCES attachments (id),      -- the uploaded statement
    method         TEXT,          -- gemini-ocr | booster-ocr-converge | simulated | simulated-fallback
    used_template  TEXT,
    status         TEXT NOT NULL DEFAULT 'extracted',     -- extracted | reconciled | committed
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ocr_extracted_rows (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id             UUID NOT NULL REFERENCES ocr_batches (id) ON DELETE CASCADE,
    account_number       TEXT,
    amount               NUMERIC(14,2),
    outstanding_balance  NUMERIC(14,2),
    due_date             DATE,
    isp_name             TEXT,
    store_name           TEXT,
    invoice_number       TEXT,
    bill_number          TEXT,
    billing_period       TEXT,
    matched_account_id   UUID REFERENCES accounts (id),    -- resolved during reconciliation
    reconciled           BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_ocr_rows_batch ON ocr_extracted_rows (batch_id);

-- =====================================================================
--  updated_at auto-touch trigger (attach to tables that carry it)
-- =====================================================================
CREATE OR REPLACE FUNCTION touch_updated_at() RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_touch      BEFORE UPDATE ON users      FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER trg_providers_touch  BEFORE UPDATE ON providers  FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER trg_stores_touch     BEFORE UPDATE ON stores     FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER trg_accounts_touch   BEFORE UPDATE ON accounts   FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER trg_topsheets_touch  BEFORE UPDATE ON topsheets  FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER trg_ocr_tpl_touch    BEFORE UPDATE ON ocr_templates FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
