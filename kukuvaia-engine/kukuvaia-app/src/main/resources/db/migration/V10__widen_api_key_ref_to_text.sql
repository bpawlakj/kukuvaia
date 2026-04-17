-- Allow storing raw JWT tokens (>255 chars) in api_key_ref, not just env var names.
ALTER TABLE kukuvaia.providers ALTER COLUMN api_key_ref TYPE TEXT;
