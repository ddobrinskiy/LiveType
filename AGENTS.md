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

**`adb install` printing `Success` is not proof either.** Observed 2026-08-04: an
install reported success and `dumpsys package` showed the new `versionName` and a
fresh `lastUpdateTime`, while the APK resident on the phone was an older build
that lacked the new strings entirely — an hour of debugging went into the app
"not rendering" code it did not have. Verify by hash, and pass `-s <serial>`
whenever more than one device or emulator might be attached:

```bash
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
APK=$(adb -s <serial> shell pm path dev.dobrinskiy.livetype | sed 's/^package://' | tr -d '\r')
adb -s <serial> pull "$APK" /tmp/installed.apk
shasum -a256 /tmp/installed.apk app/build/outputs/apk/debug/app-debug.apk   # must match
```

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

That is the single-device shape, and it authenticates as the device id `default`.
The real file uses one variable per device instead —
`DEVICE_SECRET_DAVID`, `DEVICE_SECRET_MOM`, plus `OWNER_DEVICE_ID` and
`DEVICE_CAPS` — which is exactly what the deployed Worker holds in its secret
store, so `wrangler dev` and production see the same devices. See
`worker/.dev.vars.example` and
[Per-device secrets and caps](#per-device-secrets-and-caps).

**`worker/.dev.vars` is the one place device secrets are written down.** It is
gitignored; nothing else on disk holds them, and they are not recoverable from
Cloudflare.

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
# Applies every migration in order; 0002 adds device_id and cannot be re-run.
npx wrangler d1 migrations apply livetype-usage --local
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
languages and prompt as desired; the endpoint, device secret and keyword list
are already filled in. The keywords come from `data/keywords.txt` — but only
until you press Save, after which the stored list wins and a rebuild will not
change it. Clear the app's data (or edit the list on the phone) to pick up a
new baked default.

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

`android/app/build.gradle.kts` reads `worker/.dev.vars` and `data/keywords.txt`
at configuration time and exposes three `buildConfigField`s. They differ per
build type:

| | `DEFAULT_TOKEN_ENDPOINT` | `DEFAULT_DEVICE_SECRET` | `DEFAULT_KEYWORDS` |
|---|---|---|---|
| **debug** | `http://127.0.0.1:8787/token` | `DEVICE_SECRET` from `worker/.dev.vars` | `data/keywords.txt`, parsed |
| **release** | `""` | `""` | `""` |

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

### The keyword list lives in `data/`, encrypted

The custom vocabulary sent to OpenAI as transcription hints is maintained by
hand in **`data/keywords.txt`** — one term per line, `#` comments and blank
lines allowed, duplicates dropped. It is **gitignored**: it is personal
vocabulary and this repo is meant to be public. What gets committed is
**`data/keywords.txt.age`**, the same content encrypted to
`age1aqdf22l6p03g408sg9m9jxu6hwmml0vn9sr7jukff0ty35dwsuuswv9ak4`.

```bash
./scripts/keywords-encrypt.sh    # data/keywords.txt -> data/keywords.txt.age; commit the .age
./scripts/keywords-decrypt.sh    # the reverse, on a fresh clone; needs the private identity
```

Encrypting needs the **public** recipient only — no private key is read.
Decrypting reads the identity at `~/.config/chezmoi/key.txt`, overridable with
`LIVETYPE_AGE_IDENTITY`. Both scripts fail loudly if `age` is missing, the input
file is absent, or the identity does not exist; decrypt also refuses to
overwrite an existing plaintext without `--force`, and takes an output path so
you can round-trip into `/tmp` without touching your working copy. age output is
randomised, so the `.age` file differs on every run even when nothing changed.

Rules, same shape as the `.dev.vars` ones above:

- **A missing `data/keywords.txt` is not a build error.** Fresh clones and CI
  only have the `.age` file; `DEFAULT_KEYWORDS` falls back to `""` and
  `AppSettings.load()` then falls back to `R.string.default_keywords`, exactly
  as release does.
- **Only a `SharedPreferences` default.** A list edited on the phone wins, and a
  list deliberately cleared stays cleared.
- The build reads the file through `providers.fileContents(...)`, so it is a
  tracked configuration input: editing the list prints
  `configuration cache cannot be reused because file '../data/keywords.txt' has
  changed` rather than baking a stale list into the APK.
- `javaStringLiteral()` escapes `\`, `"`, newline, CR and tab, because
  `buildConfigField` pastes its argument into `BuildConfig.java` verbatim and
  the list is multi-line.

### Test device metrics — converting real-world sizes to dp

The keyboard is laid out entirely in Kotlin using `dp()`, so any request phrased
in centimetres ("make it a centimetre taller") needs converting. Measured on the
test device:

| | |
|---|---|
| Device | Pixel 9 |
| Resolution | 1080 × 2424 px |
| Density | **420 dpi** → density scale **2.625** |
| Screen width | 411 dp |
| **1 cm** | **≈ 166 px ≈ 63 dp** |
| 1 mm | ≈ 6.3 dp |

Confirm on any other device rather than assuming:

```bash
adb shell wm density     # "Physical density: 420"  -> scale = 420/160 = 2.625
adb shell wm size        # "Physical size: 1080x2424"
```

Then `dp = cm × (dpi / 2.54) / scale`, which for this phone is `cm × 63`.

Two things worth remembering when resizing the keyboard:

- **Width is the scarce axis, height is free.** The layout arithmetic lives in
  the `THUMB_BUTTON_DP` KDoc: three 72 dp keys plus gaps already leave only
  141 dp for the status column. Growth requests should almost always be
  satisfied vertically.
- **`systemInsetPadding()` is not part of the content height.** It reserves the
  gesture-bar area at the bottom. Any padding added to raise the buttons is
  *additional* to it — folding the two together double-counts the gesture area
  on some devices and drops it on others.

### Two traps when changing any built-in default

Both of these cost real debugging time on 2026-08-01. Read them before editing
`default_keywords`, `default_languages`, `default_prompt`, or anything else that
seeds settings.

**1. A real newline in `strings.xml` is not a newline.** Android collapses
literal line breaks inside a resource value into spaces. The value needs the
two-character escape `\n`. This bites hardest when generating the file from a
script: Python's `re.sub` interprets escapes *in the replacement string*, so
`"\\n"` silently becomes a real newline. Use a lambda replacement, and verify:

```bash
python3 -c "
import re; s=open('android/app/src/main/res/values/strings.xml').read()
v=re.search(r'name=\"default_keywords\"[^>]*>(.*?)</string>',s,re.S).group(1)
print('real newlines:', v.count(chr(10)), '(must be 0)   literal:', v.count('\\\\n'))"
```

**2. Changing a default does nothing to an installed app.** Everything the build
bakes in — endpoint, device secret, keywords, languages, prompt — is a
`SharedPreferences` *default*. Once a value is stored on the phone it always
wins, by design, so the user's edits are never clobbered. To actually see a new
default you must wipe app data:

```bash
adb shell pm clear dev.dobrinskiy.livetype
```

That resets **everything**, including anything the user typed by hand. Back the
current values up first — dump them with `uiautomator` and read the `EditText`
fields — and be aware `adb shell input text` cannot type Cyrillic, so a Russian
prompt cannot be restored automatically.

Note also that `default_languages` and `default_prompt` are locale-resolved:
on an English device a wipe pulls the English `values/` copies, not
`values-ru/`.

### The debug APK contains the device secret and your keyword list — do not distribute it

Baking the secret in trades secrecy for convenience on a phone you own and
install to over `adb`. The consequence is unavoidable: `app-debug.apk` has the
secret in plaintext inside `classes*.dex`, and anyone holding that APK can mint
tokens against your worker.

The same APK also carries `data/keywords.txt` in the clear, for the same reason.
Encrypting the list in git and then handing out a debug APK that contains it
would defeat the point — the list is personal vocabulary, so treat the debug APK
as being as private as the plaintext file.

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

The keyword list needs the same control, and a term that is **not** already in
`res/values/strings.xml` — the stock defaults ship in both APKs by definition,
so grepping for `Tonkeeper` proves nothing. Add a nonsense probe term to
`data/keywords.txt`, rebuild both, then remove it:

```bash
PROBE=Zzqxleakprobe                      # add this line to data/keywords.txt first
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew assembleDebug assembleRelease
strings app/build/outputs/apk/debug/app-debug.apk | grep -c "$PROBE"              # expect 1
strings app/build/outputs/apk/release/app-release-unsigned.apk | grep -c "$PROBE" # expect 0
```

Verified this way on 2026-08-01: debug 1, release 0.

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
| `/usage` returns `500 {"error":"Could not read usage"}` or `"Could not record usage"` | D1 has no `usage_events` table | Run the `wrangler d1 migrations apply livetype-usage --local` step |
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

### Per-device secrets and caps

Four env vars, all optional, all read through one `parseDeviceConfig(env)`:

| Var | Shape | What it does |
|---|---|---|
| `DEVICE_SECRET` | one secret | The single-device path. Authenticates as the device id `default`, which is also what migration `0002` backfilled onto pre-existing rows. |
| `DEVICE_SECRET_<NAME>` | one variable per device | The suffix, lower-cased, is the device id: `DEVICE_SECRET_MOM` → `mom`. Ids match `[a-z0-9_-]{1,32}`; secrets are 24–512 characters. Blank means "not configured" rather than broken. |
| `OWNER_DEVICE_ID` | `"david"` | Who sees the `devices` breakdown. Unset falls back to `default` **if that device exists**, else nobody. |
| `DEVICE_CAPS` | `{"mom":1}` | **Daily** allowance in USD, enforced at `POST /token`. Absent id means uncapped. |
| `CAP_TZ_OFFSET_MINUTES` | `"180"` | Minutes to add to UTC for the cap's day boundary. Defaults to UTC. Junk falls back to UTC rather than failing. |

Two rules that are easy to break when editing this code:

1. **Identity comes from which secret matched, never from the request.**
   `authoriseDevice` returns the device id, and that is what goes in the ledger.
   The same reasoning as `model` (§3.2 of ARCHITECTURE.md). The registry is built
   by scanning `env` for the `DEVICE_SECRET_` prefix, so a device is added or
   revoked with one `wrangler secret put`/`delete` and no code change.
2. **A configuration that does not parse takes every route to
   `500 Worker is misconfigured`.** Deliberately loud: the alternative is a typo
   like `{"mum":1}` silently leaving `mom` uncapped. Two exceptions —
   *no* secrets configured yields 401 (fail closed, unchanged behaviour), and a
   short legacy `DEVICE_SECRET` is grandfathered so the check could not lock the
   owner out.

The cap is checked in `POST /token` **before** OpenAI is called, so a refusal
costs nothing:

| Status | Body | When |
|---|---|---|
| 402 | `{"error":"Daily spend cap reached","cap":{…}}` | this device's spend today ≥ its cap. `TokenProvider` turns this into `SpendCapReachedException` and the keyboard shows a localised sentence. |
| 500 | `{"error":"Could not verify the spend cap"}` | a capped device whose ledger could not be read. Fails **closed** on purpose. |

An uncapped device never runs the query, so it pays neither the extra D1 read on
the latency path nor that failure mode. `POST /usage` keeps accepting reports from
a device that is over its cap — the audio was already charged, and refusing the
report would only hide real spend.

**The cap's day is the worker's day, not the phone's.** It is
`localDayStartMs(now, CAP_TZ_OFFSET_MINUTES)`, while the `today` window is the
same function with the offset the *phone* sent. Do not "simplify" the cap to reuse
the phone's value: a device that picks its own boundary can shift the window and
give itself a fresh allowance. The response publishes the boundary it used
(`period`, `period_tz_offset_minutes`) so the UI can say which day it means.

Testing this locally needs the same variables in `.dev.vars`, which `wrangler dev`
reads directly. The Android debug build bakes in `DEVICE_SECRET` if present and
otherwise the **first** `DEVICE_SECRET_*` entry in the file, so keep your own
phone's first (`readDebugDeviceSecret` in `app/build.gradle.kts`).

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

Auth: `X-Device-Secret`, same `authoriseDevice` check as `/token`. The device id
whose secret matched is written to `usage_events.device_id`; a `device_id` in the
body is ignored exactly as `model` is. See
[Per-device secrets and caps](#per-device-secrets-and-caps).

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
| 500 | `{"error":"Worker is misconfigured"}` | no `DB` binding, an off-allowlist `TRANSCRIPTION_MODEL`, or a `DEVICE_SECRET_*`/`DEVICE_CAPS`/`OWNER_DEVICE_ID` that does not parse |

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
  "device_id": "mom",          // which secret matched, not what the phone claimed
  "is_owner": false,
  "cap": {                     // null when this device is uncapped
    "usd": 1, "usd_micros": 1000000,
    "spent_usd": 0.23, "spent_usd_micros": 230000,
    "remaining_usd": 0.77, "remaining_usd_micros": 770000,
    "period": "day",           // the WORKER's day, not the phone's
    "period_tz_offset_minutes": 0
  },
  "devices": [ … ],            // owner only; see below
  "tz_offset_minutes": 180,
  "as_of": "2026-08-01T14:31:07.000Z",
  "source": "device_reported"
}
```

- Windows are **whole local calendar days including today**, so `last_7d` is
  today plus the six before it — not the last 168 hours.
- **The windows cover the calling device only.** `device_id` says which one that
  is. `OWNER_DEVICE_ID` additionally receives a `devices` array — one entry per
  device that is configured *or* has history — each with the same three windows,
  a `cap_day_usd_micros` (spend inside the *cap's* day), a `cap`, and
  `configured: false` when its secret has been revoked. Revoked devices keep their
  history on purpose.
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

### D1 provisioning and deploying

`worker/wrangler.jsonc` carries the real `database_id`
(`850f00b2-d22f-49ff-bcdb-f0eca6f087da`, region WEUR). `wrangler dev` ignores it
and makes its own local D1 under `worker/.wrangler/`; `wrangler deploy` uses it.
The two are unrelated — see ARCHITECTURE.md §3.8.

```bash
cd worker

# Local dev: create/patch the tables in the SQLite `wrangler dev` uses.
npx wrangler d1 migrations apply livetype-usage --local

# Remote schema, before deploying code that depends on it. There is no `-y`;
# a non-interactive shell auto-confirms.
npx wrangler d1 migrations apply livetype-usage --remote

# Secrets. Feed the value on stdin so it never lands in shell history or argv.
grep '^OPENAI_API_KEY=' .dev.vars | sed 's/^OPENAI_API_KEY=//' | tr -d '\n' \
  | npx wrangler secret put OPENAI_API_KEY
grep '^DEVICE_SECRET=' .dev.vars | sed 's/^DEVICE_SECRET=//' | tr -d '\n' \
  | npx wrangler secret put DEVICE_SECRET

npx wrangler deploy

# What is actually live right now:
npx wrangler secret list            # names only, never values
npx wrangler deployments list
npx wrangler d1 migrations list livetype-usage --remote

# Reading the ledger:
npx wrangler d1 execute livetype-usage --local  --command "SELECT * FROM usage_events ORDER BY created_at_ms DESC LIMIT 10"
npx wrangler d1 execute livetype-usage --remote --command "SELECT device_id, COUNT(*), SUM(usd_nanos)/1e9 AS usd FROM usage_events GROUP BY device_id"
```

**Migrate the remote database before deploying code that needs the new column.**
The reverse order leaves the live worker 500ing on every `POST /usage` for as long
as the gap lasts.

**A new secret takes ~30 s to serve.** `wrangler secret put` reports success
immediately and creates a new version, but requests keep being served the old
value for around half a minute. Measured 2026-08-04: a changed `DEVICE_CAPS` was
still absent at t+20 s and live at t+30 s. Verifying straight after the put reads
as "the feature is broken" — poll until the behaviour flips instead of concluding
anything from one early request.

**A fresh account needs two things wrangler cannot do for you:** a verified
account email (otherwise every write to `/workers/scripts/*` fails with
`10034`) and a registered `workers.dev` subdomain (otherwise `deploy` fails with
`10007`). The subdomain name is permanent and account-wide, so it is the owner's
choice, not an agent's. D1 is gated on neither, so a database can exist while no
worker does.

Free plan: 10 databases, 500 MB, 5 M rows read/day. One dictation is a few
hundred bytes, so this will not grow into anything.

**Not KV.** KV caps writes at 1/sec/key and 1000/day on the free plan, and a
running total in KV is a read-modify-write race against eventual consistency.
D1 is SQLite: one row per session, `SELECT SUM(...) WHERE created_at_ms >= ?`.

### Schema

`worker/migrations/0001_usage_events.sql` plus `0002_usage_events_device.sql`,
table `usage_events`, keyed on `item_id`. Each row freezes `model`, `price_micro_usd_per_minute`,
`price_estimated` and the resolved `usd_nanos` **as of the session**, keeps the
raw reported `quantity` alongside the normalised `billable_seconds`, and stamps
`created_at_ms` from the **worker** clock — the device's clock is never trusted
for bucketing, only its UTC offset.

Costs are stored in **nano**-USD: one second of `gpt-live-transcribe` is 283333
nanos, and rounding that to whole micro-dollars would lose a fifth of it.
