-- =====================================================================
--  Pre-flight for the provider name case-insensitivity migration.
--
--  Run against PRODUCTION before promoting the staged migration in
--  docs/ops/V25__provider_name_case_insensitive.sql.staged.
--
--      psql -v ON_ERROR_STOP=1 -f docs/ops/provider-duplicate-preflight.sql
--
--  READ-ONLY: the whole script runs inside BEGIN TRANSACTION READ ONLY and ends in
--  ROLLBACK, so it is structurally incapable of writing.
--
--  This file lives under docs/ and NOT under resources/db/migration/ on purpose:
--  resources/ is the production resource root, so anything named V*.sql there is
--  picked up and executed by Flyway at boot.
--
--  Sections 3, 4 and 5 are the three conditions that make the migration abort --
--  and a Flyway failure blocks application startup. All three MUST come back
--  empty before deploying. Section 7 is the one-line go/no-go.
-- =====================================================================
BEGIN TRANSACTION READ ONLY;
SET LOCAL lock_timeout      = '5s';
SET LOCAL statement_timeout = '60s';

\echo ''
\echo '=== 1. Duplicate provider groups (merge key = lower(btrim(name))) ==='
-- Survivor = the OLDEST row in each group. Age is the right rule: the oldest row is
-- the one Finance has been billing against -- it holds the issued invoice numbers and
-- the topsheet history. The newer row is the one a bulk import minted by accident.
WITH grp AS (
    SELECT id, name, created_at, status,
           lower(btrim(name)) AS merge_key,
           first_value(id) OVER w                       AS survivor_id,
           count(*) OVER (PARTITION BY lower(btrim(name))) AS group_size
      FROM providers
    WINDOW w AS (PARTITION BY lower(btrim(name)) ORDER BY created_at, id)
)
SELECT merge_key, group_size, id, name, status, created_at,
       CASE WHEN id = survivor_id THEN 'SURVIVOR' ELSE 'merge into ' || survivor_id END AS action
  FROM grp
 WHERE group_size > 1
 ORDER BY merge_key, created_at, id;

\echo ''
\echo '=== 2. What each duplicate row drags with it ==='
WITH grp AS (
    SELECT id, name, lower(btrim(name)) AS merge_key,
           first_value(id) OVER (PARTITION BY lower(btrim(name)) ORDER BY created_at, id) AS survivor_id,
           count(*)        OVER (PARTITION BY lower(btrim(name)))                         AS group_size
      FROM providers
)
SELECT g.merge_key, g.name, (g.id = g.survivor_id) AS is_survivor,
       (SELECT count(*) FROM accounts a  WHERE a.provider_id = g.id)                                              AS accounts_all,
       (SELECT count(*) FROM accounts a  WHERE a.provider_id = g.id
                                           AND a.status NOT IN ('transferred','inactive'))                        AS accounts_live,
       (SELECT count(*) FROM topsheets t WHERE t.provider_id = g.id)                                              AS topsheets_all,
       (SELECT count(*) FROM topsheets t WHERE t.provider_id = g.id AND t.invoice_number IS NOT NULL)             AS topsheets_invoiced,
       (SELECT count(*) FROM topsheets t WHERE t.provider_id = g.id AND t.status = 'draft')                       AS topsheets_draft,
       (SELECT s.prefix        FROM invoice_sequences s WHERE s.provider_id = g.id)                               AS invoice_prefix,
       (SELECT s.current_value FROM invoice_sequences s WHERE s.provider_id = g.id)                               AS invoice_value,
       (SELECT b.current_value FROM batch_sequences  b WHERE b.provider_id = g.id)                                AS batch_value,
       (SELECT count(*) FROM ocr_templates o           WHERE o.provider_id     = g.id)                            AS ocr_templates,
       (SELECT count(*) FROM ocr_batches b             WHERE b.provider_id     = g.id)                            AS ocr_batches,
       (SELECT count(*) FROM account_change_requests r WHERE r.provider_id_new = g.id)                            AS change_requests
  FROM grp g
 WHERE g.group_size > 1
 ORDER BY g.merge_key, is_survivor DESC, g.name;

