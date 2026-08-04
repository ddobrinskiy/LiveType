export interface Env {
  OPENAI_API_KEY: string;
  /**
   * A single shared secret, which authenticates as the device id "default" — the
   * same id `0002`'s column default gives rows written before that column
   * existed. A one-phone install needs only this.
   */
  DEVICE_SECRET?: string;
  /**
   * JSON object of device id -> secret, e.g. `{"david":"…","mom":"…"}`. This is
   * how a second person gets access without holding the owner's secret, and how
   * the ledger learns whose spend a row is. Ids match DEVICE_ID_PATTERN.
   *
   * Adding or revoking a device is one `wrangler secret put DEVICE_SECRETS`; no
   * code change and no schema change is involved.
   */
  DEVICE_SECRETS?: string;
  /**
   * Which device id may read the per-device breakdown from `GET /usage`.
   * Defaults to "default" when that device exists, otherwise to nobody.
   */
  OWNER_DEVICE_ID?: string;
  /**
   * JSON object of device id -> **daily** spend cap in USD, e.g. `{"mom":1}`. A
   * device with no entry here is uncapped. Enforced by `POST /token`, which is
   * the only place spending can be prevented rather than merely recorded.
   */
  DEVICE_CAPS?: string;
  /**
   * Which timezone the cap's day boundary follows, as minutes to add to UTC
   * (Moscow `180`). Defaults to UTC.
   *
   * Deliberately a server-side setting rather than the `tz_offset_minutes` the
   * phone sends to `GET /usage`: a device that could choose its own day boundary
   * could shift the window and hand itself a fresh allowance, which is exactly
   * the leverage over cost that §3.2 keeps away from the device.
   */
  CAP_TZ_OFFSET_MINUTES?: string;
  /** Optional override; must be a key of SUPPORTED_MODELS. */
  TRANSCRIPTION_MODEL?: string;
  /** Usage ledger. Schema lives in worker/migrations/. */
  DB?: D1Database;
}

const OPENAI_CLIENT_SECRET_URL =
  "https://api.openai.com/v1/realtime/client_secrets";

const jsonHeaders = {
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
};

// Not exported: workerd inspects every named export of the entry module and
// rejects any that is not a function or ExportedHandler, so a bare string
// export fails the Worker at startup. Tests reach it via defaultModel().
const DEFAULT_MODEL = "gpt-live-transcribe";

export function defaultModel(): string {
  return DEFAULT_MODEL;
}

/**
 * Transcription models this worker may select, and which hints each one
 * tolerates. OpenAI rejects the whole client_secrets request with a 400 when an
 * unsupported hint is present, so unsupported hints are dropped rather than
 * forwarded. Verified against the live API on 2026-08-01.
 */
const SUPPORTED_MODELS: Record<
  string,
  { languages: boolean; prompt: boolean; keywords: boolean }
> = {
  "gpt-live-transcribe": { languages: true, prompt: true, keywords: true },
  "gpt-transcribe": { languages: true, prompt: true, keywords: true },
  "gpt-4o-transcribe": { languages: false, prompt: true, keywords: false },
  "gpt-4o-mini-transcribe": { languages: false, prompt: true, keywords: false },
  "gpt-realtime-whisper": { languages: false, prompt: false, keywords: false },
  "whisper-1": { languages: false, prompt: true, keywords: false },
};

/**
 * How each model is billed and what it costs, in integer micro-USD per minute
 * so the unit price itself never suffers float drift. Transcribed from
 * https://developers.openai.com/api/docs/pricing on 2026-08-01.
 *
 * `estimated` marks the two models whose *audio-input token* price OpenAI does
 * not publish. For those, the per-minute figure is OpenAI's own "estimated
 * cost" column, and the UI must say so rather than imply cent-accuracy.
 *
 * Every key of SUPPORTED_MODELS must appear here; a test enforces that.
 */
const MODEL_PRICES: Record<
  string,
  {
    microUsdPerMinute: number;
    billedBy: "duration" | "tokens";
    estimated: boolean;
  }
> = {
  "gpt-live-transcribe": {
    microUsdPerMinute: 17000,
    billedBy: "duration",
    estimated: false,
  },
  "gpt-transcribe": {
    microUsdPerMinute: 4500,
    billedBy: "duration",
    estimated: false,
  },
  "gpt-4o-transcribe": {
    microUsdPerMinute: 6000,
    billedBy: "tokens",
    estimated: true,
  },
  "gpt-4o-mini-transcribe": {
    microUsdPerMinute: 3000,
    billedBy: "tokens",
    estimated: true,
  },
  "gpt-realtime-whisper": {
    microUsdPerMinute: 17000,
    billedBy: "duration",
    estimated: false,
  },
  "whisper-1": {
    microUsdPerMinute: 6000,
    billedBy: "duration",
    estimated: false,
  },
};

