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
| 2 | Red + `!` badge when a connection is down, green when up, spinner while connecting | device | | | partial (the spinner only ever runs on the **token-server** indicator; nothing sets the OpenAI one to `LOADING`, so while the socket opens it shows the red `!` and then jumps straight to green) |
| 3 | Tap an indicator → popup with its state | device | yes | **yes** | yes |
| 4 | Recognised text no longer mirrored in the keyboard | device | | | yes |
| 5 | Background `#E0EAEC`, dark strip removed | device | | | yes |
| 6 | System `⌄` / globe glyphs forced dark | device | | | yes |
| 7 | 2x2 thumb grid: keyboard / mic, backspace / Enter | device | | | yes (grid is now 3+2 — Paste joined the top row and the squares shrank 88dp → 72dp; keyboard / mic still sit directly above backspace / Enter) |
| 8 | Icon-only buttons (no text labels) | device | | | yes (thumb grid only; Cancel and Settings are still text buttons, autosized by `fitLabel()`) |
| 9 | Status line only says "Ready" once the socket is really open | device | | | yes |

## Dictation behaviour

| # | Feature | Me | You | Confirmed | In main |
|---|---|---|---|---|---|
| 10 | Connection prewarms when the keyboard opens | device | | | yes |
| 12 | Enter inserts a newline, never sends the message | partial | yes | **yes** | yes |
| 13 | Enter stays active *during* dictation without duplicating text | build only | yes | **yes** | yes |
| 14 | Backspace: hold to repeat, accelerating to whole words | build only | yes | **yes** | yes |
| 15 | Auto-return to previous keyboard disabled via feature flag | build only | yes | **yes** | yes |

## Reliability / cost

| # | Feature | Me | You | Confirmed | In main |
|---|---|---|---|---|---|
| 16 | Silenced-mic detection (screen recorder, calls) | build only | yes | yes | yes |
| 17 | Silenced-mic shown red + warning icon, auto-recovers | build only | | | yes |
| 18 | Prewarm debounce + 8s grace + 5min ceiling | device — measured 6 open/close cycles → **0** extra token requests, 6 reuses | | | yes |
| 19 | Fix: mic tap mid-connect + focus loss no longer records with the keyboard hidden | build only | | | yes |
| 31 | Session survives a completed phrase: back to `READY`, indicators stay green, ceiling re-armed per phrase | device | yes | **yes** | yes |

## Backend

| # | Feature | Me | You | Confirmed | In main |
|---|---|---|---|---|---|
| 20 | Worker is the authority on model choice; device sends hints only | partial — 55 tests + hostile bodies against a live worker | — | | yes |
| 21 | Billing backend: `POST /usage`, `GET /usage`, D1 ledger | partial — 55 tests against real D1 in workerd; **D1 not provisioned, endpoints currently 500** | | | partial (routes, migration and 55 passing tests are all on `main`, but `worker/wrangler.jsonc` still carries `"database_id": "REPLACE_WITH_ID_FROM_WRANGLER_D1_CREATE"` — only the local `wrangler dev` D1 exists, so a deployed worker would still 500) |
| 22 | Prices frozen per row, integer micro-USD, local-day windows | partial — unit tested incl. midnight boundaries at ±180 / −300 | — | | yes |

## Build / release

