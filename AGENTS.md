# Local Testing Guide: LiveType Token Worker

## Architecture

```
┌─────────────┐  http://127.0.0.1:8787/token  ┌──────────────┐  POST /v1/realtime/  ┌─────────────┐
│  Phone via   │  ──────── adb reverse ──────►  │  wrangler dev │  client_secrets      │  OpenAI     │
│  USB (ADB)   │  ◄───────────────────────────  │  port 8787   │ ◄──────────────────  │  API        │
│              │     ephemeral client secret    │  (Mac)       │   ephemeral token    │             │
└──────────────┘                                └──────────────┘                      └─────────────┘
       │  wss://api.openai.com/v1/realtime   (no ?model= — see Model Notes)
       │  (Authorization: Bearer <client_secret>)
       └────────────────────────────────────────────────────────────────────────────► OpenAI
```

## Working Agreement

**When several separate app changes are requested at once, rebuild and
reinstall after each one — as a background command — instead of batching them
into a single build at the end.** The user is testing on a physical phone and
wants each change on the device as soon as it is ready.

```bash
# run in the background after finishing each individual change
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew assembleDebug \
  && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Verify the APK actually recompiled — a sub-second `BUILD SUCCESSFUL` usually
means `compileDebugKotlin` was `UP-TO-DATE` and nothing changed.

## Prerequisites

- Android phone connected via USB with USB debugging enabled
- `adb` installed (`brew install android-platform-tools`)
- Node.js + npm
- `wrangler` installed (`npm install -g wrangler` or via `npx`)

## Local Dev Setup

### 1. Set up secrets

```bash
# worker/.dev.vars must exist:
OPENAI_API_KEY=sk-project-...
DEVICE_SECRET=<random 32+ hex chars>
```

### 2. Cleartext HTTP is already handled — nothing to patch

Cleartext to `http://127.0.0.1:8787/token` works out of the box in **debug**
builds and is impossible in **release** builds. Do not "temporarily" widen this;
the split is permanent and needs no local edits.

| | debug | release |
|---|---|---|
| `res/xml/network_security_config.xml` | `src/debug` override: cleartext for `localhost`, `127.0.0.1`, `10.0.2.2` only | `src/main`: `cleartextTrafficPermitted="false"` |
| `isAllowedTokenEndpoint()` (`config/AppSettings.kt`) | `https://` or `http://` | `https://` only |

Both `LiveTypeSettings.isConfigured` and `MainActivity.saveSettings()` go
through `isAllowedTokenEndpoint()`, which is gated on `BuildConfig.DEBUG`
(hence `buildFeatures { buildConfig = true }` in `app/build.gradle.kts`).

The manifest keeps `android:usesCleartextTraffic="false"`, but on API 24+ the
network security config wins, so the debug loopback exception applies. minSdk is
28.

To confirm the split after touching either file:

```bash
AAPT2=$ANDROID_HOME/build-tools/35.0.0/aapt2
$AAPT2 dump xmltree --file res/xml/network_security_config.xml \
  app/build/outputs/apk/debug/app-debug.apk     # base false + loopback domain-config
# release paths are shortened by optimizeReleaseResources; grep the res/*.xml
# entries of app/build/outputs/apk/release/app-release-unsigned.apk instead —
# the network-security-config there must contain base-config only.
```

### 3. Start the worker

```bash
cd worker
npx wrangler dev --ip 0.0.0.0
# Serves on http://0.0.0.0:8787
# Auto-reloads on src changes
```

### 4. Set up ADB reverse tunnel

```bash
adb reverse tcp:8787 tcp:8787
# Forwards phone:8787 → mac:8787 over USB
```

Verify: `adb reverse --list` should show `UsbFfs tcp:8787 tcp:8787`

### 5. Build and install APK

```bash
cd android
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 6. Configure the app

On the phone, in LiveType settings:
- **Token endpoint**: `http://127.0.0.1:8787/token`
- **Device secret**: paste from `worker/.dev.vars`
- Languages, prompt, keywords as desired

### 7. Test

