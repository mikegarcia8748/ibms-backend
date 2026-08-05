-- =====================================================================
--  Account activity proofs: up to 3 PDFs per activity, tagged by purpose.
--
--  account_attachments (V1) was a bare (account_id, attachment_id) pair. Every
--  proof an account ever collected landed in it undifferentiated, and
--  ExposedAccountRepository read the WHOLE table back as `subscriptionProofIds`.
--  DeactivateAccountUseCase's linkProof() therefore made the deactivation PDF
--  surface as a subscription proof, and TransferAccountUseCase then copied that
--  polluted list onto the account it creates at the destination store. That is a
--  shipped defect, not a hypothetical. Transfer proofs meanwhile lived only in
--  transfers.proof_id — one, singular — and were never linked to an account at all.
--
--  Fix: the LINK row carries its own `purpose`, its `sort_order` within the
--  activity, when and by whom it was attached, and — for a transfer — the
--  transfers row it belongs to. `linked_at` defaults to now(), which in Postgres is
--  the TRANSACTION timestamp, so the 1..3 proofs of one request share it exactly
--  and a re-deactivation after a cancel forms a distinct, queryable set. The link's
--  purpose is stamped by the ACTIVITY, not copied from the file, so the two may
--  legitimately disagree and the use-case layer is what rejects a mismatch.
--
--  Transfer proofs link to BOTH accounts (max 6 rows for 3 files). A transfer marks
--  the source TRANSFERRED and creates a new account; linking to only one would leave
--  the other's proof list wrong. transfers.proof_id is retained, set to the first
--  proof, so every existing reader of TransferRecord.proofId keeps working.
--
--  Rejected: a separate transfer_attachments table (a second join table and a second
--  read path for the account detail screen, which wants every proof at once); a
--  proof_group_id UUID minted per activity (a column that linked_at and transfer_id
--  already answer). Revisit proof_group_id only if an activity ever needs to span
--  transactions.
--
--  attachments.file_name: the original name was only ever baked into storage_key
--  ('<purpose>/<uuid>-<sanitized name>'), already stripped of spaces and non-ASCII,
--  so a client could not label a proof without parsing a storage path. Backfilled
--  from that suffix where it parses.
--
--  The 0..2 slot CHECK is added NOT VALID on purpose: it must bind every new insert,
--  but a legacy account that already accumulated four proofs must not make this
--  migration — and therefore app startup, and therefore every integration spec —
--  fail.
-- =====================================================================

ALTER TABLE attachments
    ADD COLUMN file_name TEXT;

-- storage_key is '<purpose>/<36-char uuid>-<name>'; 36 + 1 separator = start at 38.
UPDATE attachments
   SET file_name = substring(split_part(storage_key, '/', 2) FROM 38)
 WHERE file_name IS NULL
   AND split_part(storage_key, '/', 2) ~ '^[0-9a-fA-F-]{36}-.+$';

ALTER TABLE account_attachments
    ADD COLUMN purpose     attachment_purpose,
    ADD COLUMN sort_order  SMALLINT    NOT NULL DEFAULT 0,
    ADD COLUMN linked_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN linked_by   UUID REFERENCES users (id),
    ADD COLUMN transfer_id UUID REFERENCES transfers (id);

-- Backfill purpose from the file itself: for legacy rows that is exactly what the
-- link meant. This alone un-pollutes every existing account's subscription list
-- once the purpose-filtered read lands.
UPDATE account_attachments aa
   SET purpose = a.purpose
  FROM attachments a
 WHERE a.id = aa.attachment_id;

ALTER TABLE account_attachments
    ALTER COLUMN purpose SET NOT NULL;

-- Order legacy rows deterministically within (account, purpose) and date the link
-- from the file. Legacy links were made one at a time, so each correctly becomes
-- its own single-proof activity.
WITH ranked AS (
    SELECT aa.account_id,
           aa.attachment_id,
           a.created_at,
           row_number() OVER (
               PARTITION BY aa.account_id, aa.purpose
               ORDER BY a.created_at, aa.attachment_id
           ) - 1 AS rn
      FROM account_attachments aa
      JOIN attachments a ON a.id = aa.attachment_id
)
UPDATE account_attachments aa
   SET sort_order = r.rn,
       linked_at  = r.created_at
  FROM ranked r
 WHERE r.account_id    = aa.account_id
   AND r.attachment_id = aa.attachment_id;

-- Transfer proofs were never in this table. Pull each transfers.proof_id in against
-- BOTH the source and destination account, tagged with its transfer.
INSERT INTO account_attachments
       (account_id, attachment_id, purpose, sort_order, linked_at, linked_by, transfer_id)
SELECT acc.id, t.proof_id, 'transfer_proof'::attachment_purpose,
       0, t.transfer_date, t.requested_by_id, t.id
  FROM transfers t
  CROSS JOIN LATERAL (VALUES (t.old_account_id), (t.new_account_id)) AS acc (id)
 WHERE t.proof_id IS NOT NULL
    ON CONFLICT (account_id, attachment_id) DO NOTHING;

ALTER TABLE account_attachments
    ADD CONSTRAINT ck_account_attachment_transfer_purpose
        CHECK (transfer_id IS NULL OR purpose = 'transfer_proof');

ALTER TABLE account_attachments
    ADD CONSTRAINT ck_account_attachment_slot
        CHECK (sort_order >= 0 AND sort_order <= 2) NOT VALID;

CREATE INDEX idx_account_attachments_purpose
    ON account_attachments (account_id, purpose, sort_order);

CREATE INDEX idx_account_attachments_transfer
    ON account_attachments (transfer_id)
 WHERE transfer_id IS NOT NULL;

-- V1 created idx_attachments_entity, then nothing ever populated the columns.
-- Backfill so the index stops being dead weight and download scoping becomes
-- possible (SECURITY.md "Attachment access scoping"). This is a denormalized HINT,
-- last-writer-wins for a file linked to two accounts; account_attachments stays the
-- authoritative link.
UPDATE attachments a
   SET entity_type = 'account',
       entity_id   = pick.account_id
  FROM (
      SELECT DISTINCT ON (attachment_id) attachment_id, account_id
        FROM account_attachments
       ORDER BY attachment_id, linked_at DESC
  ) pick
 WHERE pick.attachment_id = a.id
   AND a.entity_id IS NULL;

UPDATE attachments a
   SET entity_type = 'store',
       entity_id   = s.id
  FROM stores s
 WHERE a.entity_id IS NULL
   AND (s.proof_of_installation_id = a.id OR s.proof_of_closure_id = a.id);
