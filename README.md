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

> LiveType is a personal-scale project. There is no hosted service: you deploy
> your own token Worker against your own OpenAI account, and sideload the app.
> Start at [Install](#install). See [Security model](#security-model) before
> handing a **configured** phone or APK to anyone else.

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
| `data/keywords.txt.age` | The maintainer's personal vocabulary list, encrypted (see below). Nothing needs it. |
| `scripts/` | `keywords-encrypt.sh` / `keywords-decrypt.sh` for that file. |
| `android/keystore.properties.age` | The maintainer's release-signing password, encrypted to the same recipient. Nothing needs it either — see [Cutting a release](#cutting-a-release-maintainer). |
| `AGENTS.md` | Architecture notes, the local dev loop, and hard-won API details. |

**`data/keywords.txt.age` is not a secret you are missing.** It is one person's
list of jargon that transcription tends to mangle, encrypted with
[age](https://age-encryption.org) because it is personal and this repo is
public. Debug builds bake the decrypted list in as the default keyword list;
without the key you get the stock defaults from `res/values/strings.xml`
instead, and everything builds and runs exactly the same. Keep your own list by
writing `data/keywords.txt` (gitignored, one term per line, `#` comments
allowed) — or just type your terms into the app's **Terms** field.

## Prerequisites

- Android 9 (API 28) or newer on the phone.
- A Cloudflare account (the free plan is enough).
- An OpenAI API key with **prepaid credit on the account**. This is the step
  people get wrong most often, so in detail:
  - A ChatGPT Plus/Pro subscription does **not** include API access. The API is
    billed separately.
  - Add credit at **platform.openai.com → Settings → Billing**. There is a
    minimum top-up (currently $5); a new account with $0 credit returns
    `429 insufficient_quota` on the first dictation and nothing works.
  - Create a **dedicated project** for LiveType and a key scoped to it
    (**Settings → API keys → Create new secret key**, project-scoped), then set
    a low monthly budget and an email alert on that project. The device secret
    is a shared secret (see [Security model](#security-model)); a per-project
    budget is what bounds the damage if it leaks.
  - The key looks like `sk-proj-…`. You paste it into Cloudflare once, in
    step 1 — never into the phone.
- Node.js 20+ for the Worker.
- To build the app yourself (optional — releases ship a signed APK): JDK 17 and
  the Android SDK (platform 35, build-tools 35.0.0). Android Studio provides
  both.

## Install

Three steps: deploy your Worker, install the APK, type two values into the app.

### Let an agent do it

Step 1 is entirely mechanical. If you use a coding agent (Claude Code, or
similar), clone the repo, `cd` into it and paste this — steps 2 and 3 happen on
the phone and are yours to do:

```text
Set up the LiveType token Worker in this repo for me. Read README.md
"Deploy the token Worker" first, then do it.

1. Confirm Node 20+ is installed and that `npx wrangler whoami` shows me logged
   in. If it does not, stop and tell me to run `npx wrangler login` myself.
2. In worker/: create MY OWN D1 database with `npx wrangler d1 create
   livetype-usage`, put the id it prints into wrangler.jsonc as database_id
   (replacing the one committed in the repo, which is not mine), then run
   `npx wrangler d1 migrations apply livetype-usage --remote`.
3. Generate a device secret with `openssl rand -hex 32`. Show it to me once at
   the end. Do not write it into any file in the repo.
4. Set both Worker secrets with `npx wrangler secret put OPENAI_API_KEY` and
   `npx wrangler secret put DEVICE_SECRET`. Prompt me to paste each value
   interactively; never put either one in a file, a command argument, or your
   own output.
5. Run `npm run deploy`.
6. Verify it works: POST to the deployed /token with the correct
   X-Device-Secret and confirm it returns a client secret, then repeat with a
   wrong secret and confirm 401.
7. Finish by printing exactly two things for me to type into the app: the
   Worker URL with /token appended, and the device secret from step 3.

Never commit a secret. Never echo my OpenAI key back to me.
```

Then skip to [step 2](#2-install-the-app).

### 1. Deploy the token Worker

Generate a device secret first — this is the credential the phone will present
to your Worker. It is not an OpenAI key and has nothing to do with your OpenAI
account; it exists only so strangers cannot spend your credit through your
Worker:

```bash
openssl rand -hex 32
# e.g. 9f2c...ab (64 hex characters) — keep this, you type it into the app later
```

The Worker writes a usage ledger to a D1 database. The `database_id` committed
in `worker/wrangler.jsonc` is the maintainer's and you cannot deploy against
it, so create your own first:

```bash
cd worker
npm ci
npx wrangler login

npx wrangler d1 create livetype-usage
# Paste the printed database_id into wrangler.jsonc, replacing the existing one.
npx wrangler d1 migrations apply livetype-usage --remote
```

Then set the two secrets and deploy. `wrangler secret put` prompts for the
value on stdin — it never appears in your shell history:

```bash
npx wrangler secret put OPENAI_API_KEY   # paste your sk-proj-… key
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

Smoke-test it before touching the phone — the first call should return JSON
containing a client secret, the second should return 401:

```bash
SECRET=<your device secret>
URL=https://livetype-token.your-account.workers.dev/token

curl -s -X POST "$URL" -H "X-Device-Secret: $SECRET" \
  -H 'Content-Type: application/json' -d '{"languages":["en"]}'

curl -s -o /dev/null -w '%{http_code}\n' -X POST "$URL" \
  -H 'X-Device-Secret: wrong' -H 'Content-Type: application/json' -d '{}'
```

A `429` or a quota error from the first call means the OpenAI account has no
credit — see [Prerequisites](#prerequisites).

#### Choosing a transcription model

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

#### Continuous deployment (optional)

In the Cloudflare dashboard: **Workers & Pages → your Worker → Settings →
Builds → Connect**, pointed at your fork. Root directory `worker`, build
command `npm ci`, deploy command `npx wrangler deploy`. Secrets stay in
Cloudflare; never put `OPENAI_API_KEY` in the repository.

### 2. Install the app

Download `LiveType-<version>.apk` from the
[GitHub Releases](../../releases) page and open it on the phone. Android will
ask you to allow installing from your browser or file manager; this is a
sideload, LiveType is not on any app store. No APK is tracked in git.

The release APK carries **no credentials and no vocabulary** — every
`BuildConfig` default is empty, so a fresh install starts blank and you
configure it in step 3. It is signed with the maintainer's release key
(SHA-256 `f817ef58…c9577bb5`), which is what lets later versions install as an
in-place update.

To build it yourself instead:

```bash
cd android
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release-unsigned.apk
```

`./gradlew` is a small bootstrap script that downloads Gradle 8.9 on first use;
there is no committed wrapper jar. It needs the Android SDK — either set
`ANDROID_HOME`, or create `android/local.properties` with
`sdk.dir=/path/to/Android/sdk`. Opening `android/` in Android Studio and
pressing Run works too.

Your own release build is **unsigned** and Android will refuse to install it as
is; sign it with your own key (`apksigner sign --ks …`). A self-signed build
cannot update an install that came from a Release APK, or the reverse — pick one
source and stay with it, or uninstall in between. `./gradlew assembleDebug`
remains the quickest path for development and is signed with the local debug
key automatically.

### 3. Configure LiveType

1. Open the LiveType app and grant microphone permission.
2. **Worker token endpoint** — the `/token` URL from step 1. Release builds
   accept `https://` only.
3. **Device secret** — the same `DEVICE_SECRET` you put in Cloudflare.
4. **Expected languages** — comma-separated BCP-47-ish codes, e.g. `ru,en`.
5. **Context** — a free-text prompt describing what you dictate.
6. **Terms** — domain words the model tends to mangle, one per line. Debug
   builds pre-fill this from `data/keywords.txt` if you keep one; see
   [Repository layout](#repository-layout).
7. Save, then **Enable LiveType** (Android input-method settings) and
   **Choose keyboard**.

Settings are stored in app-private `SharedPreferences` and excluded from cloud
backup and device transfer.

The **Spending** section at the bottom of the screen shows the price per minute
of the model the Worker has chosen, and what you spent today, over the last
7 days and over the last 30 days — whole local calendar days, today included.
The app reports each session's usage to the Worker and renders the numbers it
gets back; it computes no prices of its own. It is a meter of what this app
reported, not an OpenAI invoice.

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

### Cutting a release (maintainer)

Release signing is driven by `android/keystore.properties`, which holds a
keystore path plus its password. When that file is absent, no `signingConfig` is
registered at all and `assembleRelease` produces an unsigned APK — that is what
keeps fresh clones and CI building without the key.

The plaintext is gitignored; `android/keystore.properties.age` is the committed
copy, encrypted to the same age recipient as the keyword list (see
[Repository layout](#repository-layout)). Restore it on a new machine with:

```bash
age -d -i ~/.config/chezmoi/key.txt -o android/keystore.properties \
  -- android/keystore.properties.age
chmod 600 android/keystore.properties
```

Re-encrypt after any edit, and commit the result:

```bash
age -r age1aqdf22l6p03g408sg9m9jxu6hwmml0vn9sr7jukff0ty35dwsuuswv9ak4 \
  -o android/keystore.properties.age -- android/keystore.properties
```

That covers the password and the path, **not the keystore itself**. The
keystore lives outside the repo at `~/.secrets/livetype/release.jks` and has no
other copy — back it up separately. Its certificate is SHA-256
`f817ef58…c9577bb5`; verify a restored copy with
`keytool -list -v -keystore release.jks -alias livetype`. Without that file no
future build can update an already-installed LiveType, and the password alone
does not help.

```bash
# 1. bump versionCode AND versionName in android/app/build.gradle.kts
cd android && ./gradlew clean assembleRelease
# 2. confirm the release carries no secrets and no personal vocabulary
#    (empty BuildConfig defaults; the release build type sets them to "")
# 3. tag and publish
git tag -a v0.1.2 -m 'LiveType 0.1.2' && git push origin v0.1.2
gh release create v0.1.2 \
  app/build/outputs/apk/release/app-release.apk#LiveType-0.1.2.apk
```

## Security model

`DEVICE_SECRET` is a shared secret baked into your phone's settings. It keeps
strangers off your token endpoint, but anyone who can read the device's app
data — or an APK you configured and handed over — can extract it. That is an
acceptable trade for a personal sideloaded keyboard when combined with:

- a dedicated OpenAI project with a low monthly budget and alerts;
- the 60-second lifetime on ephemeral client secrets;
- Cloudflare rate limiting on the Worker route;
- rotating `DEVICE_SECRET` (redeploy + re-enter in the app) if a phone is lost.

Publishing the APK is fine, and is what the Releases page does: the release
build ships no endpoint and no secret, so each user points it at a Worker they
deployed themselves under their own OpenAI account. What must not be handed
around is a *configured* build or phone, because that carries your
`DEVICE_SECRET`.

What this model does not support is a **hosted** LiveType — one Worker and one
OpenAI key serving users who did not deploy it. That needs real user
authentication or device attestation in place of the shared secret, which is
also why this is not on Google Play as-is.

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
