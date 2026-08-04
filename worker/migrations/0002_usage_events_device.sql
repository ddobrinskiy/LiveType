-- Which device reported the session.
--
-- The value is the device id whose secret authenticated the request — never
-- anything the device sent in the body. That is the same rule that makes the
-- model a server-side decision (ARCHITECTURE.md §3.2): a self-reported id would
-- turn the ledger into a self-report, and one device could write rows under
-- another's name.
--
-- Rows written before per-device secrets existed came from the single shared
-- DEVICE_SECRET, which now authenticates as the device id "default". The column
-- default backfills exactly that, so old history keeps belonging to the owner.
--
-- Unlike 0001 this migration cannot be written idempotently: SQLite has no
-- `ADD COLUMN IF NOT EXISTS`. It therefore relies solely on wrangler's record of
-- which migrations have run. Re-applying it fails loudly with
-- "duplicate column name: device_id" and changes nothing — noisy, not lossy.

ALTER TABLE usage_events ADD COLUMN device_id TEXT NOT NULL DEFAULT 'default';

-- Every query this column exists for is "one device over a time window", so
-- device_id leads and created_at_ms breaks the tie. The window-only index from
-- 0001 stays: the owner's breakdown scans a window across all devices.
CREATE INDEX IF NOT EXISTS idx_usage_events_device_created
  ON usage_events (device_id, created_at_ms);
