# Open questions

Anything waiting on a decision, plus concerns worth knowing about. This file
exists because questions buried at the end of a long reply get missed — put them
here instead, and work through the list periodically.

**Convention:** each item has an id, a date raised, and a one-line question in
bold. Resolved items move to the bottom with the decision and its date, they are
not deleted — a decision is worth more than the absence of a question.

Last updated: 2026-08-04.

---

## A. Needs your decision

### A1 — Your phone is the device `default`, not `david` *(raised 2026-08-04)*
**Cosmetic, and it costs something to change.** Per-device metering is live and
your phone authenticates with the existing `DEVICE_SECRET`, which makes its
device id `default` — that is also the id on all 32 rows of earlier history, and
it is the id `OWNER_DEVICE_ID` names, so you see the full breakdown.

Renaming it to `david` means putting your current secret value into
`DEVICE_SECRETS` and deleting `DEVICE_SECRET`, and those are two separate
`wrangler secret put` calls: in between, the worker sees the same secret under two
ids and returns `500` on every route until the second call lands — roughly a
minute, given secrets take ~30 s to propagate. The alternative is a fresh secret
for `david`, which means re-typing it on your phone. Neither is worth an outage
for a label, so it is left as is. Say the word if you want it.

### B1 — A third of the feature list is unverified on the device
24 of 54 rows in `QA.md` are user-confirmed and 12 more are closed as
tool-verified; the rest have only been compiled. Much of the UI reached `main`
without anyone looking at it on a phone.

### B2 — Long silence may end the turn server-side
When another app takes the microphone mid-dictation we now show it honestly, but
OpenAI's own logic may close the turn during the silent gap regardless. Untested,
and nothing in our UI would currently reveal it.

### B6 — Billing is a spend meter, not an audit, and the cap inherits that
Figures come from usage OpenAI reports to the phone, which the phone forwards.
A session lost to a dead network under-counts. It will not match an invoice to
the cent.

The daily cap (R19) is enforced against this same meter, so a device whose usage
reports are being lost — dead network, force-stopped app — under-counts against
its own cap and can spend past it. The cap bounds *reported* spend; the OpenAI
project budget remains the only hard ceiling.

### B10 — The per-device billing UI has never been seen on a phone *(raised 2026-08-04)*
The new device line, cap line and per-device breakdown compile and their worker
side is covered by tests, but nothing has rendered on a device. Worth a look at
whether the breakdown reads sensibly once it has two real devices in it, and
whether the cap line wraps badly in Russian.

### B8 — The indicator tap now uses a popup, not a `Toast` *(raised 2026-08-01)*
**You can have the `Toast` back, but it will not be visible.** Tapping an
indicator has failed twice. The first diagnosis (part of the 30dp target sat
outside its parent, where Android draws but does not dispatch) was correct and
is fixed; it was not the whole story. The rest is that a `Toast` is a
`TYPE_TOAST` window, which AOSP layers at 7, while an IME is layered at 13 — the
keyboard is drawn *over* the toast — and a text toast sits 48dp above the bottom
of the screen, roughly 300dp inside a keyboard that is now ~364dp tall.
`Toast.setGravity` cannot move it: it is a documented no-op for text toasts at
`targetSdk` 30+, and we target 35.

So the tap now puts up a small label in the keyboard's own window (a
`PopupWindow` anchored under the glyph, 2s, untouchable). The `Toast` call is
still there as the fallback when the popup cannot be shown. If you would rather
have only the toast, say so — it is one line — but note that the toast was last
*seen* working when the keyboard was ~216dp tall, before the two commits that
each grew it by a centimetre, and nothing about it has worked since. Worth a
look on the device: if you see **two** overlapping messages, the layering above
does not hold on your Android build and the popup should go instead.

### B7 — A held-open session may now hit OpenAI's own session limit *(raised 2026-08-01)*
Now that a finished phrase keeps the socket (see B4 below), a session used
steadily can live far longer than one that reconnected every phrase. If OpenAI
closes it server-side at its own maximum age, `onClosed` surfaces that as a red
"connection closed" status and the next mic tap reconnects from scratch. That is
honest rather than silent, but it is a failure banner where there used to be
none. Not yet observed; the 5-minute idle ceiling only bounds *unused* sessions.

