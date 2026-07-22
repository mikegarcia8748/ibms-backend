-- Arrears recovery on topsheet lines.
--
-- When an account's first (or an intervening) billing month is never compiled,
-- its prorated partial for those periods is "owed" but was previously lost, because
-- proration keys purely off the billing period. We now recover those un-billed prior
-- partials as a lumped amount folded onto the account's current-period line, flagged
-- for the Secretary to acknowledge during review.
--
--   arrears_amount  : the summed recovered partial (0 when none).
--   arrears_periods : comma-separated "YYYY-MM" list of the recovered periods. This
--                     doubles as the double-recovery guard — billedPeriodsByAccount
--                     unions these back in so a later run won't re-bill them.
ALTER TABLE topsheet_details
    ADD COLUMN arrears_amount NUMERIC(14,2) NOT NULL DEFAULT 0;
ALTER TABLE topsheet_details
    ADD COLUMN arrears_periods TEXT;
