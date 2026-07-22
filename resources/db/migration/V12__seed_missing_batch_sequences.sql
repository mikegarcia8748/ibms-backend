-- Backfill batch_sequences rows for providers that were created before the
-- bulk-import path learned to seed them (see BulkImportAccountsUseCase). Without
-- a row, CreateDraftTopSheetUseCase's batch-number minting throws
-- NoSuchElementException on the .single() lookup.
INSERT INTO batch_sequences (provider_id, current_value)
SELECT id, 0 FROM providers
ON CONFLICT (provider_id) DO NOTHING;