\echo ''
\echo '=== 3. BLOCKER -- account identity collisions (uq_account_identity_active, V16) ==='
-- The same (store, account number, circuit) LIVE under two spellings of one provider.
-- This is not an index technicality: it is one circuit on the books twice, billed twice
-- -- exactly the corruption the migration exists to stop. Which row is real is a business
-- decision, so the migration refuses rather than guessing. Section 8 drafts the fix.
WITH grp AS (
    SELECT id, first_value(id) OVER (PARTITION BY lower(btrim(name)) ORDER BY created_at, id) AS survivor_id
      FROM providers
), repointed AS (
    SELECT a.id, a.store_id, a.account_number, COALESCE(a.circuit_id,'') AS circ,
           g.survivor_id AS new_provider_id, a.provider_id AS old_provider_id,
           a.status, a.rate, a.created_at
      FROM accounts a JOIN grp g ON g.id = a.provider_id
     WHERE a.status NOT IN ('transferred','inactive')   -- = the partial index predicate
)
SELECT s.branch_code, r.account_number, r.circ,
       r.id AS account_id, p.name AS provider_spelling, r.status, r.rate, r.created_at
  FROM repointed r
  JOIN providers p ON p.id = r.old_provider_id
  JOIN stores    s ON s.id = r.store_id
 WHERE (r.new_provider_id, r.store_id, r.account_number, r.circ) IN (
        SELECT new_provider_id, store_id, account_number, circ
          FROM repointed
         GROUP BY new_provider_id, store_id, account_number, circ
        HAVING count(*) > 1)
 ORDER BY s.branch_code, r.account_number, r.circ, r.created_at;

\echo ''
\echo '=== 4. BLOCKER -- invoice number collisions (uq_topsheet_invoice_per_provider, V22) ==='
-- Case-duplicates ALWAYS share an acronym (InvoiceNumberFormatter uppercases), and each
-- counter starts at 1, so both spellings mint CONV-YYYYMM-0001. Legal today because V22
-- scoped uniqueness to (provider_id, invoice_number) -- but a violation the moment they
-- share a provider_id. An ISSUED invoice number cannot be renumbered (Finance holds it on
-- paper, against a cheque), so such a group must NOT be merged: give the two providers
-- genuinely distinct names via PATCH /providers/{id} and re-run this report instead.
WITH grp AS (
    SELECT id, first_value(id) OVER (PARTITION BY lower(btrim(name)) ORDER BY created_at, id) AS survivor_id
      FROM providers
), repointed AS (
    SELECT t.id, t.invoice_number, t.status, g.survivor_id AS new_provider_id, t.provider_name
      FROM topsheets t JOIN grp g ON g.id = t.provider_id
     WHERE t.invoice_number IS NOT NULL    -- NULLs are distinct in the index (drafts)
)
SELECT new_provider_id, invoice_number, count(*) AS copies,
       array_agg(id            ORDER BY id) AS topsheet_ids,
       array_agg(provider_name ORDER BY id) AS spellings,
       array_agg(status::text  ORDER BY id) AS statuses
  FROM repointed
 GROUP BY new_provider_id, invoice_number
HAVING count(*) > 1
 ORDER BY invoice_number;

\echo ''
\echo '=== 5. BLOCKER -- duplicate drafts (uq_draft_per_provider_period, V11) ==='
-- One active DRAFT per provider/period. Two drafts, one per spelling, collide on repoint.
-- Drafts are unconfirmed but they are a secretary's in-progress work, so resolve this in
-- the app (cancel or confirm one) -- the migration will not delete it.
WITH grp AS (
    SELECT id, first_value(id) OVER (PARTITION BY lower(btrim(name)) ORDER BY created_at, id) AS survivor_id
      FROM providers
)
SELECT g.survivor_id, t.billing_period, count(*) AS drafts,
       array_agg(t.id            ORDER BY t.id) AS topsheet_ids,
       array_agg(t.provider_name ORDER BY t.id) AS spellings
  FROM topsheets t JOIN grp g ON g.id = t.provider_id
 WHERE t.status = 'draft'
 GROUP BY g.survivor_id, t.billing_period
HAVING count(*) > 1;

\echo ''
\echo '=== 6. Sequence state (informational: is a loser AHEAD of its survivor?) ==='
-- These counters are monotonic per provider; the period only appears in the formatted
-- string. If a loser is ahead and the survivor kept its own lower value, the next confirms
-- would re-mint numbers already printed on the loser topsheets -- which after the repoint
-- sit under the SAME provider. For invoices that is an immediate 23505 blocking Finance;
-- for batch numbers it is worse, because batch_number has only a plain index (V9), so a
-- reused batch number raises nothing and two topsheets silently share one. The migration
-- therefore raises the survivor to the group MAX. This section is how you verify that.
WITH grp AS (
    SELECT id, lower(btrim(name)) AS merge_key,
           first_value(id) OVER (PARTITION BY lower(btrim(name)) ORDER BY created_at, id) AS survivor_id,
           count(*)        OVER (PARTITION BY lower(btrim(name)))                         AS group_size
      FROM providers
)
SELECT g.merge_key,
       max(inv.current_value) FILTER (WHERE g.id  = g.survivor_id) AS invoice_survivor,
       max(inv.current_value) FILTER (WHERE g.id <> g.survivor_id) AS invoice_loser_max,
       max(bat.current_value) FILTER (WHERE g.id  = g.survivor_id) AS batch_survivor,
       max(bat.current_value) FILTER (WHERE g.id <> g.survivor_id) AS batch_loser_max
  FROM grp g
  LEFT JOIN invoice_sequences inv ON inv.provider_id = g.id
  LEFT JOIN batch_sequences   bat ON bat.provider_id = g.id
 WHERE g.group_size > 1
 GROUP BY g.merge_key;

