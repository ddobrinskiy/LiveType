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

**Put open questions in `OPEN_QUESTIONS.md`, not at the end of a reply.** A
question asked in the last paragraph of a long answer gets missed, and then a
decision quietly defaults to whatever the code already does. Anything needing
the user's call — or any concern they should know about — goes in that file the
moment it comes up, and the resolved section records what was decided and why.

Related documents:

| File | Purpose |
|---|---|
| `README.md` | For a stranger: what this is and how to run it |
| `AGENTS.md` | This file: local setup, workflow, API gotchas |
| `ARCHITECTURE.md` | Why the design is what it is; decisions and rationale |
| `OPEN_QUESTIONS.md` | Awaiting a decision, plus known concerns |
| `QA.md` | What is actually verified vs. what merely compiles |

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
# One-off: create the usage tables in the local D1 wrangler dev will use.
npx wrangler d1 execute livetype-usage --local --file=./migrations/0001_usage_events.sql -y
npx wrangler dev --ip 0.0.0.0
# Serves on http://0.0.0.0:8787
# Auto-reloads on src changes
```

Routes: `POST /token`, `POST /usage`, `GET /usage` — see
[Billing and usage tracking](#billing-and-usage-tracking).

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

The worker suite boots a real `workerd` through **miniflare** to get a real
in-memory D1 for the billing tests — the SQL, the `INSERT OR IGNORE`
idempotency and the aggregate queries are exercised for real, not stubbed. That
is why `miniflare` is a pinned devDependency (same version wrangler already
resolves, so it dedupes) and why the CI worker job needs **Node 22**: both
`wrangler` and `miniflare` declare `engines: >=22`.

Time-dependent tests fake only `Date` (`vi.useFakeTimers({ toFake: ["Date"] })`)
— the worker is imported into the Node test process and only its D1 binding
comes from miniflare, so the worker's `Date.now()` is the test's clock while
miniflare's own timers keep running.

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
| `/usage` returns `500 {"error":"Could not read usage"}` or `"Could not record usage"` | D1 has no `usage_events` table | Run the `wrangler d1 execute … --file=./migrations/0001_usage_events.sql` step |
| `/usage` returns `500 {"error":"Worker is misconfigured"}` | no `DB` binding, or a bad `TRANSCRIPTION_MODEL` | Check `wrangler.jsonc` `d1_databases` and the model allowlist |

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

## Billing and usage tracking

### Where the numbers come from

OpenAI puts the **billable quantity itself** on the transcription WebSocket, in
the `conversation.item.input_audio_transcription.completed` event the app
already handles:

```jsonc
// duration-billed models
{ "item_id": "item_E81t1mmrLaGrBlAjuBJp2", "usage": { "type": "duration", "seconds": 3 } }
// token-billed models (gpt-4o-transcribe, gpt-4o-mini-transcribe)
{ "item_id": "item_…", "usage": { "type": "tokens", "input_tokens": 30,
    "input_token_details": { "text_tokens": 0, "audio_tokens": 30 }, … } }
