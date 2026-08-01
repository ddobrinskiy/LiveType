export interface Env {
  OPENAI_API_KEY: string;
  DEVICE_SECRET: string;
  /** Optional override; must be a key of SUPPORTED_MODELS. */
  TRANSCRIPTION_MODEL?: string;
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

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method !== "POST" || url.pathname !== "/token") {
      return jsonResponse({ error: "Not found" }, 404);
    }

    if (
      !env.OPENAI_API_KEY ||
      !env.DEVICE_SECRET ||
      !(await secureEquals(
        request.headers.get("X-Device-Secret"),
        env.DEVICE_SECRET,
      ))
    ) {
      return jsonResponse({ error: "Unauthorized" }, 401);
    }

    let model: string;
    try {
      model = resolveModel(env.TRANSCRIPTION_MODEL);
    } catch {
      // Misconfiguration, not a bad request — do not leak the offending value.
      return jsonResponse({ error: "Worker is misconfigured" }, 500);
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
  },
} satisfies ExportedHandler<Env>;