---

## Resolved

| # | Question | Decision | Date |
|---|---|---|---|
| R1 | Switch to `gpt-transcribe`, ~4x cheaper? | **No.** Measured: it does not stream — first delta only after commit, so text appears only when you press finish. Real-time feedback is the product. | 2026-08-01 |
| R2 | Is the secret inside the debug APK acceptable? | **Yes.** Personal phone, adb install, APK never distributed. `*.apk` gitignored; rotate `DEVICE_SECRET` if one escapes. | 2026-08-01 |
| R3 | Where does billing logic live? | **Entirely in the Worker.** The phone renders numbers, never computes them. | 2026-08-01 |
| R4 | Use the OpenAI Costs API for real spend? | **No.** Needs an admin key (403 `Missing scopes`), UTC-day buckets only, no per-model grouping, and ~119 endpoints of blast radius. Device-reported usage is exact for the default model and real-time. | 2026-08-01 |
| R5 | Can we get the microphone back from a screen recorder? | **No** — Android policy, not a bug. Handle it honestly instead. | 2026-08-01 |
| R6 | Dark theme for keyboard contrast? | **No.** Background `#E0EAEC`; system glyphs made dark via `APPEARANCE_LIGHT_NAVIGATION_BARS`. | 2026-08-01 |
| B4 | Should prewarm resume after a completed dictation? | **The question was wrong.** Nothing needs re-warming: one transcription session handles many phrases — verified on the live API, three phrases through one socket, each with its own `item_id` and `usage`. `completeSession` now keeps the socket and returns to `READY` with the indicators green; the "Done" feedback survives because nothing reconnects over it. The 5-minute ceiling is re-armed per phrase so it measures idleness, not session age. | 2026-08-01 |
| R8 | Add rate limiting before deploying the Worker? | **Not for now.** The endpoint is a public URL guarded by one static `DEVICE_SECRET`, and that secret has already leaked once (in a screen recording) — but the OpenAI account carries a hard spend cap, so the worst case is bounded by that cap rather than open-ended. That makes a limiter a cost-control nicety rather than a prerequisite. Still worth adding if the worker is ever shared or the cap raised; rotating the secret remains the first response to a leak. | 2026-08-01 |
| R9 | Publish the repository? | **Done.** Public at github.com/ddobrinskiy/LiveType under the personal account `ddobrinskiy` (not the work account `ddobrinskiy-top`). Full-history secret scan clean before pushing; CI went green on the first run. | 2026-08-01 |
| R10 | Test a clean install? | **Done.** `pm clear` run with the settings backed up first. Confirmed the debug build self-configures: endpoint, device secret and the 45-term dictionary all appeared unaided. Cost: the prompt reverted to the English default (see B9). | 2026-08-01 |
| R11 | Were the committed APKs right to remove? | **Yes, settled by publishing.** `*.apk` stays gitignored; releases go through GitHub Releases, which also keeps the debug APK — which carries the device secret — out of a public repo. | 2026-08-01 |
| R12 | Would CI work? | **Yes.** First run on the initial push: both jobs green in 2m11s. | 2026-08-01 |
| R13 | Provision a remote D1 for billing history? | **Yes, done — both halves.** The local database was already live; the remote one now exists too: `livetype-usage`, id `850f00b2-d22f-49ff-bcdb-f0eca6f087da`, region WEUR, in the `cf@dobrinskiy.me` account. `0001_usage_events.sql` applied with `--remote`, and a `--remote` `sqlite_master` query confirms the table and its index. `worker/wrangler.jsonc` carries the real id, so bug B2 is closed. Two caveats: the remote database **starts empty** — your local spend history does not travel and would need a deliberate export/import (ARCHITECTURE.md §3.8) — and the binding has not yet served a real request, because the Worker itself is not deployed (A1). | 2026-08-01 |
| R13 | Release signing — do you want to distribute APKs? | **Not for now.** Installing over adb is enough; no keystore, no signing config. Revisit only if the app is ever handed to someone else. | 2026-08-01 |
| R14 | Wire FUTO's mic button to LiveType? | **No.** Fully investigated and cheap (~5 lines plus a FUTO toggle, no fork, voice slot confirmed free), but not wanted. The findings stay in ARCHITECTURE §5 if that changes. | 2026-08-01 |
| R15 | Should a Cancel pressed after the stop square be billed? | **Yes, keep reporting it.** The commit has already reached OpenAI and is charged whatever the app does next, and the user wants the ledger to show real spend rather than what the app chose to keep. | 2026-08-01 |
| R16 | Restore the Russian dictation prompt? | **No.** The English default says the same thing; not worth the manual typing. | 2026-08-01 |
| R17 | OpenAI returns `403 unsupported_country` from Russia — work around it? | **No, accept it.** A VPN on the phone fixes both affected legs at no cost in code. Smart Placement would only fix the token call, leaving the WebSocket — which leaves the phone directly — still blocked, i.e. a confusingly half-working state. Proxying audio through the Worker would work but reverses the project's central architectural decision for the sake of a geography workaround. See ARCHITECTURE §3.8.1. | 2026-08-01 |
| R18 | How should a second person (your mother) use your Worker with spend tracked per device? | **One secret per device, and the Worker decides which device you are.** `DEVICE_SECRETS` maps device id → secret; the id whose secret matched is what lands in `usage_events.device_id`, so identity is never self-reported — the same rule as model choice. `DEVICE_SECRET` still works as the id `default`, which is also what migration `0002` backfills onto existing rows, so your phone and the dev loop were untouched. Rejected: one shared secret plus a device-supplied id (unrevocable, self-reported), and HMAC-derived credentials (buys nothing at two devices, and a leaked master mints any identity). Each device sees only its own spend; `OWNER_DEVICE_ID` also sees a per-device breakdown that keeps a revoked device's history. See ARCHITECTURE §3.14. | 2026-08-04 |
| R19 | Enforce a hard per-device spend limit, or only report? | **Enforce, at `/token`, as a daily allowance.** `DEVICE_CAPS` maps device id → USD **per day** (mom: $1); a device that has spent today's allowance gets `402` and the keyboard says so in its own language. `/token` is the only place spending can be *prevented* rather than recorded — no token, no session, no charge. Accepted cost: one D1 read on the latency path, paid **only by capped devices**, so the owner's uncapped phone is unaffected. Fails closed if the ledger cannot be read. The day boundary is `CAP_TZ_OFFSET_MINUTES` on the server, deliberately **not** the offset the phone sends: a device that chose its own boundary could shift the window and grant itself a fresh allowance. Not a rate limiter — see ARCHITECTURE §5.1, and B6 for what a lost usage report does to a cap. | 2026-08-04 |
| R20 | Deploy the Worker to Cloudflare? | **Yes, done.** Live in the `cf@dobrinskiy.me` account with `OPENAI_API_KEY` and `DEVICE_SECRET` in its secret store, bound to the remote `livetype-usage` D1. The URL is kept out of this public repo — it lives in `worker/.dev.vars` as `PROD_TOKEN_ENDPOINT`, which debug builds bake into `EndpointMode.PROD`. Two account-level gates had to be cleared first and will bite anyone repeating this on a fresh account: a verified account email (else `10034` on every Worker write) and a registered `workers.dev` subdomain (else `10007` on deploy). | 2026-08-04 |
| R21 | Deploy per-device secrets and the daily cap? | **Done 2026-08-04, with no interruption.** Order that avoided any outage: migrate the remote D1 first (the old code's `INSERT` omits `device_id`, so the column default covers it), then deploy the new code (the legacy `DEVICE_SECRET` keeps working as `default`), then add `DEVICE_SECRETS`, `OWNER_DEVICE_ID` and `DEVICE_CAPS` one at a time — every intermediate state is a valid configuration. Verified live: 32 rows of history backfilled to `default`; mom's phone sees only its own spend and its $1/day cap; the owner sees both devices; `POST /usage` from mom stamped `device_id: "mom"` while ignoring a `"device_id":"default"` planted in the body; `402` confirmed by temporarily setting mom's cap to `0`. | 2026-08-04 |
| R7 | Should Paste also copy to the system clipboard? | **No.** The last phrase lives in app memory for five minutes and is inserted from there. The system clipboard is readable by every app — a materially weaker privacy posture, and inconsistent with "LiveType keeps no dictation history". | 2026-08-01 |
