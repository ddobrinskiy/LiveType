# QA checklist

Every feature and how far it has actually been verified. Kept honest on purpose:
"compiles" is not "works", and a feature only reaches the Confirmed column when
the user says so.

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
  this column is a re-derivation, not a copy. `yes` on a row does **not** mean the
  code is deployed — see the Deployment section for that.

Legend: `yes` · `no` · `partial` · `—` (not applicable)

Last updated: 2026-08-04.

---

## Keyboard UI

| # | Feature | Me | You | Confirmed | In main |
|---|---|---|---|---|---|
| 1 | Connection indicators moved left, above the status line | device | | | yes |
| 2 | Red + `!` badge when a connection is down, green when up, spinner while connecting | device | | | yes (fixed: `connectRealtime` now sets the OpenAI indicator to `LOADING`, and `IDLE` no longer borrows the red `!`) |
| 3 | Tap an indicator → popup with its state | device — regressed twice (touch target, then Toast z-order), now a PopupWindow | yes | **yes** | yes |
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
| 19 | Worker is the authority on model choice; device sends hints only | partial — 85 tests + hostile bodies against a live worker | — | **verified by tooling** | yes |
| 20 | Billing backend: `POST /usage`, `GET /usage`, D1 ledger | partial — 85 tests against real D1 in workerd, plus the local `wrangler dev` database serving the live meter | yes | **yes** — totals grow across phrases | yes; the deployed worker binds to the remote database, which is on schema `0001` (see row 46) |
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
| 45 | Endpoint mode persists the moment it is picked, not on Save: `AppSettings.saveEndpointSelection` writes `endpoint_mode`, `token_endpoint` and the new `custom_endpoint` in one `edit()`, so the stored mode and URL cannot disagree; the spinner's layout-time initial callback is suppressed by `restoringEndpointMode` **and** a same-mode check, so opening the screen writes nothing | build only — from `clean`: `assembleDebug` and `assembleRelease` with `compileDebugKotlin` / `compileReleaseKotlin` executing, `lintDebug` and `lintVitalRelease` green (two pre-existing deprecation warnings only); both `PROD_TOKEN_ENDPOINT` states exercised at build level (absent `.dev.vars` → `""`, present → the URL, release `""` either way). **Nothing seen on a phone:** the reverting-to-prod symptom was never reproduced, the immediate write was never observed surviving a leave-and-return, and the greyed prod row / locked field / release-has-no-spinner cases were re-read in code rather than looked at | yes | **yes** | yes |

---

| 49 | Settings fields opted out of autofill, so a password manager no longer offers to save the device secret | device — masking now via PasswordTransformationMethod, not a password input type | yes | **yes** | yes |

## Per-device metering (added 2026-08-04)

| # | Feature | Me | You | Confirmed | In main |
|---|---|---|---|---|---|
| 50 | Per-device secrets: `DEVICE_SECRETS` maps id → secret, the matching id lands in `usage_events.device_id`, `DEVICE_SECRET` still works as `default` | **partial — verified against the deployed worker.** `0002` applied `--remote`; the 32 rows of prior history all read `device_id = "default"`. A `POST /usage` with mom's secret and a planted `"device_id":"default"` in the body stored `"mom"` — identity comes from the secret, as designed. That probe row was deleted afterwards; the ledger is back to 32 rows / $0.202017. Plus 30 worker tests | | | yes |
| 51 | `GET /usage` scoped per device, plus an owner-only `devices` breakdown that keeps revoked devices' history | **partial — verified against the deployed worker.** Mom's secret returns `device_id: "mom"`, `is_owner: false`, its own zeroed windows and no `devices` key; the owner's returns both devices with mom's cap visible. The revoked-device case is covered by tests only. Never rendered on a phone | | | yes |
| 52 | Daily spend cap enforced at `POST /token`: `402` with figures, fails closed on an unreadable ledger, uncapped devices skip the query | **partial — the 402 confirmed on the deployed worker** by setting mom's cap to `0` (`POST /token` → 402, `GET /usage` → `cap.usd: 0`), then restoring `{"mom":1}` and confirming 200 again. Fail-closed and uncapped-skips-the-query are covered by tests only. Not triggered from a phone | | | yes |
| 53 | The keyboard says "daily limit reached" in the user's language rather than showing an HTTP line (`SpendCapReachedException`) | build only — `assembleDebug`/`assembleRelease`/`lintVitalRelease` green, strings present in `()` and `(ru)`. The message has never been provoked on a device | | | yes |
| 54 | Billing screen shows which device the worker recognised, its limit, and the per-device table | build only — builds green, and the JSON it renders is confirmed live (rows 50–52). Nothing rendered on a phone; see `OPEN_QUESTIONS.md` B10, in particular whether the Russian cap line wraps badly | | | yes |