export interface ModelPrice {
  microUsdPerMinute: number;
  billedBy: "duration" | "tokens";
  estimated: boolean;
}

/** Throws for anything off the allowlist, exactly like resolveModel(). */
export function priceFor(model: string): ModelPrice {
  if (!Object.prototype.hasOwnProperty.call(MODEL_PRICES, model)) {
    throw new Error(`No price is known for model "${model}"`);
  }
  return MODEL_PRICES[model];
}

/** Every model this worker may select. Exported as a function, not a value. */
export function supportedModels(): string[] {
  return Object.keys(SUPPORTED_MODELS);
}

/**
 * Measured exactly, across three sample lengths and both token-billed models,
 * on 2026-08-01: 10 audio tokens per second, dead linear.
 */
const AUDIO_TOKENS_PER_MINUTE = 600;

export function audioTokensPerMinute(): number {
  return AUDIO_TOKENS_PER_MINUTE;
}

const MAX_LANGUAGES = 8;
const MAX_LANGUAGE_CHARS = 16;
const MAX_KEYWORDS = 100;
const MAX_KEYWORD_CHARS = 64;
const MAX_PROMPT_CHARS = 2000;

export interface SessionHints {
  languages: string[];
  prompt: string;
  keywords: string[];
}

/**
 * The model is a server-side decision. Anything the device sends is treated as
 * a hint only; a `model` field in the request body is ignored.
 */
export function resolveModel(configured: string | undefined): string {
  const model = configured?.trim() || DEFAULT_MODEL;
  if (!Object.prototype.hasOwnProperty.call(SUPPORTED_MODELS, model)) {
    throw new Error(`Unsupported TRANSCRIPTION_MODEL "${model}"`);
  }
  return model;
}

function stringList(value: unknown, maxItems: number, maxChars: number): string[] {
  if (!Array.isArray(value)) return [];
  const out: string[] = [];
  for (const entry of value) {
    if (typeof entry !== "string") continue;
    const trimmed = entry.trim();
    if (trimmed.length === 0 || trimmed.length > maxChars) continue;
    if (out.includes(trimmed)) continue;
    out.push(trimmed);
    if (out.length === maxItems) break;
  }
  return out;
}

export function parseHints(raw: unknown): SessionHints {
  const source =
    raw && typeof raw === "object" && !Array.isArray(raw)
      ? (raw as Record<string, unknown>)
      : {};
  const prompt = typeof source.prompt === "string" ? source.prompt.trim() : "";
  return {
    languages: stringList(source.languages, MAX_LANGUAGES, MAX_LANGUAGE_CHARS),
    prompt: prompt.slice(0, MAX_PROMPT_CHARS),
    keywords: stringList(source.keywords, MAX_KEYWORDS, MAX_KEYWORD_CHARS),
  };
}

export function createSessionRequest(
  model: string,
  hints: SessionHints = { languages: [], prompt: "", keywords: [] },
): Record<string, unknown> {
  const supports = SUPPORTED_MODELS[model];
  const transcription: Record<string, unknown> = { model };

  if (supports.languages && hints.languages.length > 0) {
    transcription.languages = hints.languages;
  }
  if (supports.prompt && hints.prompt.length > 0) {
    transcription.prompt = hints.prompt;
  }
  if (supports.keywords && hints.keywords.length > 0) {
    transcription.keywords = hints.keywords;
  }

  return {
    expires_after: {
      anchor: "created_at",
      seconds: 60,
    },
    session: {
      type: "transcription",
      audio: {
        input: {
          format: {
            type: "audio/pcm",
            rate: 24000,
          },
          noise_reduction: {
            type: "near_field",
          },
          transcription,
          turn_detection: null,
        },
      },
    },
  };
}

/* ------------------------------------------------------------------ usage */

/**
 * A single billable transcription, as reported by the device and normalised
 * here. `quantity` is what OpenAI said (seconds, or audio tokens);
 * `billableSeconds` is that converted to seconds of audio, which is the unit
 * every price in MODEL_PRICES is quoted in.
 */
export interface UsageReport {
  itemId: string;
  usageType: "duration" | "tokens";
  quantity: number;
  billableSeconds: number;
}

export type UsageReportResult =
  | { ok: true; report: UsageReport }
  | { ok: false; error: string };

/** OpenAI item ids look like `item_E81t1mmrLaGrBlAjuBJp2`. */
const ITEM_ID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;

