-- =====================================================================
--  Seed OCR parsing templates (was the hardcoded KNOWN_TEMPLATES map in
--  server.ts). Editable by sysadmin via /api/v1/ocr/templates afterwards.
-- =====================================================================
INSERT INTO ocr_templates (config_key, isp_name, format_name, ai_prompt_instruction,
                           account_number_pattern, amount_pattern, due_date_pattern, invoice_number_pattern)
VALUES
  ('globe-corporate', 'Globe', 'Globe Corporate Layout',
   'Identify ''INNOVE COMMUNICATIONS'' or ''Globe Telecom''. Look for 8 or 9-digit Account Numbers starting with ''10'' or ''888''. Ensure MRC is extracted carefully under the section ''Current Charges''.',
   '\b(888\d{6}|10\d{7})\b',
   'Current Charges.*?\s*₱?\s*([\d,]+\.\d{2})',
   'Due Date.*?\s*(\w+\s+\d{1,2},\s*\d{4})',
   'Invoice Number.*?\s*(\d{10})'),

  ('globe-residential', 'Globe', 'Globe Residential Layout',
   'Look for ''Globe Landline & Broadband'' banner. Account Numbers starting with ''8'' or ''9''. Find the Monthly Recurring Fee amount in the summary section.',
   '\b(8\d{8}|9\d{8})\b',
   'Total Amount Due.*?\s*₱?\s*([\d,]+\.\d{2})',
   'Please pay on or before.*?\s*(\w+\s+\d{1,2},\s*\d{4})',
   'Statement No.*?\s*(\d{12})'),

  ('converge-flexibiz', 'Converge', 'Converge Flexibiz Lease Layout',
   'Look for 13-digit Account Numbers starting with ''0030''. Look for ''FLEXIBIZ'' plans. Match against branch names with high precision.',
   '\b(003030\d{7})\b',
   'Total Current Charges.*?\s*₱?\s*([\d,]+\.\d{2})',
   'Payment Due Date.*?\s*(\w+\s+\d{1,2},\s*\d{4})',
   'Document No.*?\s*(\d{10})'),

  ('converge-unibill', 'Converge', 'Converge Unibill Consolidated Layout',
   'Search for a tabular list of accounts. Each row represents a store with a 13-digit account number starting with ''0030''. Extract individual totals if multiple accounts present.',
   '\b(0030\d{9})\b',
   'Total Monthly Fee.*?\s*₱?\s*([\d,]+\.\d{2})',
   'Due Date.*?\s*(\d{2}/\d{2}/\d{4})',
   'Invoice No.*?\s*([A-Z0-9-]+)'),

  ('pldt-corporate', 'PLDT', 'PLDT Corporate Alpha Layout',
   'Look for ''PLDT Enterprise''. Access 10-digit Account Numbers starting with ''9'' or ''1''. Match branch MRC offsets specified in registered contracts.',
   '\b((?:10|9)\d{8})\b',
   'Total Current Billing.*?\s*₱?\s*([\d,]+\.\d{2})',
   'Please Pay By.*?\s*(\w+\s+\d{1,2},\s*\d{4})',
   'Statement Number.*?\s*(\d{10})'),

  ('pldt-home', 'PLDT', 'PLDT DSL/Fiber Home Layout',
   'Personal/micro-business PLDT Home bill layout. Expect 10-digit Account numbers. Find Total Amount Due in the top header summary.',
   '\b(0\d{9}|1\d{9})\b',
   'Amount Due.*?\s*₱?\s*([\d,]+\.\d{2})',
   'Due Date.*?\s*(\d{2}/\d{2}/\d{4})',
   'Reference No.*?\s*(\d{10})'),

  ('bayantel-legacy', 'Bayantel', 'Bayantel Legacy Paper Layout',
   'Search for ''Bayan Telecommunications''. Account codes pattern ''BYN-XXXXXX''. Verify historical MRC charges or overdue balance details.',
   '\b(BYN-\d{6})\b',
   'Total Amount Due.*?\s*₱?\s*([\d,]+\.\d{2})',
   'Pay Before.*?\s*(\w+\s+\d{1,2},\s*\d{4})',
   'Ref No.*?\s*([A-Z0-9]{8})')
ON CONFLICT (config_key) DO NOTHING;
