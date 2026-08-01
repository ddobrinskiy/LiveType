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

Legend: `yes` · `no` · `partial` · `—` (not applicable)

Last updated: 2026-08-01.

---

## Keyboard UI

| # | Feature | Me | You | Confirmed |
|---|---|---|---|---|
| 1 | Connection indicators moved left, above the status line | device | | |
| 2 | Red + `!` badge when a connection is down, green when up, spinner while connecting | device | | |
| 3 | Tap an indicator → popup with its state | device | yes | **yes** |
| 4 | Recognised text no longer mirrored in the keyboard | device | | |
| 5 | Background `#E0EAEC`, dark strip removed | device | | |
| 6 | System `⌄` / globe glyphs forced dark | device | | |
| 7 | 2x2 thumb grid: keyboard / mic, backspace / Enter | device | | |
| 8 | Icon-only buttons (no text labels) | device | | |
| 9 | Status line only says "Ready" once the socket is really open | device | | |

## Dictation behaviour

| # | Feature | Me | You | Confirmed |
|---|---|---|---|---|
| 10 | Connection prewarms when the keyboard opens | device | | |
| 11 | Mic tap during `CONNECTING` is not lost | build only | | |
| 12 | Enter inserts a newline, never sends the message | partial | | |
| 13 | Enter stays active *during* dictation without duplicating text | build only | | |
| 14 | Backspace: hold to repeat, accelerating to whole words | build only | | |
| 15 | Auto-return to previous keyboard disabled via feature flag | build only | | |

## Reliability / cost

| # | Feature | Me | You | Confirmed |
|---|---|---|---|---|
| 16 | Silenced-mic detection (screen recorder, calls) | build only | yes | yes |
| 17 | Silenced-mic shown red + warning icon, auto-recovers | build only | | |
| 18 | Prewarm debounce + 8s grace + 5min ceiling | device — measured 6 open/close cycles → **0** extra token requests, 6 reuses | | |
| 19 | Fix: mic tap mid-connect + focus loss no longer records with the keyboard hidden | build only | | |
| 31 | Session survives a completed phrase: back to `READY`, indicators stay green, ceiling re-armed per phrase | device | yes | **yes** |

## Backend

| # | Feature | Me | You | Confirmed |
|---|---|---|---|---|
| 20 | Worker is the authority on model choice; device sends hints only | partial — 55 tests + hostile bodies against a live worker | — | |
| 21 | Billing backend: `POST /usage`, `GET /usage`, D1 ledger | partial — 55 tests against real D1 in workerd; **D1 not provisioned, endpoints currently 500** | | |
| 22 | Prices frozen per row, integer micro-USD, local-day windows | partial — unit tested incl. midnight boundaries at ±180 / −300 | — | |

## Build / release

| # | Feature | Me | You | Confirmed |
|---|---|---|---|---|
| 23 | Localisation ru/en with English fallback | device — UI came up English on an English device | | |
| 24 | Debug build bakes in endpoint + `DEVICE_SECRET` | device — confirmed on a clean install (see #32) | | |
| 25 | Release build contains no secret and no dev endpoint | partial — grepped both APKs, debug as positive control | — | |
| 26 | Release cleartext HTTP forbidden; debug allows loopback only | partial — aapt2 on both APKs | — | |
| 27 | `lintVitalRelease` passes, release APK builds | partial — `assembleRelease` succeeds | — | |
| 28 | CI workflow (worker tests + Android build) | no — never run on a real runner | — | |
| 31 | Debug build bakes in `data/keywords.txt`; release gets `""` | partial — generated `BuildConfig` for both types, plus a probe term grepped in both APKs (debug 1, release 0). **Never seen on a phone** — same clean-install gap as #24 | | |
| 32 | Missing `data/keywords.txt` does not break the build | partial — file moved away, `assembleDebug` green, `DEFAULT_KEYWORDS = ""` | — | |
| 33 | `age` round-trip of the keyword list | partial — encrypted, then decrypted with the real identity, `diff` clean | — | |

## In progress

| # | Feature | Me | You | Confirmed |
|---|---|---|---|---|
| 29 | Billing UI in settings | device | yes | **yes** — live figures once D1 was provisioned |
| 30 | Paste button for the last phrase, 5-minute expiry | build only | | |
| 31 | Custom dictionary baked into debug builds (45 terms) | device — verified on a clean install, 45 terms one per line | | |
| 32 | Debug build self-configures endpoint + secret | device — confirmed after `pm clear` | | |
| 33 | `data/keywords.txt.age`, age round-trip | partial — encrypt/decrypt verified byte-for-byte; plaintext confirmed untrackable | — | |
| 34 | Endpoint dropdown in debug: prod (disabled) / dev / custom | build only — release absence proven in the dex; the greyed prod row and the locked field not seen on device | | |
| 35 | Money rounded to three decimals, tiny amounts as `<$0.001` | partial — checked across realistic amounts in en_US and ru_RU | | |

---

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
4. **CI has never executed.** The workflow is written but no runner has run it.
5. **The release APK is unsigned**, so it cannot be attached to a GitHub Release
   as is.
6. **Long-silence behaviour is unknown.** When the mic is taken mid-dictation,
   OpenAI may end the turn on its own regardless of what the UI shows. Untested.
7. **Nothing has been pushed.** The repository is prepared and committed
   locally, never published.
