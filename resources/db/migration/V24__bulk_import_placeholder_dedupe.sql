-- =====================================================================
--  Bulk import stops leaking placeholder attachments.
--
--  BulkImportAccountsUseCase needs an attachment to satisfy the NOT NULL FK on
--  stores.proof_of_installation_id, but bulk-imported stores carry no individual
--  proof document. It therefore created one placeholder row per run under a
--  hardcoded storage key -- never deduped, and the blob is never written to
--  storage at all. Every import added another row naming the same missing blob,
--  and on an idempotent re-import (every store reused) the new row was referenced
--  by nothing.
--
--  The use case now resolves the placeholder by its storage key and only inserts
--  when absent. This migration supports that lookup and clears the rows already
--  accumulated.
-- =====================================================================

-- Supports AttachmentRepository.findByStorageKey. NOT unique: real uploads mint
-- their own per-file keys (no collision), but the historical placeholder rows
-- deleted below may not all be removable, and a unique index would then fail the
-- migration. The index only needs to make the lookup cheap.
CREATE INDEX IF NOT EXISTS idx_attachments_storage_key ON attachments (storage_key);

-- Drop the accumulated placeholders that nothing points at.
--
-- Referenced rows are deliberately left alone: repointing a store's proof to a
-- different placeholder would rewrite history for no gain, and every FK here is
-- NO ACTION, so a missed reference would abort rather than cascade. All six
-- attachment FKs at V23 are enumerated -- if a later migration adds a seventh and
-- this file is ever replayed on a newer schema, the DELETE fails loudly, which is
-- the behaviour we want.
DELETE FROM attachments a
 WHERE a.storage_key = 'bulk-import/placeholder-installation-proof'
   AND NOT EXISTS (SELECT 1 FROM stores s                  WHERE s.proof_of_installation_id = a.id)
   AND NOT EXISTS (SELECT 1 FROM stores s                  WHERE s.proof_of_closure_id      = a.id)
   AND NOT EXISTS (SELECT 1 FROM account_attachments x     WHERE x.attachment_id            = a.id)
   AND NOT EXISTS (SELECT 1 FROM transfers t               WHERE t.proof_id                 = a.id)
   AND NOT EXISTS (SELECT 1 FROM ocr_batches b             WHERE b.source_id                = a.id)
   AND NOT EXISTS (SELECT 1 FROM account_change_requests r WHERE r.proof_attachment_id      = a.id);