/**
 * One committed audio buffer cannot plausibly be four hours long. Bounding it
 * stops a compromised device from poisoning the chart with one huge row.
 */
const MAX_BILLABLE_SECONDS = 14400;

function nonNegativeNumber(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    return null;
  }
  return value;
}

/**
 * Parses the payload the device forwards from the WebSocket
 * `conversation.item.input_audio_transcription.completed` event. Only
 * `item_id` and `usage` are read; every other field of the event (`event_id`,
 * `transcript`, `content_index`, and notably any `model` or price the device
 * cared to invent) is ignored.
 *
 * Both usage shapes are accepted regardless of the model currently configured:
 * TRANSCRIPTION_MODEL can change while a session is still in flight, and the
 * unit conversion is fixed, so accepting the other shape loses no accuracy and
 * grants the device no leverage over the price.
 */
export function parseUsageReport(raw: unknown): UsageReportResult {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, error: "Malformed body" };
  }
  const body = raw as Record<string, unknown>;

  const itemId = typeof body.item_id === "string" ? body.item_id.trim() : "";
  if (!ITEM_ID_PATTERN.test(itemId)) {
    return { ok: false, error: "Invalid item_id" };
  }

  const usage = body.usage;
  if (!usage || typeof usage !== "object" || Array.isArray(usage)) {
    return { ok: false, error: "Missing usage" };
  }
  const fields = usage as Record<string, unknown>;

  if (fields.type === "duration") {
    const seconds = nonNegativeNumber(fields.seconds);
    if (seconds === null) {
      return { ok: false, error: "Invalid usage.seconds" };
    }
    if (seconds > MAX_BILLABLE_SECONDS) {
      return { ok: false, error: "usage.seconds out of range" };
    }
    return {
      ok: true,
      report: {
        itemId,
        usageType: "duration",
        quantity: seconds,
        billableSeconds: seconds,
      },
    };
  }

  if (fields.type === "tokens") {
    const details = fields.input_token_details;
    if (!details || typeof details !== "object" || Array.isArray(details)) {
      return { ok: false, error: "Missing usage.input_token_details" };
    }
    const audioTokens = nonNegativeNumber(
      (details as Record<string, unknown>).audio_tokens,
    );
    if (audioTokens === null) {
      return { ok: false, error: "Invalid usage.input_token_details.audio_tokens" };
    }
    const billableSeconds = (audioTokens / AUDIO_TOKENS_PER_MINUTE) * 60;
    if (billableSeconds > MAX_BILLABLE_SECONDS) {
      return { ok: false, error: "audio_tokens out of range" };
    }
    return {
      ok: true,
      report: {
        itemId,
        usageType: "tokens",
        quantity: audioTokens,
        billableSeconds,
      },
    };
  }

  return { ok: false, error: "Unsupported usage.type" };
}

/**
 * Cost of one report in nano-USD. Nanos, not micros, because a one-second
 * session at $0.017/min is 283333.33 nanos — rounding that to a whole
 * micro-dollar would lose 20% of it.
 */
export function usdNanosFor(
  billableSeconds: number,
  microUsdPerMinute: number,
): number {
  return Math.round((billableSeconds / 60) * microUsdPerMinute * 1000);
}

const DAY_MS = 86_400_000;

/**
 * UTC-14:00 does not exist; UTC+14:00 (Kiritimati) does. ±840 covers every
 * real zone with room to spare, and anything outside it is a bug or an attack.
 */
const MAX_TZ_OFFSET_MINUTES = 840;

/**
 * `tz_offset_minutes` is minutes to ADD to UTC to get the device's local time
 * — i.e. what Java's `TimeZone.getOffset(now) / 60000` returns. Moscow is
 * +180, New York in winter is -300. Anything unparseable, non-integer or out
 * of range silently falls back to UTC; the value actually applied is echoed in
 * the response so the client can tell.
 */
export function resolveTzOffsetMinutes(raw: string | null): number {
  if (raw === null) return 0;
  const trimmed = raw.trim();
  if (!/^[+-]?\d{1,4}$/.test(trimmed)) return 0;
  const value = Number(trimmed);
  if (!Number.isInteger(value) || Math.abs(value) > MAX_TZ_OFFSET_MINUTES) {
    return 0;
  }
  return value;
}

/** Start of the device's local day, as a UTC epoch-millisecond instant. */
export function localDayStartMs(nowMs: number, tzOffsetMinutes: number): number {
  const offsetMs = tzOffsetMinutes * 60_000;
  return Math.floor((nowMs + offsetMs) / DAY_MS) * DAY_MS - offsetMs;
}

/**
 * Window lower bounds, inclusive. The windows are whole *local calendar days*
 * including today, so "last 7 days" is today plus the six before it — not the
 * last 168 hours.
 */