\echo ''
\echo '=== 7. GO / NO-GO -- all three collision counts must be 0 ==='
WITH grp AS (
    SELECT id, first_value(id) OVER (PARTITION BY lower(btrim(name)) ORDER BY created_at, id) AS survivor_id,
           count(*) OVER (PARTITION BY lower(btrim(name))) AS group_size
      FROM providers
), acc AS (
    SELECT a.store_id, a.account_number, COALESCE(a.circuit_id,'') AS circ, g.survivor_id
      FROM accounts a JOIN grp g ON g.id = a.provider_id
     WHERE a.status NOT IN ('transferred','inactive')
), inv AS (
    SELECT t.invoice_number, g.survivor_id
      FROM topsheets t JOIN grp g ON g.id = t.provider_id
     WHERE t.invoice_number IS NOT NULL
), dft AS (
    SELECT t.billing_period, g.survivor_id
      FROM topsheets t JOIN grp g ON g.id = t.provider_id
     WHERE t.status = 'draft'
)
SELECT (SELECT count(*) FROM grp WHERE group_size > 1 AND id <> survivor_id)                                                     AS providers_to_merge,
       (SELECT count(*) FROM (SELECT 1 FROM acc GROUP BY survivor_id, store_id, account_number, circ HAVING count(*) > 1) z)     AS account_collisions,
       (SELECT count(*) FROM (SELECT 1 FROM inv GROUP BY survivor_id, invoice_number HAVING count(*) > 1) z)                     AS invoice_collisions,
       (SELECT count(*) FROM (SELECT 1 FROM dft GROUP BY survivor_id, billing_period HAVING count(*) > 1) z)                     AS draft_collisions;

\echo ''
\echo '=== 8. Draft remediation for section 3, FOR HUMAN REVIEW -- emitted as text, not executed ==='
-- Read section 3 first. Delete the statements for the rows you want to KEEP, then run what
-- remains manually. NEVER run this wholesale: deactivating the wrong row is silent
-- UNDER-billing, a quieter failure than the double-billing being fixed. The default below
-- keeps the OLDEST account in each collision and deactivates the rest.
WITH grp AS (
    SELECT id, first_value(id) OVER (PARTITION BY lower(btrim(name)) ORDER BY created_at, id) AS survivor_id
      FROM providers
), repointed AS (
    SELECT a.id, a.store_id, a.account_number, COALESCE(a.circuit_id,'') AS circ,
           g.survivor_id AS new_provider_id, a.provider_id AS old_provider_id, a.created_at
      FROM accounts a JOIN grp g ON g.id = a.provider_id
     WHERE a.status NOT IN ('transferred','inactive')
), collided AS (
    SELECT r.*,
           row_number() OVER (PARTITION BY r.new_provider_id, r.store_id, r.account_number, r.circ
                              ORDER BY r.created_at, r.id) AS rn,
           first_value(r.id) OVER (PARTITION BY r.new_provider_id, r.store_id, r.account_number, r.circ
                                   ORDER BY r.created_at, r.id) AS keep_id
      FROM repointed r
     WHERE (r.new_provider_id, r.store_id, r.account_number, r.circ) IN (
            SELECT new_provider_id, store_id, account_number, circ FROM repointed
             GROUP BY new_provider_id, store_id, account_number, circ HAVING count(*) > 1)
)
SELECT format(
    'UPDATE accounts SET status = ''inactive'', notes = COALESCE(notes || E''\n'', '''') || ''Deactivated %s: duplicate of account %s, created under the "%s" spelling before the provider merge.'' WHERE id = ''%s'';',
    current_date, c.keep_id, p.name, c.id) AS remediation_sql
  FROM collided c JOIN providers p ON p.id = c.old_provider_id
 WHERE c.rn > 1;

ROLLBACK;
