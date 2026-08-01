# Open questions

Anything waiting on a decision, plus concerns worth knowing about. This file
exists because questions buried at the end of a long reply get missed — put them
here instead, and work through the list periodically.

**Convention:** each item has an id, a date raised, and a one-line question in
bold. Resolved items move to the bottom with the decision and its date, they are
not deleted — a decision is worth more than the absence of a question.

Last updated: 2026-08-01.

---

## A. Needs your decision

### A1 — Deploy the Worker to Cloudflare? *(raised 2026-08-01)*
**Do you want the keyboard to work away from the Mac?**

Right now `wrangler dev` runs the Worker locally, so dictation only works while
the phone is tethered over USB with `adb reverse` running. `wrangler deploy`
puts it on a public URL and cuts the cable. Free tier covers this easily — one
request per dictation session against a 100k/day allowance.

Blocks: A2. (Rate limiting was considered and deliberately deferred — see R8.)

### A2 — Provision a remote D1? *(raised 2026-08-01, partly resolved)*
**Should billing history live in the cloud, or is the Mac enough?**

The **local** database is now provisioned and working — `GET /usage` returns
200 and the meter is live. Nothing in your Cloudflare account was touched.

Still open: the **remote** one (`wrangler d1 create livetype-usage`), which
only makes sense together with A1. Note the two databases are unrelated —
deploying will not carry local rows across, so the cloud meter starts at zero
unless you deliberately export and import. See ARCHITECTURE.md §3.8.

### A4 — Release signing *(raised 2026-08-01)*
**Do you want to distribute release APKs, and where does the keystore live?**

`assembleRelease` produces an *unsigned* APK, so it cannot be attached to a
GitHub Release as-is. Needs a signing config fed from secrets, keystore kept out
of the repo.

### A7 — FUTO mic hand-off *(raised 2026-08-01)*
**Wire FUTO's mic button to LiveType?**

Investigated fully. ~5 lines in our own `input_method.xml` (a subtype with
`imeSubtypeMode="voice"` plus `overridesImplicitlyEnabledSubtype="true"`), then
flip FUTO's "Disable built-in voice input". No fork, no licence issue. Confirmed
on-device that the voice slot is free. Not implemented — you asked whether it
was possible, not for it to be done.

### B1 — A quarter of the feature list is still unverified on the device
23 of 44 rows in `QA.md` are user-confirmed and 11 more are closed as
tool-verified; the rest have only been compiled. Parallel agents were forbidden
from touching the phone, so much of the UI went straight into a merge.

### B2 — Long silence may end the turn server-side
When another app takes the microphone mid-dictation we now show it honestly, but
OpenAI's own logic may close the turn during the silent gap regardless. Untested,
and nothing in our UI would currently reveal it.

### B6 — Billing is a spend meter, not an audit
Figures come from usage OpenAI reports to the phone, which the phone forwards.
A session lost to a dead network under-counts. It will not match an invoice to
the cent.

### B7 — A held-open session may now hit OpenAI's own session limit *(raised 2026-08-01)*
Now that a finished phrase keeps the socket (see B4 below), a session used
steadily can live far longer than one that reconnected every phrase. If OpenAI
closes it server-side at its own maximum age, `onClosed` surfaces that as a red
"connection closed" status and the next mic tap reconnects from scratch. That is
honest rather than silent, but it is a failure banner where there used to be
none. Not yet observed; the 5-minute idle ceiling only bounds *unused* sessions.

### B9 — The dictation prompt reverted to English *(raised 2026-08-01)*
The clean-install test reset it to the English default, because the device
locale is English; it had been the Russian wording. Semantically the same
instruction. `adb shell input text` cannot type Cyrillic, so if you want the
Russian one back it needs a few taps by hand.

### B8 — Cancel after the stop square is still billed *(raised 2026-08-01)*
Cancel now abandons the phrase instead of tearing the session down, and while
you are still recording it sends `input_audio_buffer.clear`, so the audio is
never committed and never metered. Press it in the brief FINISHING window
*after* tapping stop, though, and the commit has already gone out: OpenAI bills
that buffer whatever we do next. The text is thrown away, but the usage is still
reported, deliberately — the ledger records what OpenAI charged, not what the
app kept. Say so if you would rather under-report those.

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
| R7 | Should Paste also copy to the system clipboard? | **No.** The last phrase lives in app memory for five minutes and is inserted from there. The system clipboard is readable by every app — a materially weaker privacy posture, and inconsistent with "LiveType keeps no dictation history". | 2026-08-01 |