- Open any text field, select LiveType as keyboard
- Press the microphone button
- Watch logs: `adb logcat --pid=$(adb shell pidof -s dev.dobrinskiy.livetype) | grep -E "(LiveTypeIme|LiveTypeToken)"`

## Tests and CI

```bash
cd worker && npx vitest run    # the only automated test suite
cd android && ./gradlew assembleDebug
```

`.github/workflows/ci.yml` runs exactly those two on push to `main` and on every
pull request. Nothing in CI needs secrets, and CI never touches a device.

## Logging

The app logs errors with tags:
- `LiveTypeToken` — token fetch from worker
- `LiveTypeIme` — session lifecycle errors

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Failed to connect to /X.X.X.X` | Phone can't reach Mac IP | Use `adb reverse` + `127.0.0.1` URL |
| `unexpected end of stream` | Worker died or adb tunnel lost | Re-run `adb reverse` + restart worker |
| `Model "..." is not supported in transcription mode` | `?model=` passed on the realtime WebSocket URL | Connect to bare `wss://api.openai.com/v1/realtime` — see Model Notes |
| `You must not provide a model parameter for transcription sessions` | Same as above | Same as above |
| `languages parameter not supported` | Model doesn't support language hint | Remove `languages` from client_secrets request body |
| `no credits remaining` (429, `credit_balance_exhausted`) | OpenAI account out of credits | Top up billing. Note `/token` still returns **200** — minting an ephemeral secret is free, so the failure only surfaces at the WebSocket stage |

## Model Notes

### The worker owns the model choice

The device never picks the model. `worker/src/index.ts` builds the entire
client_secrets request server-side; the POST body from the phone is parsed as
**hints only** (`languages`, `prompt`, `keywords`) and a `model` field in that
body is ignored. Change the model with the optional `TRANSCRIPTION_MODEL` var
(defaults to `gpt-live-transcribe`); an off-allowlist value returns
`500 {"error":"Worker is misconfigured"}` rather than reaching OpenAI.

Hints are clamped (8 languages, 100 keywords, 2000-char prompt; entries trimmed
and de-duplicated) and any hint the selected model rejects is dropped, because
OpenAI 400s the whole request over one unsupported hint:

| Model | languages | prompt | keywords |
|---|---|---|---|
| `gpt-live-transcribe` (default) | yes | yes | yes |
| `gpt-transcribe` | yes | yes | yes |
| `gpt-4o-transcribe` | no | yes | no |
| `gpt-4o-mini-transcribe` | no | yes | no |
| `gpt-realtime-whisper` | no | no | no |
| `whisper-1` | no | yes | no |

Consequence for the client: `RealtimeTranscriber.sessionUpdate()` **must not**
send a `transcription` block. OpenAI makes `model` mandatory whenever that block
is present (`Missing required parameter:
'session.audio.input.transcription.model'`), and a device-sent model does
override the token's — so including it would hand model choice back to the
phone. Omitting the block leaves the token's config intact; the update exists
only to re-assert the audio format and produce `session.updated`, the app's
ready signal.

Also note: **do not export non-function values from `worker/src/index.ts`.**
workerd inspects every named export of the entry module and fails to boot with
`Incorrect type for map entry ...: the provided value is not of type 'function
or ExportedHandler'`. Hence `defaultModel()` instead of an exported constant.

### WebSocket URL

**Do not pass `?model=` on the WebSocket URL.** A transcription session gets its
model from the ephemeral token (`session.audio.input.transcription.model`), and
the realtime endpoint rejects a session model outright:

| WS URL | Result |
|--------|--------|
| `wss://api.openai.com/v1/realtime` | works |
| `...?intent=transcription` | works |
| `...?model=gpt-live-transcribe` | `Model "..." is not supported in transcription mode` |
| `...?model=gpt-realtime` | `You must not provide a model parameter for transcription sessions` |

All six models in the table above are available on the current key and accepted
by `/v1/realtime/client_secrets` (verified 2026-08-01). The rest of the
`gpt-realtime*` family is for speech-to-speech sessions, not transcription, and
is deliberately off the allowlist.
