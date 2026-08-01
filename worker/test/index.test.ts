import { describe, expect, it } from "vitest";
import {
  createSessionRequest,
  defaultModel,
  parseHints,
  resolveModel,
  secureEquals,
  type SessionHints,
} from "../src/index";

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
