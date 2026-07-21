CREATE TYPE account_change_request_status AS ENUM ('pending', 'approved', 'rejected', 'cancelled');

CREATE TABLE account_change_requests (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id           UUID NOT NULL REFERENCES accounts (id),
    submitted_by_id      UUID NOT NULL REFERENCES users (id),
    status               account_change_request_status NOT NULL DEFAULT 'pending',

    -- Change delta columns (null = no change requested for this field)
    account_number_new   TEXT,
    installation_date_new DATE,
    rate_new             NUMERIC(14,2),
    provider_id_new      UUID REFERENCES providers (id),
    circuit_id_new       TEXT,
    plan_name_new        TEXT,
    proof_attachment_id  UUID REFERENCES attachments (id),

    -- Approval/rejection metadata
    approved_by_id       UUID REFERENCES users (id),
    approved_at          TIMESTAMPTZ,
    rejected_reason      TEXT,
    cancelled_at         TIMESTAMPTZ,

    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Enforce single pending request per account at DB level
CREATE UNIQUE INDEX idx_acr_one_pending_per_account
    ON account_change_requests (account_id) WHERE status = 'pending';

CREATE INDEX idx_acr_account_id ON account_change_requests (account_id);
CREATE INDEX idx_acr_submitted_by ON account_change_requests (submitted_by_id, status);
CREATE INDEX idx_acr_status_created ON account_change_requests (status, created_at DESC);

CREATE TRIGGER touch_updated_at_account_change_requests
    BEFORE UPDATE ON account_change_requests
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