## Deployment

| # | Feature | Me | You | Confirmed | In main |
|---|---|---|---|---|---|
| 46 | Worker deployed to Cloudflare, bound to the remote `livetype-usage` D1 | partial — live and current: the deployed build includes rows 50–54, `0002` is applied `--remote`, and `wrangler secret list` shows `OPENAI_API_KEY`, `DEVICE_SECRET`, `DEVICE_SECRETS`, `OWNER_DEVICE_ID`, `DEVICE_CAPS`. `POST /token` returns a client secret for the owner; a random secret gets 401 | yes | **yes** | yes |
| 47 | Dictation with **no local worker**: `adb reverse` removed, cable out, over mobile data | no — **untestable by an assistant**: it is defined by the cable being out, which only the person holding the phone can establish | | | yes |
| 48 | Live audio through the **deployed** worker (mint `ek_…`, stream speech, usage landing in the remote D1) | no — an assistant cannot dictate. The same path against the local `wrangler dev` worker is what rows 19–21 cover | | | yes |

Note that deploying alone does not prove row 47: with the `adb reverse` tunnel
still up, a phone pointed at localhost keeps working and looks fine.

**The endpoint is public and has no rate limiting.** The only guard is a bearer
device secret, one of which has leaked in a screen recording. See
`ARCHITECTURE.md` §5.1.

## Bugs found, not yet fixed

Defects discovered during this session that are still live on `main`. Each
needs a fix and then a re-test — none of these has been verified as working.

| # | Bug | How it was found | Fixed? | Re-tested? |
|---|---|---|---|---|
| B1 | ~~**The OpenAI indicator never shows the spinner.**~~ **Fixed and confirmed on device.** It goes `IDLE → OK`, skipping `LOADING`, and `IDLE` renders as the red `!` that everywhere else means *failed*. So the entire OpenAI connect — the slow leg — looks like an error. The token-server indicator does spin, which is why this passed a glance. One line in `connectRealtime()`. The unread `INDICATOR_IDLE` constant is the same bug's other half. | code audit against `main`; `git log -S` shows the `LOADING` call was never written, not lost in a merge | **yes** | no |
| B2 | ~~**`wrangler.jsonc` still has `"database_id": "REPLACE_WITH_ID_FROM_WRANGLER_D1_CREATE"`.**~~ **Fixed:** the file carries `850f00b2-d22f-49ff-bcdb-f0eca6f087da` (region WEUR), and the deployed worker binds to it. | code audit | **yes** | **yes** |
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
3. **Long-silence behaviour is unknown.** When the mic is taken mid-dictation,
   OpenAI may end the turn on its own regardless of what the UI shows. Untested.
4. **Per-device metering has not carried real dictation** (rows 50–54). It is
   deployed and its HTTP behaviour is confirmed live, but no phone has yet
   dictated through mom's secret, and no billing screen has rendered the new
   fields. See `OPEN_QUESTIONS.md` B10.
5. **The endpoint has no rate limiting.** Accepted deliberately (R8): the OpenAI
   project carries a hard spend cap, so the blast radius of a leaked device
   secret is bounded by that cap. Per-device caps (R19) narrow it further for
   capped devices, but the owner's own device is uncapped by design. Worth
   revisiting if the project budget is ever raised.
