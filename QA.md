# QA checklist

Everything built in this session, and how far each thing has actually been
verified. Kept honest on purpose: "compiles" is not "works", and a feature only
reaches the last column when the user says so.

**Columns**

- **Me** — verified by the assistant. `device` = observed running on the phone;
  `partial` = verified indirectly (tests, logs, APK inspection) but not seen
  working end to end; `build only` = compiles and merges, nothing more.
- **You** — exercised by the user on the phone.
- **Confirmed** — user explicitly said it works.
- **In main** — whether the code is on the `main` branch *right now*, found by
  reading the working tree rather than by trusting the columns to its left.
  `yes` means implemented **and wired up**: the declaration was found, and so
  was a live call site. The session merged many worktree branches by hand, so
  this column is a re-derivation, not a copy. Audited 2026-08-01 against
  `4d3143d`.

Legend: `yes` · `no` · `partial` · `—` (not applicable)

Last updated: 2026-08-01.

---

## Keyboard UI

| # | Feature | Me | You | Confirmed | In main |
|---|---|---|---|---|---|
| 1 | Connection indicators moved left, above the status line | device | | | yes |
| 2 | Red + `!` badge when a connection is down, green when up, spinner while connecting | device | | | yes (fixed: `connectRealtime` now sets the OpenAI indicator to `LOADING`, and `IDLE` no longer borrows the red `!`) |
| 3 | Tap an indicator → popup with its state | device | yes | **yes** | yes |
| 4 | Recognised text no longer mirrored in the keyboard | device | yes | **yes** | yes |
| 5 | Background `#E0EAEC`, dark strip removed | device | yes | **yes** | yes |
| 6 | System `⌄` / globe glyphs forced dark | device | yes | **yes** | yes |
| 7 | Icon-only buttons (no text labels) | device | yes | **yes** | yes (thumb grid only; Cancel and Settings are still text buttons, autosized by `fitLabel()`) |
| 8 | Status line only says "Ready" once the socket is really open | device | yes | **yes** | yes |

## Dictation behaviour

| # | Feature | Me | You | Confirmed | In main |
|---|---|---|---|---|---|
| 9 | Connection prewarms when the keyboard opens | device | yes | **yes** | yes |
| 10 | Enter inserts a newline, never sends the message | partial | yes | **yes** | yes |
| 11 | Enter stays active *during* dictation without duplicating text | build only | yes | **yes** | yes |
| 12 | Backspace: hold to repeat, accelerating to whole words | build only | yes | **yes** | yes |
| 13 | Auto-return to previous keyboard disabled via feature flag | build only | yes | **yes** | yes |

## Reliability / cost

| # | Feature | Me | You | Confirmed | In main |
|---|---|---|---|---|---|
| 14 | Silenced-mic detection (screen recorder, calls) | build only | yes | yes | yes |
| 15 | Silenced-mic shown red + warning icon, auto-recovers | build only | yes | **yes** | yes |
| 16 | Prewarm debounce + 8s grace + 5min ceiling | device — measured 6 open/close cycles → **0** extra token requests, 6 reuses | yes | **yes** — stays alive across field changes, settings, typing; closes on IME switch, which is by design | yes |
| 17 | Session survives a completed phrase: back to `READY`, indicators stay green, ceiling re-armed per phrase | device | yes | **yes** | yes |
| 18 | Cancel abandons the phrase without closing the session: `input_audio_buffer.clear`, composing text and partial transcript dropped, socket kept, back to `READY` with both indicators green | build only — `assembleDebug` (with `compileDebugKotlin` executing) and `lintDebug` clean; never tapped on a phone, and the late-transcript guards (abandoned `item_id`, `abandonedCompletions`) have not been observed firing against the live API | | | yes |

## Backend

| # | Feature | Me | You | Confirmed | In main |
|---|---|---|---|---|---|
| 19 | Worker is the authority on model choice; device sends hints only | partial — 55 tests + hostile bodies against a live worker | — | **verified by tooling** | yes |
| 20 | Billing backend: `POST /usage`, `GET /usage`, D1 ledger | partial — 55 tests against real D1 in workerd, plus the local `wrangler dev` database serving the live meter | yes | **yes** — totals grow across phrases | yes for the code and the local database; the **remote** one now exists and is migrated (row 43), but no deployed worker has bound to it yet |
| 21 | Prices frozen per row, integer micro-USD, local-day windows | partial — unit tested incl. midnight boundaries at ±180 / −300 | — | **verified by tooling** | yes |

## Build / release