export function windowStartsMs(
  nowMs: number,
  tzOffsetMinutes: number,
): { today: number; last7d: number; last30d: number } {
  const today = localDayStartMs(nowMs, tzOffsetMinutes);
  return {
    today,
    last7d: today - 6 * DAY_MS,
    last30d: today - 29 * DAY_MS,
  };
}

async function sha256(value: string): Promise<Uint8Array> {
  const bytes = new TextEncoder().encode(value);
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
}

export async function secureEquals(
  supplied: string | null,
  expected: string,
): Promise<boolean> {
  if (
    supplied === null ||
    supplied.length === 0 ||
    supplied.length > 512 ||
    expected.length === 0
  ) {
    return false;
  }

  const [left, right] = await Promise.all([sha256(supplied), sha256(expected)]);
  let mismatch = left.length ^ right.length;
  for (let index = 0; index < Math.min(left.length, right.length); index += 1) {
    mismatch |= left[index] ^ right[index];
  }
  return mismatch === 0;
}

function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: jsonHeaders,
  });
}

/* ---------------------------------------------------------------- devices */

/** The device id the original single `DEVICE_SECRET` authenticates as. */
const LEGACY_DEVICE_ID = "default";

/**
 * Device ids are lowercase and boring on purpose: they go into the ledger, into
 * a JSON response and into the billing screen, and they are typed by hand into
 * a `wrangler secret put` payload.
 */
const DEVICE_ID_PATTERN = /^[a-z0-9_-]{1,32}$/;

/** `openssl rand -hex 16` clears this comfortably. */
const MIN_DEVICE_SECRET_CHARS = 24;

/**
 * secureEquals refuses anything longer than this, so a longer configured secret
 * could never match and the device would be locked out with no explanation.
 * Rejecting it at parse time turns that into a loud misconfiguration instead.
 */
const MAX_DEVICE_SECRET_CHARS = 512;

/** Sanity bound. A cap this size is a typo, not a budget. */
const MAX_CAP_USD = 10_000;

/** Device id -> secret. */
export type DeviceRegistry = ReadonlyMap<string, string>;

/** Device id -> daily cap in integer micro-USD. Absent means uncapped. */
export type CapRegistry = ReadonlyMap<string, number>;

export interface DeviceConfig {
  devices: DeviceRegistry;
  caps: CapRegistry;
  /** Who may see every device's spend. Null when no owner is identifiable. */
  ownerDeviceId: string | null;
  /** Minutes to add to UTC to find the cap's day boundary. */
  capTzOffsetMinutes: number;
}

export type DeviceConfigResult =
  | { ok: true; config: DeviceConfig }
  | { ok: false; error: string };

/**
 * Reads the whole device half of the configuration in one go, and refuses to
 * return a partially-understood version of it.
 *
 * Every rejection here surfaces as a 500 "Worker is misconfigured" on every
 * route, exactly as a bad TRANSCRIPTION_MODEL already does. That is deliberate:
 * the failure this must never have is a typo that silently leaves a device
 * uncapped or unauthenticated. A worker that is loudly down is recoverable with
 * one `wrangler secret put`; money spent under a cap that was quietly ignored is
 * not.
 *
 * Note what is *not* an error: no configured secrets at all. That yields an
 * empty registry, which matches nothing, so every request gets a 401 — the same
 * fail-closed behaviour a blank DEVICE_SECRET has always had.
 */
