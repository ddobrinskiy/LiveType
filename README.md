# LiveType

LiveType is an Android voice-input keyboard (IME). You install it as a
keyboard, press the microphone, speak, and the transcript lands in whatever
text field you were in — as composing text while you talk, committed when you
stop.

Transcription runs on the OpenAI Realtime API. Your OpenAI key stays on a
Cloudflare Worker you deploy yourself; the phone only ever holds a short-lived
token.

- Streaming partial results straight into the target field.
- Language, context prompt and keyword hints, configurable in the app.
- Refuses to record in password fields; keeps no transcript history.
- English and Russian UI, picked from the device locale.
- No account, no analytics, no backend of your own beyond the token Worker.

> LiveType is a personal-scale project: it is designed for you to deploy your
> own Worker and sideload your own build. See [Security model](#security-model)
> before handing the APK to anyone else.

## How it works

```
┌───────────────┐  1. POST /token          ┌──────────────────┐  2. POST /v1/realtime/  ┌────────┐
│  LiveType     │  X-Device-Secret         │  Cloudflare      │     client_secrets      │ OpenAI │
│  (Android)    │ ───────────────────────► │  Worker          │ ──────────────────────► │  API   │
│               │ ◄─────────────────────── │  (your account)  │ ◄────────────────────── │        │
└───────────────┘  ephemeral client secret └──────────────────┘   ephemeral secret      └────────┘
        │                                    holds OPENAI_API_KEY
        │
        │  3. wss://api.openai.com/v1/realtime
        │     Authorization: Bearer <ephemeral secret>
        │     PCM 24 kHz audio up, transcript deltas down
        └──────────────────────────────────────────────────────────────────────────────► OpenAI
```

Two consequences worth stating plainly:

- **The real API key never leaves the Worker.** The phone authenticates to the
  Worker with a shared `DEVICE_SECRET` and receives a client secret that
  expires 60 seconds after it is minted.
- **Audio never transits the Worker.** The phone opens the Realtime WebSocket
  to OpenAI directly, so the Worker sees no audio and no transcripts — only
  token requests.

The Worker also decides *which* model is used. The phone may send hints
(`languages`, `prompt`, `keywords`) and nothing else; a `model` field in the
request body is ignored, so a compromised device cannot select a costlier
model. Hints are trimmed, de-duplicated and clamped (8 languages, 100 keywords,
2000-character prompt).

### Repository layout

| Path | What it is |
|---|---|
| `android/` | The Kotlin IME. No Compose, no AndroidX — plain views, one dependency (OkHttp). |
| `worker/` | The Cloudflare Worker that mints ephemeral tokens, plus its Vitest suite. |
| `AGENTS.md` | Architecture notes, the local dev loop, and hard-won API details. |

## Prerequisites

- Android 9 (API 28) or newer on the phone.
- A Cloudflare account (the free plan is enough).
- An OpenAI API key on a project with billing enabled. A ChatGPT Plus
  subscription does **not** include API access.
- Node.js 20+ for the Worker.
- To build the app: JDK 17 and the Android SDK (platform 35, build-tools
  35.0.0). Android Studio provides both.

## 1. Deploy the token Worker

Generate a device secret first — this is the credential the phone will present
to your Worker:

```bash
openssl rand -hex 32
```

Then deploy:

```bash
cd worker
npm ci
npx wrangler login
npx wrangler secret put OPENAI_API_KEY   # paste your OpenAI key
npx wrangler secret put DEVICE_SECRET    # paste the value from openssl
npm run deploy
```

Wrangler prints the Worker URL. The app needs it with `/token` appended:

```text
https://livetype-token.your-account.workers.dev/token
```

`POST /token` is the only route; everything else returns 404. A request without
a matching `X-Device-Secret` header gets 401 (the comparison is
constant-time over SHA-256 digests).

### Choosing a transcription model

The Worker defaults to `gpt-live-transcribe`. To change it, set the optional
`TRANSCRIPTION_MODEL` variable — in `wrangler.jsonc` under `vars`, or in the
Cloudflare dashboard. Only the models below are allowed; anything else makes
`/token` return `500 {"error":"Worker is misconfigured"}` rather than reaching
OpenAI.

OpenAI rejects an entire request when it carries a hint the model does not
support, so the Worker drops unsupported hints instead of forwarding them:

| Model | `languages` | `prompt` | `keywords` |
|---|---|---|---|
| `gpt-live-transcribe` (default) | yes | yes | yes |
| `gpt-transcribe` | yes | yes | yes |
| `gpt-4o-transcribe` | no | yes | no |
| `gpt-4o-mini-transcribe` | no | yes | no |
| `gpt-realtime-whisper` | no | no | no |
| `whisper-1` | no | yes | no |

Verified against the live API on 2026-08-01.

### Continuous deployment (optional)

In the Cloudflare dashboard: **Workers & Pages → your Worker → Settings →
Builds → Connect**, pointed at your fork. Root directory `worker`, build
command `npm ci`, deploy command `npx wrangler deploy`. Secrets stay in
Cloudflare; never put `OPENAI_API_KEY` in the repository.

## 2. Install the app

Prebuilt APKs are published on the
[GitHub Releases](../../releases) page — download the latest one and install it
on the phone. Nothing is tracked in git.

To build it yourself:

```bash
cd android
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew` is a small bootstrap script that downloads Gradle 8.9 on first use;
there is no committed wrapper jar. It needs the Android SDK — either set
`ANDROID_HOME`, or create `android/local.properties` with
`sdk.dir=/path/to/Android/sdk`. Opening `android/` in Android Studio and
pressing Run works too.

Release builds (`./gradlew assembleRelease`) are unsigned; sign them with your
own key before distributing.

## 3. Configure LiveType

1. Open the LiveType app and grant microphone permission.
2. **Worker token endpoint** — the `/token` URL from step 1. Release builds
   accept `https://` only.
3. **Device secret** — the same `DEVICE_SECRET` you put in Cloudflare.
4. **Expected languages** — comma-separated BCP-47-ish codes, e.g. `ru,en`.
5. **Context** — a free-text prompt describing what you dictate.
6. **Terms** — domain words the model tends to mangle, one per line.
7. Save, then **Enable LiveType** (Android input-method settings) and
   **Choose keyboard**.

Settings are stored in app-private `SharedPreferences` and excluded from cloud
backup and device transfer.

## Using the keyboard

- Tap the microphone to start. Two indicators show the token Worker and the
  OpenAI Realtime connection; tap either for its status.
- Speak. Partial text appears in the field as composing text and is revised as
  the model corrects itself.
- Tap the microphone again to finish. The final transcript replaces the
  composing text.
- The ⏎ button inserts a line break — including mid-dictation, which commits
  what has been recognised so far and keeps recording.
- **Cancel** drops the in-flight phrase. **Settings** opens the app.
- Dictation is blocked in password fields.

Switching back to your previous keyboard automatically after a phrase is
implemented but disabled — see `FeatureFlags.RETURN_TO_PREVIOUS_KEYBOARD`. In
practice it fought the user, who usually wants to keep dictating.

## Local development

The full loop — running the Worker on your machine and pointing the phone at it
over USB — is documented in [`AGENTS.md`](AGENTS.md). The short version:

```bash
cd worker && npx wrangler dev        # needs worker/.dev.vars, see .dev.vars.example
adb reverse tcp:8787 tcp:8787
# token endpoint on the phone: http://127.0.0.1:8787/token
```

Cleartext HTTP is a **debug-build-only** capability, and even there it is
limited to loopback addresses by
`android/app/src/debug/res/xml/network_security_config.xml`. The release build
ships an HTTPS-only network security config and
`isAllowedTokenEndpoint()` refuses to save an `http://` endpoint, so the device
secret cannot travel in plaintext from a release APK.

Worker tests:

```bash
cd worker && npm ci && npx vitest run
```

CI runs those tests and an Android debug build on every push and pull request.

## Security model

`DEVICE_SECRET` is a shared secret baked into your phone's settings. It keeps
strangers off your token endpoint, but anyone who can read the device's app
data — or an APK you configured and handed over — can extract it. That is an
acceptable trade for a personal sideloaded keyboard when combined with:

- a dedicated OpenAI project with a low monthly budget and alerts;
- the 60-second lifetime on ephemeral client secrets;
- Cloudflare rate limiting on the Worker route;
- rotating `DEVICE_SECRET` (redeploy + re-enter in the app) if a phone is lost.

Do not ship this to Google Play as-is. A public distribution needs real user
authentication or device attestation in place of the shared secret.

## Privacy

- Audio goes from the phone to OpenAI and nowhere else. The Worker never sees
  it.
- No transcript history is stored, on the device or anywhere else. Text is
  written into the field you dictated into and then forgotten.
- No analytics, no crash reporting, no third-party SDKs. The app's only
  dependency is OkHttp.
- The app requests exactly two permissions: `INTERNET` and `RECORD_AUDIO`.
- Dictation is refused in password fields.
- Settings, including the device secret, are excluded from Android cloud backup
  and device-to-device transfer.
- What OpenAI does with the audio is governed by your own OpenAI account and
  its data-retention settings. Requests are tagged with the safety identifier
  `livetype-personal-install`.

## Credits

The OpenAI glyph in `android/app/src/main/res/drawable/ic_openai.xml` is path
data from [simple-icons](https://github.com/simple-icons/simple-icons)
(CC0-1.0). It is used nominatively, to show whether the app is connected to the
OpenAI Realtime API. It is not an endorsement, and this project is not
affiliated with or sponsored by OpenAI.

## Licence

MIT — see [LICENSE](LICENSE).
