-- =====================================================================
--  Idempotency keys for money-mutating POSTs (topsheet compile/pay,
--  transfers). A duplicate (scope, idempotency_key) replays the stored
--  response instead of repeating the side effect. The row is reserved AND
--  completed inside the SAME transaction as the mutation, so a failed
--  mutation rolls the reservation back — only successful responses persist.
-- =====================================================================
CREATE TABLE idempotency_keys (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope            TEXT NOT NULL,                 -- e.g. 'topsheet.pay'
    idempotency_key  TEXT NOT NULL,
    user_id          UUID REFERENCES users(id),
    request_hash     TEXT NOT NULL,                 -- SHA-256 of the request body
    response_status  INT,
    response_body    TEXT,                          -- serialized domain result (null until completed)
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    CONSTRAINT uq_idempotency_scope_key UNIQUE (scope, idempotency_key)
);
