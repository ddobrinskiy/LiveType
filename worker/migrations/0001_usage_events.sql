-- One row per billable transcription, as reported by the device from the
-- OpenAI WebSocket event conversation.item.input_audio_transcription.completed.
--
-- item_id is OpenAI's own id for the committed audio buffer, so it doubles as
-- the idempotency key: a retried POST /usage is INSERT OR IGNORE'd and cannot
-- double-count.
--
-- The unit price is frozen into every row. Prices are read from the pricing
-- page by a human and edited into MODEL_PRICES; when one changes, history must
-- keep the price that was actually in force, so the aggregate queries never
-- re-price old rows.

CREATE TABLE IF NOT EXISTS usage_events (
  -- OpenAI item id, e.g. "item_E81t1mmrLaGrBlAjuBJp2".
  item_id                    TEXT    PRIMARY KEY,
  -- The model the worker had configured when the row was written. Never a
  -- value supplied by the device.
  model                      TEXT    NOT NULL,
  -- "duration" or "tokens": which usage shape OpenAI reported.
  usage_type                 TEXT    NOT NULL,
  -- The raw reported quantity: seconds for "duration", audio tokens for
  -- "tokens". Kept verbatim so a pricing mistake can be recomputed later.
  quantity                   REAL    NOT NULL,
  -- quantity normalised to seconds of audio (audio tokens / 600 * 60).
  billable_seconds           REAL    NOT NULL,
  -- Unit price in force at the time of the session, in integer micro-USD per
  -- minute (17000 == $0.017/min).
  price_micro_usd_per_minute INTEGER NOT NULL,
  -- 1 for the two token-billed models, whose audio-input token price OpenAI
  -- does not publish and whose per-minute figure is OpenAI's own estimate.
  price_estimated            INTEGER NOT NULL,
  -- Resolved cost in nano-USD. Nanos because one second of gpt-live-transcribe
  -- is 283333 nanos; rounding that to whole micro-dollars would lose 20%.
  usd_nanos                  INTEGER NOT NULL,
  -- Worker clock at ingest, epoch milliseconds. The device clock is not
  -- trusted for bucketing; only its UTC offset is.
  created_at_ms              INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_usage_events_created_at
  ON usage_events (created_at_ms);