| # | Feature | Me | You | Confirmed | In main |
|---|---|---|---|---|---|
| 23 | Localisation ru/en with English fallback | device — UI came up English on an English device | | | yes (every translatable string used from Kotlin exists in both `values` and `values-ru`; the six that are English-only are all `translatable="false"`) |
| 24 | Debug build bakes in endpoint + `DEVICE_SECRET` | device — confirmed on a clean install (see #32) | | | yes |
| 25 | Release build contains no secret and no dev endpoint | partial — grepped both APKs, debug as positive control | — | | yes (re-grepped `classes.dex` of a fresh `assembleRelease`: 0 hits for the secret and for `127.0.0.1:8787`, 1 each in debug) |
| 26 | Release cleartext HTTP forbidden; debug allows loopback only | partial — aapt2 on both APKs | — | | yes (release APK carries `base-config cleartextTrafficPermitted=false` and no `domain-config`; debug adds localhost / 127.0.0.1 / 10.0.2.2) |
| 27 | `lintVitalRelease` passes, release APK builds | partial — `assembleRelease` succeeds | — | | yes (re-ran `assembleDebug assembleRelease lintVitalRelease` — BUILD SUCCESSFUL, two deprecation warnings only) |
| 28 | CI workflow (worker tests + Android build) | no — never run on a real runner | — | | yes (file tracked at `.github/workflows/ci.yml`, both jobs present — still never executed, see gap 4) |
| 31 | Debug build bakes in `data/keywords.txt`; release gets `""` | partial — generated `BuildConfig` for both types, plus a probe term grepped in both APKs (debug 1, release 0). **Never seen on a phone** — same clean-install gap as #24 | | | yes |
| 32 | Missing `data/keywords.txt` does not break the build | partial — file moved away, `assembleDebug` green, `DEFAULT_KEYWORDS = ""` | — | | yes (`providers.fileContents(...).asText.orNull ?. … .orEmpty()` — the null-safe chain is intact; not re-tested by moving the user's file) |
| 33 | `age` round-trip of the keyword list | partial — encrypted, then decrypted with the real identity, `diff` clean | — | | yes (re-decrypted `data/keywords.txt.age` at HEAD with the real identity: byte-identical to `data/keywords.txt`) |

## In progress

| # | Feature | Me | You | Confirmed | In main |
|---|---|---|---|---|---|
| 29 | Billing UI in settings | device | yes | **yes** — live figures once D1 was provisioned | yes (`seconds`, `sessions` and `price.unit` are parsed from the worker but not rendered; money is) |
| 30 | Paste button for the last phrase, 5-minute expiry | build only | yes | **yes** | yes |
| 31 | Custom dictionary baked into debug builds (45 terms) | device — verified on a clean install, 45 terms one per line | | | yes (decoded `BuildConfig.DEFAULT_KEYWORDS` from the debug build: 45 terms, exactly matching `data/keywords.txt` after comment/blank/duplicate filtering) |
| 32 | Debug build self-configures endpoint + secret | device — confirmed after `pm clear` | | | yes |
| 33 | `data/keywords.txt.age`, age round-trip | partial — encrypt/decrypt verified byte-for-byte; plaintext confirmed untrackable | — | | yes |
| 34 | Endpoint dropdown in debug: prod (disabled) / dev / custom | build only — release absence proven in the dex; the greyed prod row and the locked field not seen on device | yes | **yes** | yes |
| 35 | Money rounded to three decimals, tiny amounts as `<$0.001` | partial — checked across realistic amounts in en_US and ru_RU | | | yes |

---

## Deployment (not done yet)

| # | Feature | Me | You | Confirmed | In main |
|---|---|---|---|---|---|
| 36 | Worker deployed to Cloudflare (`wrangler deploy` + remote D1) | no — never deployed; `wrangler.jsonc` still has the placeholder `database_id` | | | no |
| 37 | Dictation works with **no local worker**: `adb reverse` removed, cable out, endpoint pointing at `https://….workers.dev/token`, over mobile data | no | | | no |

Row 37 is the one that actually proves it. Deploying alone is not enough — with
the tunnel still up the phone can keep hitting localhost and look fine.

## Bugs found, not yet fixed

Defects discovered during this session that are still live on `main`. Each
needs a fix and then a re-test — none of these has been verified as working.

| # | Bug | How it was found | Fixed? | Re-tested? |
|---|---|---|---|---|
| B1 | **The OpenAI indicator never shows the spinner.** It goes `IDLE → OK`, skipping `LOADING`, and `IDLE` renders as the red `!` that everywhere else means *failed*. So the entire OpenAI connect — the slow leg — looks like an error. The token-server indicator does spin, which is why this passed a glance. One line in `connectRealtime()`. The unread `INDICATOR_IDLE` constant is the same bug's other half. | code audit against `main`; `git log -S` shows the `LOADING` call was never written, not lost in a merge | no | no |
| B2 | **`wrangler.jsonc` still has `"database_id": "REPLACE_WITH_ID_FROM_WRANGLER_D1_CREATE"`.** Harmless locally — `wrangler dev` fabricates its own D1 and ignores the field, which is why billing works on the laptop — but a deploy would bind the worker to nothing. Blocks rows 36/37. | code audit | no | no |
| B3 | **A reused session can outlive OpenAI's own server-side session limit.** Now that one socket serves many phrases, it may hit the server's maximum and close, surfacing as a red "connection closed" where the old per-phrase reconnect quietly hid it. Not observed yet; it is a new failure surface created by multi-phrase reuse. | reasoning by the agent that implemented reuse | no | no |
| B4 | **Behaviour during a long silenced-microphone gap is unknown.** We now report the silencing honestly, but OpenAI may end the turn on its own during the silence, and nothing in the UI would reveal that. | reasoning; never reproduced | no | no |
| B5 | **The dictation prompt reverted to English.** `pm clear` reset it to the English default because the device locale is English; it had been the Russian wording. Same instruction semantically, but not the user's setting. `adb shell input text` cannot type Cyrillic, so restoring it needs a few taps by hand. | observed after the clean-install test | no | — |
| B6 | **Status text is indented ~24dp relative to the indicators.** The warning-icon slot is reserved permanently so the line cannot jump when the icon appears; the cost is that the text no longer aligns with the icons above it. Cosmetic. | observed on device | no | — |

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
3. **The billing section is visibly broken on the phone** — the user saw it. It
   renders, but every window shows an error because no D1 database exists, so
   `GET /usage` returns 500. Not a UI bug: the error path is working as
   designed. Blocked on question A2 (provision D1). Deliberately not fixed yet.
   *(2026-08-01 audit: this now contradicts #29, where the user confirmed live
   figures once D1 was provisioned. The local `wrangler dev` D1 works; what is
   still missing is a **deployed** one — see gap 8.)*
4. **CI has never executed.** The workflow is written but no runner has run it.
5. **The release APK is unsigned**, so it cannot be attached to a GitHub Release
   as is.
6. **Long-silence behaviour is unknown.** When the mic is taken mid-dictation,
   OpenAI may end the turn on its own regardless of what the UI shows. Untested.
7. **Nothing has been pushed.** The repository is prepared and committed
   locally, never published.
8. **The OpenAI indicator never shows the spinner (#2).** `openSession` sets it
   to `IDLE` and the only other writes are `OK` (on `session.updated`) and
   `ERROR`. Nothing anywhere in the file's history has ever set it to
   `LOADING`, so during the whole OpenAI connect — the slower of the two legs —
   it displays the red `!` badge that everywhere else means *failed*, then
   flips to green. The token-server indicator does spin correctly, which is
   presumably why this went unnoticed on device. One line in
   `connectRealtime()` fixes it.
9. **`wrangler.jsonc` has a placeholder `database_id` (#21).** It still reads
   `REPLACE_WITH_ID_FROM_WRANGLER_D1_CREATE`. `wrangler dev` creates its D1
   locally and ignores the field, which is why the billing UI works on the
   laptop, but `wrangler deploy` would fail or bind nothing. Blocked on the
   same A2 as gap 3.
10. **Dead constant `INDICATOR_IDLE`** in `LiveTypeImeService` — declared,
    never read. `setIndicator` deliberately paints `IDLE` with
    `INDICATOR_ERROR`. Cosmetic, but it is the visible half of gap 8.