| # | Feature | Me | You | Confirmed | In main |
|---|---|---|---|---|---|
| 22 | Localisation ru/en with English fallback | device — UI came up English on an English device | deferred — needs the system language switched to Russian | | yes (every translatable string used from Kotlin exists in both `values` and `values-ru`; the six that are English-only are all `translatable="false"`) |
| 23 | Debug build bakes in endpoint + `DEVICE_SECRET` | device — confirmed on a clean install (see #32) | | | yes |
| 24 | Release build contains no secret and no dev endpoint | partial — grepped both APKs, debug as positive control | — | **verified by tooling** | yes (re-grepped `classes.dex` of a fresh `assembleRelease`: 0 hits for the secret and for `127.0.0.1:8787`, 1 each in debug) |
| 25 | Release cleartext HTTP forbidden; debug allows loopback only | partial — aapt2 on both APKs | — | **verified by tooling** | yes (release APK carries `base-config cleartextTrafficPermitted=false` and no `domain-config`; debug adds localhost / 127.0.0.1 / 10.0.2.2) |
| 26 | `lintVitalRelease` passes, release APK builds | partial — `assembleRelease` succeeds | — | **verified by tooling** | yes (re-ran `assembleDebug assembleRelease lintVitalRelease` — BUILD SUCCESSFUL, two deprecation warnings only) |
| 27 | CI workflow (worker tests + Android build) | device — first run on the initial push: both jobs green in 2m11s | — | **verified by tooling** | yes (file tracked at `.github/workflows/ci.yml`, both jobs present — still never executed, see gap 4) |
| 28 | Debug build bakes in `data/keywords.txt`; release gets `""` | partial — generated `BuildConfig` for both types, plus a probe term grepped in both APKs (debug 1, release 0). **Never seen on a phone** — same clean-install gap as #24 | — | **verified by tooling** | yes |
| 29 | Missing `data/keywords.txt` does not break the build | partial — file moved away, `assembleDebug` green, `DEFAULT_KEYWORDS = ""` | — | **verified by tooling** | yes (`providers.fileContents(...).asText.orNull ?. … .orEmpty()` — the null-safe chain is intact; not re-tested by moving the user's file) |
| 30 | `age` round-trip of the keyword list | partial — encrypted, then decrypted with the real identity, `diff` clean | — | **verified by tooling** | yes (re-decrypted `data/keywords.txt.age` at HEAD with the real identity: byte-identical to `data/keywords.txt`) |

## In progress

| # | Feature | Me | You | Confirmed | In main |
|---|---|---|---|---|---|
| 31 | Billing UI in settings | device | yes | **yes** — live figures once D1 was provisioned | yes (`seconds`, `sessions` and `price.unit` are parsed from the worker but not rendered; money is) |
| 32 | Paste button for the last phrase, 5-minute expiry | build only | yes | **yes** | yes |
| 33 | Custom dictionary baked into debug builds (45 terms) | device — verified on a clean install, 45 terms one per line | — | **verified by tooling** | yes (decoded `BuildConfig.DEFAULT_KEYWORDS` from the debug build: 45 terms, exactly matching `data/keywords.txt` after comment/blank/duplicate filtering) |
| 34 | Debug build self-configures endpoint + secret | device — confirmed after `pm clear` | — | **verified by tooling** | yes |
| 35 | `data/keywords.txt.age`, age round-trip | partial — encrypt/decrypt verified byte-for-byte; plaintext confirmed untrackable | — | **verified by tooling** | yes |
| 36 | Endpoint dropdown in debug: prod (disabled) / dev / custom | build only — release absence proven in the dex; the greyed prod row and the locked field not seen on device | yes | **yes** | yes |
| 37 | Money rounded to three decimals, tiny amounts as `<$0.001` | partial — checked across realistic amounts in en_US and ru_RU | yes | **yes** | yes |
| 38 | Silenced mic also reddens the record button; shorter `status_mic_in_use` | build only — needs re-verification on device, including that the button returns to its normal tint when the mic comes back | yes | **yes** | yes |
| 39 | Max recording length: 1–20 min dropdown (default 3), auto-stop finishes the phrase like the stop button | build only — `assembleDebug` + `lintDebug` + `lintVitalRelease` clean; never waited out a real ceiling on the phone | yes | **yes** — dropdown and the auto-stop both | yes |
| 40 | Keyboard heights tuned: keys 72×110dp, content 261dp, plus a 63dp thumb-reach lift below (block 324dp) | build only — arithmetic in the `THUMB_BUTTON_HEIGHT_DP` KDoc, `assembleDebug` + `lintDebug` green; not seen on a phone | | | yes |
| 41 | Keyboard another 1cm taller and lifted 1cm off the bottom: content 230dp → 293dp (keys 72×126dp, row gap 13dp) plus a 63dp thumb-reach margin under the whole block, 356dp in total (+55%) | build only — arithmetic in the `THUMB_BUTTON_HEIGHT_DP` KDoc, `assembleDebug` + `lintDebug` green; width untouched, but neither the new height nor the lift has been seen on a phone | | | yes |
| 42 | Recording-limit status shown ⚠️ + red + bold (new `emphasis` flag on `setState`; the ⚠️ is in the string, so the warning ImageView stays `INVISIBLE` and the record button keeps its normal tint) | build only — `assembleDebug` + `lintDebug` green, `aapt2 dump resources` shows the emoji on both the `()` and `(ru)` value; the red/bold/⚠️ and their reset on the next ordinary state have never been seen on a phone | yes | **yes** | yes |
| 43 | Indicators enlarged: 26dp glyphs (was 18) inside 48dp touch boxes (was 30), 38dp spinner ring, 26dp alarm glyph; optical left-edge alignment now derived from `content`'s shortened left padding instead of a negative row margin; column arithmetic 48 + 6 + 95 + 12 + 72 = 233dp | build only — `assembleDebug` (with `compileDebugKotlin` executing) and `lintDebug` clean, no error-severity issues; arithmetic re-derived in the `THUMB_BUTTON_DP` and `THUMB_BUTTON_HEIGHT_DP` KDocs. Nothing rendered: the glyph size, the ring's fit around the 26dp logo, the `!` badge's position at 18sp, and that the status text still holds its longest Russian string in 95dp have all been reasoned, not seen | | | yes |
| 44 | Tapping an indicator shows its status toast | build only — **regressed once and is untracked by row 3**, which was confirmed before the left-column rework put 6dp of a 30dp target outside its parent (drawn, never touched) and left a 24dp live strip with the glyph on its left edge. Now a 48dp box wholly inside its parent. Never re-tapped on a phone | | | yes |
| 45 | Endpoint mode persists the moment it is picked, not on Save: `AppSettings.saveEndpointSelection` writes `endpoint_mode`, `token_endpoint` and the new `custom_endpoint` in one `edit()`, so the stored mode and URL cannot disagree; the spinner's layout-time initial callback is suppressed by `restoringEndpointMode` **and** a same-mode check, so opening the screen writes nothing | build only — from `clean`: `assembleDebug` and `assembleRelease` with `compileDebugKotlin` / `compileReleaseKotlin` executing, `lintDebug` and `lintVitalRelease` green (two pre-existing deprecation warnings only); both `PROD_TOKEN_ENDPOINT` states exercised at build level (absent `.dev.vars` → `""`, present → the URL, release `""` either way). **Nothing seen on a phone:** the reverting-to-prod symptom was never reproduced, the immediate write was never observed surviving a leave-and-return, and the greyed prod row / locked field / release-has-no-spinner cases were re-read in code rather than looked at | | | yes |

---

## Deployment (attempted 2026-08-01 — blocked on the Cloudflare account)

| # | Feature | Me | You | Confirmed | In main |
|---|---|---|---|---|---|
| 46 | Worker deployed to Cloudflare (`wrangler deploy` + remote D1) | **partial — the database yes, the worker no.** Done and verified: `wrangler d1 create livetype-usage` → `850f00b2-d22f-49ff-bcdb-f0eca6f087da`, real id written into `wrangler.jsonc`, `d1 migrations apply --remote` applied `0001_usage_events.sql` (3 commands, ✅), and a `--remote` `sqlite_master` query confirms `usage_events` plus its index exist in WEUR. **Not done:** `wrangler secret put` and `wrangler deploy` both fail — see the two account gates below. `workers/scripts` on the account lists **0** scripts, so nothing is half-deployed | | | partial — the real `database_id` is on `main`; no worker exists |
| 47 | Dictation works with **no local worker**: `adb reverse` removed, cable out, endpoint pointing at `https://….workers.dev/token`, over mobile data | no — and **untestable by an assistant in any case**: it is defined by the cable being out and `adb reverse` being gone, which only the person holding the phone can establish. Blocked upstream by row 43 anyway; there is no `https://` URL to point the app at. `EndpointMode.PROD_ENDPOINT` is therefore still `""` and the prod dropdown row is still correctly disabled | | | no |
| 48 | Live audio through the **deployed** worker (mint `ek_…` from `https://…workers.dev/token`, stream real speech, transcript back, usage landing in remote D1) | no — could not be attempted; there is no deployed URL to mint from. The same test against the **local** `wrangler dev` worker is what rows 19–21 already cover | | | — |

**The two gates, both needing the account owner:**

1. **The Cloudflare account's email address is unverified.** Every write to
   `/accounts/…/workers/scripts/*` returns
   `10034 — You need to verify your email address to use Workers`
   (<https://developers.cloudflare.com/fundamentals/setup/account/verify-email-address/>).
   This is what `wrangler secret put OPENAI_API_KEY` hit. D1 is not gated on it,
   which is why the database half went through.
2. **No `workers.dev` subdomain exists** — `GET /workers/subdomain` returns
   `10007`. `wrangler deploy` stops before uploading anything and offers to
   register one interactively. Deliberately not answered on the owner's behalf:
   the subdomain is a permanent, account-wide hostname.

Row 44 is the one that actually proves it. Deploying alone is not enough — with
the tunnel still up the phone can keep hitting localhost and look fine.

**When row 43 does clear, the endpoint goes public with no rate limiting.** The
only guard is a static `DEVICE_SECRET`, which has already leaked once in a
screen recording. See `OPEN_QUESTIONS.md` A5.

## Bugs found, not yet fixed

Defects discovered during this session that are still live on `main`. Each
needs a fix and then a re-test — none of these has been verified as working.

| # | Bug | How it was found | Fixed? | Re-tested? |
|---|---|---|---|---|
| B1 | **The OpenAI indicator never shows the spinner.** It goes `IDLE → OK`, skipping `LOADING`, and `IDLE` renders as the red `!` that everywhere else means *failed*. So the entire OpenAI connect — the slow leg — looks like an error. The token-server indicator does spin, which is why this passed a glance. One line in `connectRealtime()`. The unread `INDICATOR_IDLE` constant is the same bug's other half. | code audit against `main`; `git log -S` shows the `LOADING` call was never written, not lost in a merge | **yes** | no |
| B2 | ~~**`wrangler.jsonc` still has `"database_id": "REPLACE_WITH_ID_FROM_WRANGLER_D1_CREATE"`.**~~ **Fixed** 2026-08-01: `wrangler d1 create livetype-usage` returned `850f00b2-d22f-49ff-bcdb-f0eca6f087da` (region WEUR) and that id is now in the file. | code audit | **yes** | partial — the id is proven correct against the real database (`d1 migrations apply --remote` and a `sqlite_master` query both hit it and report `served_by_region: WEUR`), but it has **not** been exercised through a deployed worker's `DB` binding, because the deploy is blocked (row 43) |
| B3 | **A reused session can outlive OpenAI's own server-side session limit.** Now that one socket serves many phrases, it may hit the server's maximum and close, surfacing as a red "connection closed" where the old per-phrase reconnect quietly hid it. Not observed yet; it is a new failure surface created by multi-phrase reuse. | reasoning by the agent that implemented reuse | no | no |
| B4 | **Behaviour during a long silenced-microphone gap is unknown.** We now report the silencing honestly, but OpenAI may end the turn on its own during the silence, and nothing in the UI would reveal that. | reasoning; never reproduced | no | no |
| B5 | **The dictation prompt reverted to English.** `pm clear` reset it to the English default because the device locale is English; it had been the Russian wording. Same instruction semantically, but not the user's setting. `adb shell input text` cannot type Cyrillic, so restoring it needs a few taps by hand. | observed after the clean-install test | no | — |
| B6 | ~~**Status text is indented ~24dp relative to the indicators.**~~ **Fixed** by the left-column rework: the warning icon moved to the far end of the indicator row, so the text starts at the shared left edge. | observed on device | no | — |

## Known gaps

Things below are **not** verified and should not be assumed working.

1. **The dictation prompt is now English.** The clean install reset it to the
   English default because the device locale is English; it used to be the
   Russian wording. Semantically the same instruction, but it was not the
   user's earlier setting. `adb shell input text` cannot type Cyrillic, so
   restoring it needs a few taps by hand.
2. **The silenced-mic UI (#17) has never been seen.** The detection itself is
   confirmed working (#16 — the user saw the message), but the red text and
   warning icon landed afterwards and nobody has looked at that state since.
3. **The release APK is unsigned**, so it cannot be attached to a GitHub Release
   as is.
4. **Long-silence behaviour is unknown.** When the mic is taken mid-dictation,
   OpenAI may end the turn on its own regardless of what the UI shows. Untested.
5. **The Worker is still not deployed (#43).** The remote D1 database is real
   and migrated, and `wrangler.jsonc` carries its actual id — but no Worker
   script exists in the Cloudflare account (`workers/scripts` lists 0). Two
   account-level gates stopped it: the account email is unverified (`10034`,
   which blocks every Worker write including `wrangler secret put`) and no
   `workers.dev` subdomain has been registered (`10007`). Both need the account
   owner; see `OPEN_QUESTIONS.md` A1. Until then the phone still needs the
   cable, and **no secret has been pushed to Cloudflare** — the deployed worker
   would have neither `OPENAI_API_KEY` nor `DEVICE_SECRET`.
6. **The deployed endpoint will have no rate limiting.** Accepted deliberately
   (R8) on the grounds that the OpenAI account has a hard spend cap, so the
   blast radius of the leaked `DEVICE_SECRET` is bounded by that cap rather
   than open-ended. Worth revisiting if the cap is ever raised.
