export interface Env {
  OPENAI_API_KEY: string;
  DEVICE_SECRET: string;
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

/**
 * The single device check. Every route uses this one — /usage must never grow
 * its own copy that drifts from /token's.
 */
async function deviceIsAuthorised(
  request: Request,
  env: Env,
): Promise<boolean> {
  if (!env.DEVICE_SECRET) return false;
  return secureEquals(
    request.headers.get("X-Device-Secret"),
    env.DEVICE_SECRET,
  );
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
 WHERE created_at_ms >= ?1 AND created_at_ms <= ?2`;

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

async function handleUsagePost(request: Request, env: Env): Promise<Response> {
  if (!(await deviceIsAuthorised(request, env))) {
    return jsonResponse(UNAUTHORIZED, 401);
  }

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
            created_at_ms)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)`,
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
      )
      .run();
    const changes = result.meta?.changes ?? 0;
    return jsonResponse({ ok: true, duplicate: changes === 0 }, 202);
  } catch {
    return jsonResponse({ error: "Could not record usage" }, 500);
  }
}

async function handleUsageGet(
  request: Request,
  env: Env,
  url: URL,
): Promise<Response> {
  if (!(await deviceIsAuthorised(request, env))) {
    return jsonResponse(UNAUTHORIZED, 401);
  }

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

  let rows: WindowRow[];
  try {
    const statement = db.prepare(WINDOW_SQL);
    const results = await db.batch<WindowRow>([
      statement.bind(starts.today, now),
      statement.bind(starts.last7d, now),
      statement.bind(starts.last30d, now),
    ]);
    rows = results.map((result) => result.results[0] ?? null) as WindowRow[];
  } catch {
    return jsonResponse({ error: "Could not read usage" }, 500);
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
  if (!env.OPENAI_API_KEY || !(await deviceIsAuthorised(request, env))) {
    return jsonResponse(UNAUTHORIZED, 401);
  }

  let model: string;
  try {
    model = resolveModel(env.TRANSCRIPTION_MODEL);
  } catch {
    // Misconfiguration, not a bad request — do not leak the offending value.
    return jsonResponse(MISCONFIGURED, 500);
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