export function parseDeviceConfig(env: Env): DeviceConfigResult {
  const devices = new Map<string, string>();

  const raw = env.DEVICE_SECRETS?.trim();
  if (raw) {
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      return { ok: false, error: "DEVICE_SECRETS is not valid JSON" };
    }
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return { ok: false, error: "DEVICE_SECRETS must be a JSON object" };
    }
    for (const [id, secret] of Object.entries(parsed as Record<string, unknown>)) {
      if (!DEVICE_ID_PATTERN.test(id)) {
        return { ok: false, error: `DEVICE_SECRETS has an invalid device id` };
      }
      if (
        typeof secret !== "string" ||
        secret.length < MIN_DEVICE_SECRET_CHARS ||
        secret.length > MAX_DEVICE_SECRET_CHARS
      ) {
        return { ok: false, error: `Device "${id}" has an unusable secret` };
      }
      devices.set(id, secret);
    }
  }

  // Deliberately not length-checked: this secret predates the rule and is
  // already deployed. Enforcing MIN_DEVICE_SECRET_CHARS on it could lock the
  // owner out of their own worker on the deploy that introduced the check.
  const legacy = env.DEVICE_SECRET ?? "";
  if (legacy.trim().length > 0) {
    if (devices.has(LEGACY_DEVICE_ID)) {
      return {
        ok: false,
        error: `DEVICE_SECRET and DEVICE_SECRETS both define "${LEGACY_DEVICE_ID}"`,
      };
    }
    devices.set(LEGACY_DEVICE_ID, legacy);
  }

  // Two devices sharing a secret would make the ledger's device_id a coin flip
  // (the loop below has no way to tell them apart), which is worse than useless.
  // The message names no id: which pair collided is not worth leaking.
  const secrets = new Set<string>();
  for (const secret of devices.values()) {
    if (secrets.has(secret)) {
      return { ok: false, error: "Two devices are configured with the same secret" };
    }
    secrets.add(secret);
  }

  const caps = new Map<string, number>();
  const rawCaps = env.DEVICE_CAPS?.trim();
  if (rawCaps) {
    let parsed: unknown;
    try {
      parsed = JSON.parse(rawCaps);
    } catch {
      return { ok: false, error: "DEVICE_CAPS is not valid JSON" };
    }
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return { ok: false, error: "DEVICE_CAPS must be a JSON object" };
    }
    for (const [id, value] of Object.entries(parsed as Record<string, unknown>)) {
      // A cap naming a device that cannot authenticate is a typo, and the typo
      // that costs money is the mirror of it — the device whose cap you meant to
      // write and misspelled is uncapped. So this fails rather than warns, which
      // means removing a device means removing its cap in the same breath.
      if (!devices.has(id)) {
        return { ok: false, error: `DEVICE_CAPS names an unknown device "${id}"` };
      }
      if (
        typeof value !== "number" ||
        !Number.isFinite(value) ||
        value < 0 ||
        value > MAX_CAP_USD
      ) {
        return { ok: false, error: `Device "${id}" has an invalid cap` };
      }
      caps.set(id, Math.round(value * 1_000_000));
    }
  }

  const configuredOwner = env.OWNER_DEVICE_ID?.trim();
  let ownerDeviceId: string | null;
  if (configuredOwner) {
    // Explicitly named and absent is a typo worth shouting about: it would
    // silently leave nobody able to see the breakdown.
    if (!devices.has(configuredOwner)) {
      return { ok: false, error: `OWNER_DEVICE_ID names an unknown device` };
    }
    ownerDeviceId = configuredOwner;
  } else {
    // Unset falls back to the legacy device *if it exists*. Defaulting to the
    // string "default" unconditionally would 500 every install that has moved
    // entirely to named secrets.
    ownerDeviceId = devices.has(LEGACY_DEVICE_ID) ? LEGACY_DEVICE_ID : null;
  }

  // Reuses the query-parameter rule: unparseable or out of range falls back to
  // UTC rather than failing, because a wrong day boundary still caps spend
  // while a dead worker stops the owner dictating.
  const capTzOffsetMinutes = resolveTzOffsetMinutes(
    env.CAP_TZ_OFFSET_MINUTES ?? null,
  );

  return {
    ok: true,
    config: { devices, caps, ownerDeviceId, capTzOffsetMinutes },
  };
}

export type AuthOutcome =
  | { ok: true; deviceId: string }
  | { ok: false; reason: "unauthorized" };

/**
 * The single device check. Every route uses this one — /usage must never grow
 * its own copy that drifts from /token's.
 *
 * Returns *which* device authenticated, because that identity is the only
 * trustworthy source for the ledger's device_id and for the cap lookup.
 */
export async function authoriseDevice(
  request: Request,
  config: DeviceConfig,
): Promise<AuthOutcome> {
  const supplied = request.headers.get("X-Device-Secret");

  // Every entry is compared and the loop does not break early, so its cost does
  // not depend on which device matched. secureEquals already compares SHA-256
  // digests rather than the secrets themselves.
  let deviceId: string | null = null;
  for (const [id, secret] of config.devices) {
    if (await secureEquals(supplied, secret)) deviceId = id;
  }

  return deviceId === null
    ? { ok: false, reason: "unauthorized" }
    : { ok: true, deviceId };
}

const UNAUTHORIZED = { error: "Unauthorized" };
const MISCONFIGURED = { error: "Worker is misconfigured" };

interface WindowRow {
  seconds: number;
  nanos: number;
  sessions: number;
}

const WINDOW_SQL = `SELECT COALESCE(SUM(billable_seconds), 0) AS seconds,
       COALESCE(SUM(usd_nanos), 0)       AS nanos,
       COUNT(*)                          AS sessions
  FROM usage_events
 WHERE device_id = ?1 AND created_at_ms >= ?2 AND created_at_ms <= ?3`;

