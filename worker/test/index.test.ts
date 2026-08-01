import { Miniflare } from "miniflare";
import {
  afterAll,
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";
import worker, {
  audioTokensPerMinute,
  createSessionRequest,
  defaultModel,
  localDayStartMs,
  parseHints,
  parseUsageReport,
  priceFor,
  resolveModel,
  resolveTzOffsetMinutes,
  secureEquals,
  supportedModels,
  usdNanosFor,
  windowStartsMs,
  type Env,
  type SessionHints,
} from "../src/index";
import migrationSql from "../migrations/0001_usage_events.sql?raw";

const MODEL = defaultModel();

const noHints: SessionHints = { languages: [], prompt: "", keywords: [] };

function transcriptionOf(request: Record<string, unknown>) {
  const session = request.session as {
    audio: { input: { transcription: Record<string, unknown> } };
  };
  return session.audio.input.transcription;
}

describe("createSessionRequest", () => {
  it("pins the client secret to a short transcription session", () => {
    const request = createSessionRequest(MODEL, noHints) as {
      expires_after: { seconds: number };
      session: {
        type: string;
        audio: {
          input: {
            format: { type: string; rate: number };
            turn_detection: null;
          };
        };
      };
    };

    expect(request.expires_after.seconds).toBe(60);
    expect(request.session.type).toBe("transcription");
    expect(request.session.audio.input.format).toEqual({
      type: "audio/pcm",
      rate: 24000,
    });
    expect(request.session.audio.input.turn_detection).toBeNull();
  });

  it("uses the server-selected model", () => {
    expect(transcriptionOf(createSessionRequest(MODEL)).model).toBe(
      "gpt-live-transcribe",
    );
    expect(transcriptionOf(createSessionRequest("whisper-1")).model).toBe(
      "whisper-1",
    );
  });

  it("forwards hints the model supports", () => {
    const transcription = transcriptionOf(
      createSessionRequest("gpt-live-transcribe", {
        languages: ["en", "ru"],
        prompt: "technical jargon",
        keywords: ["LiveType"],
      }),
    );

    expect(transcription.languages).toEqual(["en", "ru"]);
    expect(transcription.prompt).toBe("technical jargon");
    expect(transcription.keywords).toEqual(["LiveType"]);
  });

  it("drops hints the model would reject", () => {
    // whisper-1 accepts prompt but 400s on languages and keywords.
    const whisper = transcriptionOf(
      createSessionRequest("whisper-1", {
        languages: ["en"],
        prompt: "keep this",
        keywords: ["drop this"],
      }),
    );
    expect(whisper).toEqual({ model: "whisper-1", prompt: "keep this" });

    // gpt-realtime-whisper rejects all three.
    const realtimeWhisper = transcriptionOf(
      createSessionRequest("gpt-realtime-whisper", {
        languages: ["en"],
        prompt: "nope",
        keywords: ["nope"],
      }),
    );
    expect(realtimeWhisper).toEqual({ model: "gpt-realtime-whisper" });
  });

  it("omits empty hints entirely", () => {
    expect(transcriptionOf(createSessionRequest(MODEL, noHints))).toEqual(
      { model: MODEL },
    );
  });
});

describe("resolveModel", () => {
  it("defaults when unset or blank", () => {
    expect(resolveModel(undefined)).toBe(MODEL);
    expect(resolveModel("   ")).toBe(MODEL);
  });

  it("accepts a supported override", () => {
    expect(resolveModel("gpt-transcribe")).toBe("gpt-transcribe");
    expect(resolveModel(" whisper-1 ")).toBe("whisper-1");
  });

  it("rejects anything not on the allowlist", () => {
    expect(() => resolveModel("gpt-realtime")).toThrow();
    expect(() => resolveModel("../../etc/passwd")).toThrow();
    // Must not resolve inherited Object properties.
    expect(() => resolveModel("constructor")).toThrow();
    expect(() => resolveModel("toString")).toThrow();
  });
});

describe("parseHints", () => {
  it("ignores a device-supplied model", () => {
    const hints = parseHints({ model: "gpt-realtime", languages: ["en"] });
    expect(hints).not.toHaveProperty("model");
    expect(hints.languages).toEqual(["en"]);
  });

  it("tolerates junk bodies", () => {
    for (const junk of [null, undefined, "string", 42, [], { languages: "en" }]) {
      expect(parseHints(junk)).toEqual(noHints);
    }
  });

  it("drops non-string and blank entries, and de-duplicates", () => {
    const hints = parseHints({
      languages: ["en", 7, "", "  ", "en", null, " ru "],
      keywords: ["LiveType", "LiveType", {}],
    });
    expect(hints.languages).toEqual(["en", "ru"]);
    expect(hints.keywords).toEqual(["LiveType"]);
  });

  it("clamps oversized input", () => {
    const hints = parseHints({
      languages: Array.from({ length: 50 }, (_, i) => `l${i}`),
      keywords: Array.from({ length: 500 }, (_, i) => `k${i}`),
      prompt: "x".repeat(9000),
    });
    expect(hints.languages).toHaveLength(8);
    expect(hints.keywords).toHaveLength(100);
    expect(hints.prompt).toHaveLength(2000);
  });

  it("drops individual entries that are too long", () => {
    const hints = parseHints({
      languages: ["en", "x".repeat(17)],
      keywords: ["ok", "y".repeat(65)],
    });
    expect(hints.languages).toEqual(["en"]);
    expect(hints.keywords).toEqual(["ok"]);
  });
});

describe("secureEquals", () => {
  it("accepts only the exact device secret", async () => {
    await expect(secureEquals("correct", "correct")).resolves.toBe(true);
    await expect(secureEquals("wrong", "correct")).resolves.toBe(false);
    await expect(secureEquals(null, "correct")).resolves.toBe(false);
  });
});

/* ============================================================ billing/usage */

describe("model prices", () => {
  it("prices every model the worker may select", () => {
    for (const model of supportedModels()) {
      expect(() => priceFor(model)).not.toThrow();
    }
    expect(supportedModels()).toHaveLength(6);
  });

  it("matches the published per-minute prices", () => {
    // Transcribed from developers.openai.com/api/docs/pricing on 2026-08-01.
    expect(priceFor("gpt-live-transcribe")).toEqual({
      microUsdPerMinute: 17000,
      billedBy: "duration",
      estimated: false,
    });
    expect(priceFor("gpt-transcribe").microUsdPerMinute).toBe(4500);
    expect(priceFor("gpt-realtime-whisper").microUsdPerMinute).toBe(17000);
    expect(priceFor("whisper-1").microUsdPerMinute).toBe(6000);
    expect(priceFor("gpt-4o-transcribe").microUsdPerMinute).toBe(6000);
    expect(priceFor("gpt-4o-mini-transcribe").microUsdPerMinute).toBe(3000);
  });

  it("flags exactly the two models whose token price OpenAI withholds", () => {
    const estimated = supportedModels().filter((m) => priceFor(m).estimated);
    expect(estimated.sort()).toEqual([
      "gpt-4o-mini-transcribe",
      "gpt-4o-transcribe",
    ]);
    // ...which are also exactly the token-billed ones.
    const tokenBilled = supportedModels().filter(
      (m) => priceFor(m).billedBy === "tokens",
    );
    expect(tokenBilled.sort()).toEqual([
      "gpt-4o-mini-transcribe",
      "gpt-4o-transcribe",
    ]);
  });

  it("refuses to price anything off the allowlist", () => {
    expect(() => priceFor("gpt-realtime")).toThrow();
    expect(() => priceFor("constructor")).toThrow();
    expect(() => priceFor("toString")).toThrow();
    expect(() => priceFor("")).toThrow();
  });
});

describe("usdNanosFor", () => {
  it("prices duration-billed audio per minute", () => {
    const live = priceFor("gpt-live-transcribe").microUsdPerMinute;
    // A whole minute is exactly the published price.
    expect(usdNanosFor(60, live)).toBe(17_000_000);
    // 3 s at $0.017/min = $0.00085.
    expect(usdNanosFor(3, live)).toBe(850_000);
    expect(usdNanosFor(0, live)).toBe(0);
    // $0.0045/min, an odd number of seconds: 11 s = $0.000825.
    expect(usdNanosFor(11, priceFor("gpt-transcribe").microUsdPerMinute)).toBe(
      825_000,
    );
  });

  it("keeps sub-micro-dollar precision", () => {
    // 1 s at $0.017/min is $0.000283333..., which would round to $0.000283
    // (a 0.1% loss) in micros and to $0.000 in cents.
    expect(usdNanosFor(1, 17000)).toBe(283_333);
  });
});

describe("parseUsageReport", () => {
  it("reads the duration payload OpenAI actually sends", () => {
    // Verbatim from a live gpt-live-transcribe session, 2026-08-01.
    const result = parseUsageReport({
      type: "conversation.item.input_audio_transcription.completed",
      event_id: "event_E81t2qaHZRlsSLZQpwJ0L",
      item_id: "item_E81t1mmrLaGrBlAjuBJp2",
      content_index: 0,
      transcript: "",
      usage: { type: "duration", seconds: 3 },
    });

    expect(result).toEqual({
      ok: true,
      report: {
        itemId: "item_E81t1mmrLaGrBlAjuBJp2",
        usageType: "duration",
        quantity: 3,
        billableSeconds: 3,
      },
    });
  });

  it("converts audio tokens at 600 per minute", () => {
    const tokens = (audioTokens: number) =>
      parseUsageReport({
        item_id: "item_x",
        usage: {
          type: "tokens",
          total_tokens: audioTokens + 5,
          input_tokens: audioTokens,
          input_token_details: { text_tokens: 0, audio_tokens: audioTokens },
          output_tokens: 5,
        },
      });

    expect(audioTokensPerMinute()).toBe(600);
    // Measured rates: 30 tok = 3 s, 100 tok = 10 s, 300 tok = 30 s.
    for (const [tok, seconds] of [
      [30, 3],
      [100, 10],
      [300, 30],
      [600, 60],
      [0, 0],
    ]) {
      const result = tokens(tok);
      expect(result.ok).toBe(true);
      if (!result.ok) return;
      expect(result.report.quantity).toBe(tok);
      expect(result.report.billableSeconds).toBeCloseTo(seconds, 9);
    }
  });

  it("ignores every field except item_id and usage", () => {
    const result = parseUsageReport({
      item_id: "item_x",
      usage: { type: "duration", seconds: 5, seconds_billed: 900 },
      model: "gpt-realtime-translate",
      price: 99,
      usd: 12.5,
      price_micro_usd_per_minute: 1,
      created_at_ms: 0,
    });

    expect(result).toEqual({
      ok: true,
      report: {
        itemId: "item_x",
        usageType: "duration",
        quantity: 5,
        billableSeconds: 5,
      },
    });
  });

  it("rejects a missing or unusable item_id", () => {
    const usage = { type: "duration", seconds: 1 };
    for (const itemId of [
      undefined,
      null,
      "",
      "   ",
      42,
      { toString: "item_x" },
      ["item_x"],
      "item with spaces",
      "item_'; DROP TABLE usage_events; --",
      "x".repeat(129),
    ]) {
      const result = parseUsageReport({ item_id: itemId, usage });
      expect(result, `item_id ${JSON.stringify(itemId)}`).toEqual({
        ok: false,
        error: "Invalid item_id",
      });
    }
  });

  it("rejects malformed bodies without throwing", () => {
    for (const junk of [null, undefined, "", "not json", 42, [], true]) {
      expect(parseUsageReport(junk)).toEqual({
        ok: false,
        error: "Malformed body",
      });
    }
  });

  it("rejects a missing or non-object usage", () => {
    for (const usage of [undefined, null, "duration", 3, []]) {
      expect(parseUsageReport({ item_id: "item_x", usage })).toEqual({
        ok: false,
        error: "Missing usage",
      });
    }
  });

  it("rejects an unknown usage.type", () => {
    for (const type of [undefined, null, "seconds", "DURATION", 1, {}]) {
      expect(parseUsageReport({ item_id: "item_x", usage: { type } })).toEqual({
        ok: false,
        error: "Unsupported usage.type",
      });
    }
  });

  it("rejects hostile duration values", () => {
    const bad = (seconds: unknown) =>
      parseUsageReport({ item_id: "item_x", usage: { type: "duration", seconds } });

    expect(bad(-1)).toEqual({ ok: false, error: "Invalid usage.seconds" });
    expect(bad(-0.0001)).toEqual({ ok: false, error: "Invalid usage.seconds" });
    expect(bad("3")).toEqual({ ok: false, error: "Invalid usage.seconds" });
    expect(bad(null)).toEqual({ ok: false, error: "Invalid usage.seconds" });
    expect(bad(undefined)).toEqual({ ok: false, error: "Invalid usage.seconds" });
    expect(bad(NaN)).toEqual({ ok: false, error: "Invalid usage.seconds" });
    expect(bad(Infinity)).toEqual({ ok: false, error: "Invalid usage.seconds" });
    expect(bad(-Infinity)).toEqual({ ok: false, error: "Invalid usage.seconds" });
    expect(bad({ valueOf: 3 })).toEqual({
      ok: false,
      error: "Invalid usage.seconds",
    });

    // Sane upper bound: one committed buffer is not four hours of audio.
    expect(bad(14401)).toEqual({
      ok: false,
      error: "usage.seconds out of range",
    });
    expect(bad(Number.MAX_SAFE_INTEGER)).toEqual({
      ok: false,
      error: "usage.seconds out of range",
    });
    expect(bad(1e308)).toEqual({
      ok: false,
      error: "usage.seconds out of range",
    });

    // ...but the bound itself, and a zero-length billed commit, are fine.
    expect(bad(14400).ok).toBe(true);
    expect(bad(0).ok).toBe(true);
  });

  it("rejects hostile token values", () => {
    const bad = (details: unknown) =>
      parseUsageReport({
        item_id: "item_x",
        usage: { type: "tokens", input_token_details: details },
      });

    for (const details of [undefined, null, "30", 30, []]) {
      expect(bad(details)).toEqual({
        ok: false,
        error: "Missing usage.input_token_details",
      });
    }
    for (const audioTokens of [-1, "30", null, NaN, Infinity, {}]) {
      expect(bad({ audio_tokens: audioTokens })).toEqual({
        ok: false,
        error: "Invalid usage.input_token_details.audio_tokens",
      });
    }
    expect(bad({ audio_tokens: 144001 })).toEqual({
      ok: false,
      error: "audio_tokens out of range",
    });
    expect(bad({ audio_tokens: 144000 }).ok).toBe(true);

    // input_tokens/total_tokens are never trusted as the billable quantity;
    // only input_token_details.audio_tokens is.
    const inflated = parseUsageReport({
      item_id: "item_x",
      usage: {
        type: "tokens",
        total_tokens: 999999,
        input_tokens: 999999,
        input_token_details: { text_tokens: 999999, audio_tokens: 30 },
        output_tokens: 999999,
      },
    });
    expect(inflated.ok).toBe(true);
    if (inflated.ok) expect(inflated.report.billableSeconds).toBe(3);
  });
});

describe("resolveTzOffsetMinutes", () => {
  it("accepts real UTC offsets, signed", () => {
    expect(resolveTzOffsetMinutes("0")).toBe(0);
    expect(resolveTzOffsetMinutes("180")).toBe(180);
    expect(resolveTzOffsetMinutes("+180")).toBe(180);
    expect(resolveTzOffsetMinutes("-300")).toBe(-300);
    expect(resolveTzOffsetMinutes(" -300 ")).toBe(-300);
    expect(resolveTzOffsetMinutes("840")).toBe(840); // UTC+14, Kiritimati
    expect(resolveTzOffsetMinutes("-840")).toBe(-840);
    expect(resolveTzOffsetMinutes("-270")).toBe(-270); // Newfoundland
  });

  it("falls back to UTC for anything unsound", () => {
    for (const raw of [
      null,
      "",
      "   ",
      "abc",
      "180.5",
      "1e3",
      "0x10",
      "NaN",
      "Infinity",
      "841",
      "-841",
      "99999",
      "-99999",
      "180; DROP TABLE usage_events",
      "180 minutes",
    ]) {
      expect(resolveTzOffsetMinutes(raw), `offset ${JSON.stringify(raw)}`).toBe(
        0,
      );
    }
  });
});

describe("local day bucketing", () => {
  const noon = Date.parse("2026-08-01T12:00:00.000Z");

  it("starts the day at local midnight, not UTC midnight", () => {
    expect(localDayStartMs(noon, 0)).toBe(
      Date.parse("2026-08-01T00:00:00.000Z"),
    );
    // UTC+3: 2026-08-01 00:00 local is 2026-07-31 21:00 UTC.
    expect(localDayStartMs(noon, 180)).toBe(
      Date.parse("2026-07-31T21:00:00.000Z"),
    );
    // UTC-5: 2026-08-01 00:00 local is 2026-08-01 05:00 UTC.
    expect(localDayStartMs(noon, -300)).toBe(
      Date.parse("2026-08-01T05:00:00.000Z"),
    );
  });

  it("counts whole local calendar days, today included", () => {
    const day = 86_400_000;
    const starts = windowStartsMs(noon, 180);
    expect(starts.today).toBe(Date.parse("2026-07-31T21:00:00.000Z"));
    expect(starts.last7d).toBe(starts.today - 6 * day);
    expect(starts.last30d).toBe(starts.today - 29 * day);
    expect(starts.last7d).toBe(Date.parse("2026-07-25T21:00:00.000Z"));
    expect(starts.last30d).toBe(Date.parse("2026-07-02T21:00:00.000Z"));
  });

  it("handles a local date that is behind the UTC date", () => {
    // 01:00 UTC on Aug 1 is still Jul 31 at UTC-5.
    const earlyUtc = Date.parse("2026-08-01T01:00:00.000Z");
    expect(localDayStartMs(earlyUtc, -300)).toBe(
      Date.parse("2026-07-31T05:00:00.000Z"),
    );
    // ...and already Aug 1 at UTC+3.
    expect(localDayStartMs(earlyUtc, 180)).toBe(
      Date.parse("2026-07-31T21:00:00.000Z"),
    );
  });
});

/* -------------------------------------------------- HTTP against a real D1 */

interface UsageWindow {
  seconds: number;
  usd: number;
  usd_micros: number;
  sessions: number;
}

interface UsageResponse {
  model: string;
  price: {
    usd_per_minute: number;
    usd_micros_per_minute: number;
    unit: string;
    estimated: boolean;
  };
  windows: { today: UsageWindow; last_7d: UsageWindow; last_30d: UsageWindow };
  tz_offset_minutes: number;
  as_of: string;
  source: string;
}

const SECRET = "0123456789abcdef0123456789abcdef";
const NOON = Date.parse("2026-08-01T12:00:00.000Z");
const DAY = 86_400_000;

let mf: Miniflare;
let db: D1Database;

/** Statements from the real migration file, so the tests cannot drift from it. */
function migrationStatements(): string[] {
  return migrationSql
    .replace(/--[^\n]*/g, "")
    .split(";")
    .map((statement) => statement.trim())
    .filter((statement) => statement.length > 0);
}

function testEnv(overrides: Partial<Env> = {}): Env {
  return {
    OPENAI_API_KEY: "sk-test-never-called",
    DEVICE_SECRET: SECRET,
    DB: db,
    ...overrides,
  };
}

function usageRequest(
  method: "GET" | "POST" | "DELETE",
  options: { body?: unknown; secret?: string | null; query?: string } = {},
): Request {
  const { body, secret = SECRET, query = "" } = options;
  const headers = new Headers();
  if (secret !== null) headers.set("X-Device-Secret", secret);
  const init: RequestInit = { method, headers };
  if (body !== undefined) {
    headers.set("Content-Type", "application/json");
    init.body = typeof body === "string" ? body : JSON.stringify(body);
  }
  return new Request(`https://worker.test/usage${query}`, init);
}

function duration(seconds: number) {
  return { type: "duration", seconds };
}

function tokens(audioTokens: number) {
  return {
    type: "tokens",
    total_tokens: audioTokens + 5,
    input_tokens: audioTokens,
    input_token_details: { text_tokens: 0, audio_tokens: audioTokens },
    output_tokens: 5,
  };
}

async function postUsage(
  body: unknown,
  options: { secret?: string | null; env?: Env } = {},
): Promise<Response> {
  return worker.fetch(
    usageRequest("POST", { body, secret: options.secret }),
    options.env ?? testEnv(),
  );
}

/** Records one session as if the phone had posted it at `atMs`. */
async function recordAt(
  atMs: number,
  itemId: string,
  usage: unknown,
  env?: Env,
): Promise<void> {
  vi.setSystemTime(atMs);
  const response = await postUsage({ item_id: itemId, usage }, { env });
  expect(response.status, `recording ${itemId}`).toBe(202);
}

async function readUsage(
  options: { query?: string; secret?: string | null; env?: Env; atMs?: number } = {},
): Promise<{ status: number; body: UsageResponse }> {
  if (options.atMs !== undefined) vi.setSystemTime(options.atMs);
  const response = await worker.fetch(
    usageRequest("GET", { query: options.query, secret: options.secret }),
    options.env ?? testEnv(),
  );
  return {
    status: response.status,
    body: (await response.json()) as UsageResponse,
  };
}

beforeAll(async () => {
  mf = new Miniflare({
    modules: true,
    // The worker under test is invoked directly; this script exists only so
    // miniflare has something to attach the D1 binding to.
    script: "export default { fetch() { return new Response(null, {status: 404}); } };",
    d1Databases: { DB: ":memory:" },
  });
  db = (await mf.getD1Database("DB")) as unknown as D1Database;
  for (const statement of migrationStatements()) {
    await db.prepare(statement).run();
  }
});

afterAll(async () => {
  await mf.dispose();
});

beforeEach(async () => {
  await db.prepare("DELETE FROM usage_events").run();
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(NOON);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("routing", () => {
  it("serves only the three documented routes", async () => {
    const cases: Array<[string, string, number]> = [
      ["GET", "https://worker.test/usage", 200],
      ["POST", "https://worker.test/usage", 400], // authorised but no body
      ["DELETE", "https://worker.test/usage", 404],
      ["PUT", "https://worker.test/usage", 404],
      ["GET", "https://worker.test/token", 404],
      ["GET", "https://worker.test/", 404],
      ["GET", "https://worker.test/usage/today", 404],
      ["POST", "https://worker.test/usages", 404],
    ];

    for (const [method, url, expected] of cases) {
      const response = await worker.fetch(
        new Request(url, {
          method,
          headers: { "X-Device-Secret": SECRET },
        }),
        testEnv(),
      );
      expect(response.status, `${method} ${url}`).toBe(expected);
    }
  });
});

describe("usage auth", () => {
  it("rejects a missing device secret on both endpoints", async () => {
    for (const method of ["GET", "POST"] as const) {
      const response = await worker.fetch(
        usageRequest(method, {
          secret: null,
          body: method === "POST" ? { item_id: "item_a", usage: duration(3) } : undefined,
        }),
        testEnv(),
      );
      expect(response.status, method).toBe(401);
      expect(await response.json()).toEqual({ error: "Unauthorized" });
    }
  });

  it("rejects a wrong device secret on both endpoints", async () => {
    for (const secret of ["", "wrong", SECRET.toUpperCase(), `${SECRET}x`, SECRET.slice(0, -1)]) {
      const post = await postUsage(
        { item_id: "item_a", usage: duration(3) },
        { secret },
      );
      expect(post.status, `POST with ${JSON.stringify(secret)}`).toBe(401);

      const get = await worker.fetch(usageRequest("GET", { secret }), testEnv());
      expect(get.status, `GET with ${JSON.stringify(secret)}`).toBe(401);
    }

    const rows = await db.prepare("SELECT COUNT(*) AS n FROM usage_events").first<{ n: number }>();
    expect(rows?.n).toBe(0);
  });

  it("rejects everything when the worker has no device secret configured", async () => {
    const env = testEnv({ DEVICE_SECRET: "" });
    const post = await postUsage({ item_id: "item_a", usage: duration(3) }, { env });
    expect(post.status).toBe(401);
    const get = await readUsage({ env });
    expect(get.status).toBe(401);
  });
});

describe("POST /usage", () => {
  it("records a duration session and prices it server-side", async () => {
    const response = await postUsage({
      item_id: "item_E81t1mmrLaGrBlAjuBJp2",
      usage: duration(3),
    });
    expect(response.status).toBe(202);
    expect(await response.json()).toEqual({ ok: true, duplicate: false });

    const row = await db
      .prepare("SELECT * FROM usage_events")
      .first<Record<string, unknown>>();
    expect(row).toMatchObject({
      item_id: "item_E81t1mmrLaGrBlAjuBJp2",
      model: "gpt-live-transcribe",
      usage_type: "duration",
      quantity: 3,
      billable_seconds: 3,
      price_micro_usd_per_minute: 17000,
      price_estimated: 0,
      usd_nanos: 850_000,
      created_at_ms: NOON,
    });
  });

  it("does not double-count a replayed item_id", async () => {
    const body = { item_id: "item_retry", usage: duration(30) };

    const first = await postUsage(body);
    expect(await first.json()).toEqual({ ok: true, duplicate: false });

    for (let attempt = 0; attempt < 3; attempt += 1) {
      const retry = await postUsage(body);
      expect(retry.status).toBe(202);
      expect(await retry.json()).toEqual({ ok: true, duplicate: true });
    }

    // Even a retry that claims a different quantity must not overwrite.
    const tampered = await postUsage({
      item_id: "item_retry",
      usage: duration(14400),
    });
    expect(tampered.status).toBe(202);

    const totals = await db
      .prepare(
        "SELECT COUNT(*) AS sessions, SUM(billable_seconds) AS seconds FROM usage_events",
      )
      .first<{ sessions: number; seconds: number }>();
    expect(totals).toEqual({ sessions: 1, seconds: 30 });

    const usage = await readUsage();
    expect(usage.body.windows.today).toEqual({
      seconds: 30,
      usd: 0.0085,
      usd_micros: 8500,
      sessions: 1,
    });
  });

  it("converts audio tokens at 600/min and marks the price estimated", async () => {
    const env = testEnv({ TRANSCRIPTION_MODEL: "gpt-4o-mini-transcribe" });
    // 300 audio tokens = 30 s = 0.5 min at $0.003/min = $0.0015.
    await recordAt(NOON, "item_tokens", tokens(300), env);

    const row = await db
      .prepare("SELECT * FROM usage_events")
      .first<Record<string, unknown>>();
    expect(row).toMatchObject({
      model: "gpt-4o-mini-transcribe",
      usage_type: "tokens",
      quantity: 300,
      billable_seconds: 30,
      price_micro_usd_per_minute: 3000,
      price_estimated: 1,
      usd_nanos: 1_500_000,
    });

    const usage = await readUsage({ env });
    expect(usage.body.price).toEqual({
      usd_per_minute: 0.003,
      usd_micros_per_minute: 3000,
      unit: "tokens",
      estimated: true,
    });
    expect(usage.body.windows.today).toEqual({
      seconds: 30,
      usd: 0.0015,
      usd_micros: 1500,
      sessions: 1,
    });
  });

  it("prices gpt-4o-transcribe from its own token rate", async () => {
    const env = testEnv({ TRANSCRIPTION_MODEL: "gpt-4o-transcribe" });
    // The measured live sample: 3 s of audio -> 30 audio tokens.
    await recordAt(NOON, "item_4o", tokens(30), env);
    const usage = await readUsage({ env });
    expect(usage.body.windows.today.seconds).toBe(3);
    // 3 s at $0.006/min = $0.0003.
    expect(usage.body.windows.today.usd_micros).toBe(300);
    expect(usage.body.price.estimated).toBe(true);
  });

  it("cannot be told which model or price to use", async () => {
    const response = await postUsage({
      item_id: "item_liar",
      usage: duration(60),
      model: "gpt-realtime-translate",
      price: { usd_per_minute: 99 },
      usd: 1234.5,
      price_micro_usd_per_minute: 34000,
      usd_nanos: 999_999_999,
      billable_seconds: 99999,
      created_at_ms: 0,
      price_estimated: 1,
    });
    expect(response.status).toBe(202);

    const row = await db
      .prepare("SELECT * FROM usage_events")
      .first<Record<string, unknown>>();
    expect(row).toMatchObject({
      model: "gpt-live-transcribe",
      billable_seconds: 60,
      price_micro_usd_per_minute: 17000,
      price_estimated: 0,
      usd_nanos: 17_000_000,
      created_at_ms: NOON,
    });

    const usage = await readUsage();
    expect(usage.body.model).toBe("gpt-live-transcribe");
    expect(usage.body.price.usd_per_minute).toBe(0.017);
    expect(usage.body.windows.today.usd).toBe(0.017);
  });

  it("400s malformed input instead of 500ing, and stores nothing", async () => {
    const cases: Array<[unknown, string]> = [
      ["", "Malformed body"],
      ["not json at all", "Malformed body"],
      ["{", "Malformed body"],
      ["[1,2,3]", "Malformed body"],
      ["null", "Malformed body"],
      ["42", "Malformed body"],
      [{}, "Invalid item_id"],
      [{ usage: duration(3) }, "Invalid item_id"],
      [{ item_id: "", usage: duration(3) }, "Invalid item_id"],
      [{ item_id: 7, usage: duration(3) }, "Invalid item_id"],
      [{ item_id: "item_a" }, "Missing usage"],
      [{ item_id: "item_a", usage: "duration" }, "Missing usage"],
      [{ item_id: "item_a", usage: {} }, "Unsupported usage.type"],
      [
        { item_id: "item_a", usage: { type: "minutes", minutes: 1 } },
        "Unsupported usage.type",
      ],
      [
        { item_id: "item_a", usage: { type: "duration", seconds: "3" } },
        "Invalid usage.seconds",
      ],
      [
        { item_id: "item_a", usage: { type: "duration", seconds: -5 } },
        "Invalid usage.seconds",
      ],
      [
        { item_id: "item_a", usage: { type: "duration", seconds: 1e12 } },
        "usage.seconds out of range",
      ],
      [
        { item_id: "item_a", usage: { type: "tokens" } },
        "Missing usage.input_token_details",
      ],
      [
        {
          item_id: "item_a",
          usage: { type: "tokens", input_token_details: { audio_tokens: -3 } },
        },
        "Invalid usage.input_token_details.audio_tokens",
      ],
    ];

    for (const [body, error] of cases) {
      const response = await postUsage(body);
      expect(response.status, JSON.stringify(body)).toBe(400);
      expect(await response.json()).toEqual({ error });
    }

    const rows = await db
      .prepare("SELECT COUNT(*) AS n FROM usage_events")
      .first<{ n: number }>();
    expect(rows?.n).toBe(0);
  });

  it("500s cleanly when the worker is misconfigured", async () => {
    const badModel = testEnv({ TRANSCRIPTION_MODEL: "gpt-realtime" });
    const post = await postUsage(
      { item_id: "item_a", usage: duration(3) },
      { env: badModel },
    );
    expect(post.status).toBe(500);
    expect(await post.json()).toEqual({ error: "Worker is misconfigured" });
    expect((await readUsage({ env: badModel })).status).toBe(500);

    const noDb = testEnv({ DB: undefined });
    const withoutDb = await postUsage(
      { item_id: "item_a", usage: duration(3) },
      { env: noDb },
    );
    expect(withoutDb.status).toBe(500);
    expect((await readUsage({ env: noDb })).status).toBe(500);
  });
});

describe("GET /usage", () => {
  it("answers before a single session has been recorded", async () => {
    const { status, body } = await readUsage({ query: "?tz_offset_minutes=180" });
    expect(status).toBe(200);
    expect(body).toEqual({
      model: "gpt-live-transcribe",
      price: {
        usd_per_minute: 0.017,
        usd_micros_per_minute: 17000,
        unit: "duration",
        estimated: false,
      },
      windows: {
        today: { seconds: 0, usd: 0, usd_micros: 0, sessions: 0 },
        last_7d: { seconds: 0, usd: 0, usd_micros: 0, sessions: 0 },
        last_30d: { seconds: 0, usd: 0, usd_micros: 0, sessions: 0 },
      },
      tz_offset_minutes: 180,
      as_of: "2026-08-01T12:00:00.000Z",
      source: "device_reported",
    });
  });

  it("nests the windows so each contains the shorter ones", async () => {
    const todayStart = localDayStartMs(NOON, 0);
    await recordAt(NOON, "item_today", duration(60));
    await recordAt(todayStart - DAY, "item_yesterday", duration(60));
    await recordAt(todayStart - 10 * DAY, "item_older", duration(60));
    await recordAt(todayStart - 100 * DAY, "item_ancient", duration(60));

    const { body } = await readUsage({ atMs: NOON });
    expect(body.windows.today.sessions).toBe(1);
    expect(body.windows.last_7d.sessions).toBe(2);
    expect(body.windows.last_30d.sessions).toBe(3);
    expect(body.windows.last_30d.usd).toBe(0.051);
  });

  it("buckets by the device's local day, not UTC (positive offset)", async () => {
    // UTC+3. Local midnight starting 2026-08-01 is 2026-07-31T21:00:00Z.
    const localMidnight = Date.parse("2026-07-31T21:00:00.000Z");
    await recordAt(localMidnight - 1, "item_before", duration(60));
    await recordAt(localMidnight, "item_after", duration(120));

    const local = await readUsage({
      query: "?tz_offset_minutes=180",
      atMs: NOON,
    });
    expect(local.body.tz_offset_minutes).toBe(180);
    expect(local.body.windows.today).toMatchObject({
      seconds: 120,
      sessions: 1,
    });
    expect(local.body.windows.last_7d).toMatchObject({
      seconds: 180,
      sessions: 2,
    });

    // Both events are on 2026-07-31 in UTC, so UTC bucketing sees neither.
    const utc = await readUsage({ atMs: NOON });
    expect(utc.body.tz_offset_minutes).toBe(0);
    expect(utc.body.windows.today).toMatchObject({ seconds: 0, sessions: 0 });
    expect(utc.body.windows.last_7d).toMatchObject({
      seconds: 180,
      sessions: 2,
    });
  });

  it("buckets by the device's local day, not UTC (negative offset)", async () => {
    // UTC-5. Local midnight starting 2026-08-01 is 2026-08-01T05:00:00Z.
    const localMidnight = Date.parse("2026-08-01T05:00:00.000Z");
    await recordAt(localMidnight - 1, "item_before", duration(60));
    await recordAt(localMidnight, "item_at", duration(30));
    await recordAt(localMidnight + 1, "item_after", duration(90));

    const local = await readUsage({
      query: "?tz_offset_minutes=-300",
      atMs: NOON,
    });
    expect(local.body.tz_offset_minutes).toBe(-300);
    expect(local.body.windows.today).toMatchObject({
      seconds: 120,
      sessions: 2,
    });

    // In UTC all three land on 2026-08-01, which is why the offset matters.
    const utc = await readUsage({ atMs: NOON });
    expect(utc.body.windows.today).toMatchObject({ seconds: 180, sessions: 3 });
  });

  it("puts the 7- and 30-day edges on local midnight too", async () => {
    const starts = windowStartsMs(NOON, 180);
    await recordAt(starts.last7d, "item_7d_in", duration(60));
    await recordAt(starts.last7d - 1, "item_7d_out", duration(60));
    await recordAt(starts.last30d, "item_30d_in", duration(60));
    await recordAt(starts.last30d - 1, "item_30d_out", duration(60));

    const { body } = await readUsage({
      query: "?tz_offset_minutes=180",
      atMs: NOON,
    });
    expect(body.windows.today.sessions).toBe(0);
    expect(body.windows.last_7d.sessions).toBe(1);
    expect(body.windows.last_30d.sessions).toBe(3);
  });

  it("falls back to UTC for an unusable tz_offset_minutes", async () => {
    const localMidnight = Date.parse("2026-08-01T05:00:00.000Z");
    await recordAt(localMidnight - 1, "item_before", duration(60));

    for (const query of [
      "",
      "?tz_offset_minutes=",
      "?tz_offset_minutes=abc",
      "?tz_offset_minutes=-300.5",
      "?tz_offset_minutes=99999",
      "?tz_offset_minutes=-300&tz_offset_minutes=abc", // first wins, is valid
    ]) {
      const { body } = await readUsage({ query, atMs: NOON });
      const expected = query.includes("=-300&") ? -300 : 0;
      expect(body.tz_offset_minutes, query).toBe(expected);
    }
  });

  it("ignores unknown query parameters", async () => {
    const { status, body } = await readUsage({
      query: "?tz_offset_minutes=180&model=gpt-realtime-translate&usd=99",
      atMs: NOON,
    });
    expect(status).toBe(200);
    expect(body.model).toBe("gpt-live-transcribe");
  });

  it("keeps the price that was in force when each session ran", async () => {
    // Recorded while the worker was on the cheap model...
    await recordAt(
      NOON,
      "item_cheap",
      duration(60),
      testEnv({ TRANSCRIPTION_MODEL: "gpt-transcribe" }),
    );
    // ...then the operator switches models.
    const after = await readUsage({
      env: testEnv({ TRANSCRIPTION_MODEL: "gpt-live-transcribe" }),
      atMs: NOON,
    });

    // The header shows the current model and price...
    expect(after.body.model).toBe("gpt-live-transcribe");
    expect(after.body.price.usd_per_minute).toBe(0.017);
    // ...but the historical minute is still billed at $0.0045, not re-priced.
    expect(after.body.windows.today.usd).toBe(0.0045);
  });

  it("reports the source so a reconciliation path can be added later", async () => {
    const { body } = await readUsage();
    expect(body.source).toBe("device_reported");
  });

  it("sums many sessions without float drift", async () => {
    for (let index = 0; index < 100; index += 1) {
      await recordAt(NOON, `item_${index}`, duration(1));
    }
    const { body } = await readUsage({ atMs: NOON });
    expect(body.windows.today.sessions).toBe(100);
    expect(body.windows.today.seconds).toBe(100);
    // 100 s at $0.017/min = $0.0283333...; nanos round to 28333300 -> 28333 µ$.
    expect(body.windows.today.usd_micros).toBe(28333);
    expect(body.windows.today.usd).toBe(0.028333);
  });
});
