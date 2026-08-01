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
means `compileDebugKotlin` was `UP-TO-DATE` and nothing changed. Compare the
APK's mtime against the clock rather than trusting the build log.

**The same applies after every merge.** Merging a branch — especially work done
by a parallel agent in a worktree — is a change to the app like any other, and
until the merged APK is on the phone nothing has actually been verified. A
green `assembleDebug` only proves it compiles; parallel agents can each build
cleanly and still combine into something broken at runtime. So after every
merge: build, install, and look at the result on the device.

```bash
# after `git merge`
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew assembleDebug \
  && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb exec-out screencap -p > /tmp/shot.png     # then actually look at it
```

Screenshotting is not optional for UI work — layout regressions (overlapping
views, a control pushed off-screen, an invisible tint) are invisible to the
compiler and to the logs.

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

The Android **debug** build also reads this file and bakes `DEVICE_SECRET` in —
see [Debug vs release configuration](#debug-vs-release-configuration).

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

**Debug builds configure themselves** — nothing to type. See
[Debug vs release configuration](#debug-vs-release-configuration). Set
languages, prompt and keywords as desired; the endpoint and device secret are
already filled in.

Release builds start blank and must be configured by hand:
- **Token endpoint**: your deployed worker's `https://.../token`
- **Device secret**: paste from wherever you keep it

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

## Debug vs release configuration

`android/app/build.gradle.kts` reads `worker/.dev.vars` at configuration time
and exposes two `buildConfigField`s. They differ per build type:

| | `BuildConfig.DEFAULT_TOKEN_ENDPOINT` | `BuildConfig.DEFAULT_DEVICE_SECRET` |
|---|---|---|
| **debug** | `http://127.0.0.1:8787/token` | `DEVICE_SECRET` from `worker/.dev.vars` |
| **release** | `""` | `""` |

`AppSettings.load()` uses them as the `SharedPreferences` **defaults only**. A
value the user saved always wins — the baked values never overwrite stored
settings, and clearing a field on the phone stays cleared.

Rules the build enforces:

- **`OPENAI_API_KEY` is never read into the app.** It belongs to the worker
  alone. Only `DEVICE_SECRET` crosses over, and only into debug.
- **A missing `worker/.dev.vars` is not an error.** Fresh clones and CI have no
  such file; the secret falls back to `""` and the build carries on.
- **Release gets literal empty strings**, not "whatever was parsed". The parse
  result is wired to the debug build type only.

### The debug APK contains the device secret — do not distribute it

Baking the secret in trades secrecy for convenience on a phone you own and
install to over `adb`. The consequence is unavoidable: `app-debug.apk` has the
secret in plaintext inside `classes*.dex`, and anyone holding that APK can mint
tokens against your worker.

- Never attach a debug APK to a GitHub Release, an issue, a chat, or a bug
  report. Ship `assembleRelease` output only.
- `*.apk` is in `.gitignore` for this reason. Do not remove that line.
- If a debug APK does escape, rotate `DEVICE_SECRET` in `worker/.dev.vars` and
  in the deployed worker.

Verify a release build is clean before publishing it:

```bash
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew assembleRelease
SECRET=$(grep '^DEVICE_SECRET=' ../worker/.dev.vars | cut -d= -f2-)
REL=app/build/outputs/apk/release/app-release-unsigned.apk
strings "$REL" | grep -F "$SECRET"          # expect: no output, exit 1
strings app/build/outputs/apk/debug/app-debug.apk | grep -cF "$SECRET"  # expect: 1
```

The debug check is the control: if it does not find the secret, the release
check proves nothing about your grep.

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