/** The same three windows, every device at once. Owner's view only. */
const BREAKDOWN_SQL = `SELECT device_id,
       COALESCE(SUM(billable_seconds), 0) AS seconds,
       COALESCE(SUM(usd_nanos), 0)        AS nanos,
       COUNT(*)                           AS sessions
  FROM usage_events
 WHERE created_at_ms >= ?1 AND created_at_ms <= ?2
 GROUP BY device_id`;

/** One device's spend since an instant. Backs the daily cap check. */
const DEVICE_SPEND_SQL = `SELECT COALESCE(SUM(usd_nanos), 0) AS nanos
  FROM usage_events
 WHERE device_id = ?1 AND created_at_ms >= ?2`;

function windowSummary(row: WindowRow | null) {
  const nanos = row?.nanos ?? 0;
  const usdMicros = Math.round(nanos / 1000);
  return {
    seconds: Math.round((row?.seconds ?? 0) * 1000) / 1000,
    usd: usdMicros / 1_000_000,
    usd_micros: usdMicros,
    sessions: row?.sessions ?? 0,
  };
}

export interface CapState {
  capMicros: number;
  spentMicros: number;
  tzOffsetMinutes: number;
}

/**
 * Today's spend for one device, or null if the ledger could not be read.
 *
 * Null is never treated as zero by callers: a cap that evaporates when D1
 * hiccups is not a cap. Only devices that actually have a cap ever reach this
 * function, so an uncapped device — the owner's own phone — pays neither the
 * latency nor the failure mode.
 */
async function readCapState(
  db: D1Database,
  deviceId: string,
  capMicros: number,
  nowMs: number,
  tzOffsetMinutes: number,
): Promise<CapState | null> {
  try {
    const row = await db
      .prepare(DEVICE_SPEND_SQL)
      .bind(deviceId, localDayStartMs(nowMs, tzOffsetMinutes))
      .first<{ nanos: number }>();
    return {
      capMicros,
      spentMicros: Math.round((row?.nanos ?? 0) / 1000),
      tzOffsetMinutes,
    };
  } catch {
    return null;
  }
}

function capSummary(state: CapState) {
  const remaining = Math.max(0, state.capMicros - state.spentMicros);
  return {
    usd: state.capMicros / 1_000_000,
    usd_micros: state.capMicros,
    spent_usd: state.spentMicros / 1_000_000,
    spent_usd_micros: state.spentMicros,
    remaining_usd: remaining / 1_000_000,
    remaining_usd_micros: remaining,
    // The period is named, and its boundary published, because it is the
    // *worker's* day and need not be the same day as the `today` window above —
    // that one follows the timezone the phone reported.
    period: "day",
    period_tz_offset_minutes: state.tzOffsetMinutes,
  };
}

async function handleUsagePost(request: Request, env: Env): Promise<Response> {
  const parsedConfig = parseDeviceConfig(env);
  if (!parsedConfig.ok) return jsonResponse(MISCONFIGURED, 500);

  const auth = await authoriseDevice(request, parsedConfig.config);
  if (!auth.ok) return jsonResponse(UNAUTHORIZED, 401);

  let model: string;
  try {
    model = resolveModel(env.TRANSCRIPTION_MODEL);
  } catch {
    return jsonResponse(MISCONFIGURED, 500);
  }
  const db = env.DB;
  if (!db) return jsonResponse(MISCONFIGURED, 500);

  const parsed = parseUsageReport(await request.json().catch(() => null));
  if (!parsed.ok) {
    return jsonResponse({ error: parsed.error }, 400);
  }
  const report = parsed.report;

  // The model and the price are decided here, from server configuration only,
  // exactly as /token decides the model. Nothing the device sent is consulted.
  const price = priceFor(model);
  const usdNanos = usdNanosFor(report.billableSeconds, price.microUsdPerMinute);

  try {
    // item_id is the primary key, so a retried POST is a no-op rather than a
    // double charge. The price is frozen into the row: a later price change
    // must not silently rewrite history.
    const result = await db
      .prepare(
        `INSERT OR IGNORE INTO usage_events
           (item_id, model, usage_type, quantity, billable_seconds,
            price_micro_usd_per_minute, price_estimated, usd_nanos,
            created_at_ms, device_id)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)`,
      )
      .bind(
        report.itemId,
        model,
        report.usageType,
        report.quantity,
        report.billableSeconds,
        price.microUsdPerMinute,
        price.estimated ? 1 : 0,
        usdNanos,
        Date.now(),
        // The authenticated identity, not anything in the body.
        auth.deviceId,
      )
      .run();
    const changes = result.meta?.changes ?? 0;
    return jsonResponse({ ok: true, duplicate: changes === 0 }, 202);
  } catch {
    return jsonResponse({ error: "Could not record usage" }, 500);
  }
}