```

`duration.seconds` is **ceil'd per committed buffer** and is billed even when
the transcript comes back empty. Audio tokens are exactly **600 per minute**.
Both measured against the live API on 2026-08-01.

The phone forwards that payload verbatim; **every billing decision is made in
the worker.** The Costs API was evaluated and rejected: it needs an admin key
(a project key gets a hard 403 on all of `/v1/organization/*`), buckets only by
UTC day, and does not group by model.

### The phone renders, it does not compute

The device must not hold a price table, convert tokens to minutes, or decide
what "today" means. It posts the raw event and renders the numbers `GET /usage`
hands back. This is the same rule as model selection: a device-supplied `model`,
`price`, `usd` or `usd_nanos` in a `POST /usage` body is ignored, and a test
asserts it.

### `POST /usage` — ingest

Auth: `X-Device-Secret`, same `secureEquals` check as `/token`.

```jsonc
// request
{ "item_id": "item_E81t1mmrLaGrBlAjuBJp2",       // required, [A-Za-z0-9_-]{1,128}
  "usage": { "type": "duration", "seconds": 3 } } // or the "tokens" shape above
```

| Status | Body | When |
|---|---|---|
| 202 | `{"ok":true,"duplicate":false}` | recorded |
| 202 | `{"ok":true,"duplicate":true}` | `item_id` already stored — retries are free |
| 400 | `{"error":"<reason>"}` | malformed body, bad `item_id`, unknown `usage.type`, non-numeric/negative/out-of-range quantity |
| 401 | `{"error":"Unauthorized"}` | wrong or missing device secret |
| 500 | `{"error":"Worker is misconfigured"}` | no `DB` binding, or an off-allowlist `TRANSCRIPTION_MODEL` |

A single commit is capped at **14400 s** (or 144000 audio tokens) so one bad
row cannot poison the chart. `item_id` is the primary key, so the phone's retry
outbox can post the same record as often as it likes.

Both usage shapes are accepted whatever model is configured — `TRANSCRIPTION_MODEL`
can change while a session is in flight, and the conversion is fixed, so
accepting the other shape loses nothing and grants the device no leverage.

### `GET /usage?tz_offset_minutes=<int>` — read

`tz_offset_minutes` is **minutes to add to UTC to get device-local time** —
exactly `TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000`
in Java. Moscow `180`, New York in winter `-300`. Anything non-integer or
outside ±840 silently falls back to UTC; the applied value is echoed back.

```jsonc
{
  "model": "gpt-live-transcribe",
  "price": { "usd_per_minute": 0.017, "usd_micros_per_minute": 17000,
             "unit": "duration", "estimated": false },
  "windows": {
    "today":    { "seconds": 412, "usd": 0.116733, "usd_micros": 116733, "sessions": 23 },
    "last_7d":  { … },
    "last_30d": { … }
  },
  "tz_offset_minutes": 180,
  "as_of": "2026-08-01T14:31:07.000Z",
  "source": "device_reported"
}
```

- Windows are **whole local calendar days including today**, so `last_7d` is
  today plus the six before it — not the last 168 hours.
- `usd == usd_micros / 1e6` always. Render either; never re-derive dollars from
  `seconds`, because the row's price may differ from the current one.
- **`estimated: true`** for `gpt-4o-transcribe` and `gpt-4o-mini-transcribe`.
  OpenAI does not publish an audio-input **token** price for those two; the
  per-minute figure is OpenAI's own "estimated cost" column. The UI must say so
  rather than imply cent-accuracy. The default model is not affected.
- `source` is `"device_reported"` today. It exists so an admin-key
  reconciliation path can be added later without a breaking change — the phone
  should display whatever it receives, not assume the constant.
- This is a spend **meter, not an audit**. It counts what this app reported; it
  cannot see free credits, prepaid discounts, or spend from anything else on the
  OpenAI account. A session lost to a dead phone or a dropped network
  under-counts.

Prices live in `MODEL_PRICES` in `worker/src/index.ts`, as integer micro-USD per
minute, transcribed from <https://developers.openai.com/api/docs/pricing> on
2026-08-01. **Every key of `SUPPORTED_MODELS` must have an entry**; a test
enforces it. When a price changes, edit the table — old rows keep the price
frozen into them and are never re-priced.

### D1 provisioning

`worker/wrangler.jsonc` now carries the project's first binding. `database_id`
in it is a **placeholder**; `wrangler dev` ignores it (local D1 is created on
demand under `worker/.wrangler/`) but `wrangler deploy` does not.

```bash
cd worker

# 1. Local dev — create the tables in the local SQLite that `wrangler dev` uses.
#    Re-runnable: the migration is IF NOT EXISTS throughout.
npx wrangler d1 execute livetype-usage --local --file=./migrations/0001_usage_events.sql -y

# 2. Real database, once per account. Copy the printed database_id into
#    wrangler.jsonc, replacing REPLACE_WITH_ID_FROM_WRANGLER_D1_CREATE.
npx wrangler d1 create livetype-usage

# 3. Apply the schema remotely, then deploy.
npx wrangler d1 migrations apply livetype-usage --remote
npx wrangler deploy

# Handy afterwards:
npx wrangler d1 execute livetype-usage --local  --command "SELECT * FROM usage_events ORDER BY created_at_ms DESC LIMIT 10"
npx wrangler d1 execute livetype-usage --remote --command "SELECT model, COUNT(*), SUM(usd_nanos)/1e9 AS usd FROM usage_events GROUP BY model"
```

Free plan: 10 databases, 500 MB, 5 M rows read/day. One dictation is a few
hundred bytes, so this will not grow into anything.

**Not KV.** KV caps writes at 1/sec/key and 1000/day on the free plan, and a
running total in KV is a read-modify-write race against eventual consistency.
D1 is SQLite: one row per session, `SELECT SUM(...) WHERE created_at_ms >= ?`.

### Schema

`worker/migrations/0001_usage_events.sql`, table `usage_events`, keyed on
`item_id`. Each row freezes `model`, `price_micro_usd_per_minute`,
`price_estimated` and the resolved `usd_nanos` **as of the session**, keeps the
raw reported `quantity` alongside the normalised `billable_seconds`, and stamps
`created_at_ms` from the **worker** clock — the device's clock is never trusted
for bucketing, only its UTC offset.

Costs are stored in **nano**-USD: one second of `gpt-live-transcribe` is 283333
nanos, and rounding that to whole micro-dollars would lose a fifth of it.