interface BreakdownRow extends WindowRow {
  device_id: string;
}

/**
 * The owner's cross-device view: one entry per device that either is configured
 * now or has ever spent.
 *
 * A revoked device keeps its history and stays in the list with
 * `configured: false`, because deleting a secret must not silently rewrite what
 * has already been spent.
 */
function buildBreakdown(
  config: DeviceConfig,
  perWindow: Map<string, BreakdownRow>[],
  capDayByDevice: Map<string, BreakdownRow>,
) {
  const ids = new Set<string>(config.devices.keys());
  for (const window of perWindow) {
    for (const id of window.keys()) ids.add(id);
  }
  for (const id of capDayByDevice.keys()) ids.add(id);

  return [...ids]
    .map((id) => {
      const capMicros = config.caps.get(id);
      const spentMicros = Math.round(
        (capDayByDevice.get(id)?.nanos ?? 0) / 1000,
      );
      return {
        device_id: id,
        // False means "has history but can no longer authenticate".
        configured: config.devices.has(id),
        windows: {
          today: windowSummary(perWindow[0].get(id) ?? null),
          last_7d: windowSummary(perWindow[1].get(id) ?? null),
          last_30d: windowSummary(perWindow[2].get(id) ?? null),
        },
        // Spend in the *cap's* day, which is why it is reported separately from
        // the `today` window: the two follow different timezones by design.
        cap_day_usd_micros: spentMicros,
        cap:
          capMicros === undefined
            ? null
            : capSummary({
                capMicros,
                spentMicros,
                tzOffsetMinutes: config.capTzOffsetMinutes,
              }),
      };
    })
    .sort(
      (left, right) =>
        right.windows.last_30d.usd_micros - left.windows.last_30d.usd_micros ||
        left.device_id.localeCompare(right.device_id),
    );
}

function indexByDevice(rows: BreakdownRow[]): Map<string, BreakdownRow> {
  return new Map(rows.map((row) => [row.device_id, row]));
}

async function handleUsageGet(
  request: Request,
  env: Env,
  url: URL,
): Promise<Response> {
  const parsedConfig = parseDeviceConfig(env);
  if (!parsedConfig.ok) return jsonResponse(MISCONFIGURED, 500);
  const config = parsedConfig.config;

  const auth = await authoriseDevice(request, config);
  if (!auth.ok) return jsonResponse(UNAUTHORIZED, 401);

  let model: string;
  try {
    model = resolveModel(env.TRANSCRIPTION_MODEL);
  } catch {
    return jsonResponse(MISCONFIGURED, 500);
  }
  const db = env.DB;
  if (!db) return jsonResponse(MISCONFIGURED, 500);

  const price = priceFor(model);
  const tzOffsetMinutes = resolveTzOffsetMinutes(
    url.searchParams.get("tz_offset_minutes"),
  );
  const now = Date.now();
  const starts = windowStartsMs(now, tzOffsetMinutes);
  const isOwner = config.ownerDeviceId === auth.deviceId;

  // Every device sees its own spend and nothing else. Not a secrecy measure so
  // much as an honesty one: a shared total on a borrowed phone reads as that
  // phone's total.
  let rows: WindowRow[];
  let breakdown: ReturnType<typeof buildBreakdown> | undefined;
  try {
    const statement = db.prepare(WINDOW_SQL);
    const results = await db.batch<WindowRow>([
      statement.bind(auth.deviceId, starts.today, now),
      statement.bind(auth.deviceId, starts.last7d, now),
      statement.bind(auth.deviceId, starts.last30d, now),
    ]);
    rows = results.map((result) => result.results[0] ?? null) as WindowRow[];

    if (isOwner) {
      const grouped = db.prepare(BREAKDOWN_SQL);
      const groupedResults = await db.batch<BreakdownRow>([
        grouped.bind(starts.today, now),
        grouped.bind(starts.last7d, now),
        grouped.bind(starts.last30d, now),
        grouped.bind(localDayStartMs(now, config.capTzOffsetMinutes), now),
      ]);
      const indexed = groupedResults.map((result) =>
        indexByDevice(result.results ?? []),
      );
      breakdown = buildBreakdown(config, indexed.slice(0, 3), indexed[3]);
    }
  } catch {
    return jsonResponse({ error: "Could not read usage" }, 500);
  }

  const ownCapMicros = config.caps.get(auth.deviceId);
  let ownCap: ReturnType<typeof capSummary> | null = null;
  if (ownCapMicros !== undefined) {
    const state = await readCapState(
      db,
      auth.deviceId,
      ownCapMicros,
      now,
      config.capTzOffsetMinutes,
    );
    // A cap we could not read is reported as absent rather than as a wrong
    // number; the cap is still enforced at /token, which fails closed.
    ownCap = state === null ? null : capSummary(state);
  }

  return jsonResponse(
    {
      model,
      price: {
        usd_per_minute: price.microUsdPerMinute / 1_000_000,
        usd_micros_per_minute: price.microUsdPerMinute,
        unit: price.billedBy,
        estimated: price.estimated,
      },
      windows: {
        today: windowSummary(rows[0]),
        last_7d: windowSummary(rows[1]),
        last_30d: windowSummary(rows[2]),
      },
      // Whose meter this is. Echoed so the phone can label the figures rather
      // than imply they are the account's.
      device_id: auth.deviceId,
      is_owner: isOwner,
      /** Null when this device is uncapped. */
      cap: ownCap,
      /** Present for the owner only. */
      ...(breakdown === undefined ? {} : { devices: breakdown }),
      tz_offset_minutes: tzOffsetMinutes,
      as_of: new Date(now).toISOString(),
      // Reserved for a future admin-key reconciliation path; the phone should
      // display whatever it gets here rather than assume this value.
      source: "device_reported",
    },
    200,
  );
}

async function handleToken(request: Request, env: Env): Promise<Response> {
  const parsedConfig = parseDeviceConfig(env);
  if (!parsedConfig.ok) return jsonResponse(MISCONFIGURED, 500);
  const config = parsedConfig.config;

  const auth = await authoriseDevice(request, config);
  if (!env.OPENAI_API_KEY || !auth.ok) {
    return jsonResponse(UNAUTHORIZED, 401);
  }

  let model: string;
  try {
    model = resolveModel(env.TRANSCRIPTION_MODEL);
  } catch {
    // Misconfiguration, not a bad request — do not leak the offending value.
    return jsonResponse(MISCONFIGURED, 500);
  }

  // The cap is enforced here because this is the only point where spending can
  // be *prevented* rather than recorded: no ephemeral token, no session, no
  // charge. An uncapped device skips the lookup entirely and pays none of its
  // latency — which is why the owner's own phone is unaffected.
  const capMicros = config.caps.get(auth.deviceId);
  if (capMicros !== undefined) {
    const db = env.DB;
    // A cap with no ledger to read is not enforceable, and quietly minting the
    // token would defeat the point of configuring one.
    if (!db) return jsonResponse(MISCONFIGURED, 500);

    const state = await readCapState(
      db,
      auth.deviceId,
      capMicros,
      Date.now(),
      config.capTzOffsetMinutes,
    );
    if (state === null) {
      return jsonResponse({ error: "Could not verify the spend cap" }, 500);
    }
    if (state.spentMicros >= state.capMicros) {
      // 402 rather than 403: nothing is wrong with the request or the device,
      // the budget is simply used up. The figures are included so the phone can
      // say what happened instead of showing a bare failure.
      return jsonResponse(
        {
          error: "Daily spend cap reached",
          cap: capSummary(state),
        },
        402,
      );
    }
  }

  // The device may only supply hints. The session shape and the model are
  // built here so a compromised device cannot pick a costlier model.
  const hints = parseHints(await request.json().catch(() => null));
  const body = createSessionRequest(model, hints);

  let openAIResponse: Response;
  try {
    openAIResponse = await fetch(OPENAI_CLIENT_SECRET_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.OPENAI_API_KEY}`,
        "Content-Type": "application/json",
        "OpenAI-Safety-Identifier": "livetype-personal-install",
      },
      body: JSON.stringify(body),
    });
  } catch {
    return jsonResponse({ error: "Could not reach OpenAI" }, 502);
  }

  const responseBody = await openAIResponse.text();
  if (!openAIResponse.ok) {
    // The response is useful while setting up the project, but never includes
    // the API key. Cloudflare logs should still avoid recording this body.
    return new Response(responseBody, {
      status: openAIResponse.status,
      headers: jsonHeaders,
    });
  }

  return new Response(responseBody, {
    status: 200,
    headers: jsonHeaders,
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    if (path === "/token" && request.method === "POST") {
      return handleToken(request, env);
    }
    if (path === "/usage" && request.method === "POST") {
      return handleUsagePost(request, env);
    }
    if (path === "/usage" && request.method === "GET") {
      return handleUsageGet(request, env, url);
    }
    return jsonResponse({ error: "Not found" }, 404);
  },
} satisfies ExportedHandler<Env>;

