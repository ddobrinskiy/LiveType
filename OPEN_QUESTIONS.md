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

Blocks: A2, and effectively A5.

### A2 — Provision a remote D1? *(raised 2026-08-01, partly resolved)*
**Should billing history live in the cloud, or is the Mac enough?**

The **local** database is now provisioned and working — `GET /usage` returns
200 and the meter is live. Nothing in your Cloudflare account was touched.

Still open: the **remote** one (`wrangler d1 create livetype-usage`), which
only makes sense together with A1. Note the two databases are unrelated —
deploying will not carry local rows across, so the cloud meter starts at zero
unless you deliberately export and import. See ARCHITECTURE.md §3.8.

### A3 — Publish the repository? *(raised 2026-08-01)*
**Push to GitHub, public?**

Prepared and committed locally: README, MIT licence, CI, `.gitignore`, secret
scan clean. Never pushed — publishing is yours to trigger.

### A4 — Release signing *(raised 2026-08-01)*
**Do you want to distribute release APKs, and where does the keystore live?**

`assembleRelease` produces an *unsigned* APK, so it cannot be attached to a
GitHub Release as-is. Needs a signing config fed from secrets, keystore kept out
of the repo.

### A5 — Rate limiting before deploy *(raised 2026-08-01, no answer yet)*
**Add rate limiting to the Worker before it goes public?**

I raised this and it got lost. Once deployed, the endpoint is a public URL
protected by one static `DEVICE_SECRET`. Without a limiter there is no ceiling
on damage if that secret leaks. I can add it; it should land before A1.

### A7 — FUTO mic hand-off *(raised 2026-08-01)*
**Wire FUTO's mic button to LiveType?**

Investigated fully. ~5 lines in our own `input_method.xml` (a subtype with
`imeSubtypeMode="voice"` plus `overridesImplicitlyEnabledSubtype="true"`), then
flip FUTO's "Disable built-in voice input". No fork, no licence issue. Confirmed
on-device that the voice slot is free. Not implemented — you asked whether it
was possible, not for it to be done.

### A8 — Test a clean install? *(raised 2026-08-01)*
**May I wipe app data to verify the debug build self-configures?**

The baked endpoint/secret are `SharedPreferences` *defaults*, so they only apply
when nothing is saved — and your phone has saved settings, so that path has
never actually run. Verifying needs
`adb shell pm clear dev.dobrinskiy.livetype`, which also wipes your prompt and
keywords. Your call.

### A9 — APKs in the repo *(raised 2026-08-01)*
**Was removing the committed APK the right call?**

I added `*.apk` to `.gitignore` and deleted the stale
`releases/LiveType-debug.apk` (built 30 Jul, before every fix in this session).
Reversible until A3 happens.

---

## B. Concerns — no decision needed, but you should know

### B1 — Most of this session is unverified on the device
9 of 30 features in `QA.md` have actually been seen working. Parallel agents
were forbidden from touching the phone, so much of the UI went straight into a
merge. The red silenced-mic alert in particular has never been looked at.

### B2 — Long silence may end the turn server-side
When another app takes the microphone mid-dictation we now show it honestly, but
OpenAI's own logic may close the turn during the silent gap regardless. Untested,
and nothing in our UI would currently reveal it.

### B3 — CI has never run
`.github/workflows/ci.yml` is written but no runner has executed it. It will
first run — and possibly first fail — on push (A3).

### B4 — Prewarm does not resume after a completed dictation
By design: re-warming immediately would overwrite the "Done" feedback. So the
first tap after finishing a phrase reconnects rather than reusing. Tell me if
that feels slow in practice.

### B5 — Status text is indented relative to the indicators
The warning-icon slot is reserved permanently so the line cannot jump when the
icon appears. Cosmetic side effect: the status text starts ~24dp right of the
indicators. Easy to align if it bothers you.

### B6 — Billing is a spend meter, not an audit
Figures come from usage OpenAI reports to the phone, which the phone forwards.
A session lost to a dead network under-counts. It will not match an invoice to
the cent.

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
| R7 | Should Paste also copy to the system clipboard? | **No.** The last phrase lives in app memory for five minutes and is inserted from there. The system clipboard is readable by every app — a materially weaker privacy posture, and inconsistent with "LiveType keeps no dictation history". | 2026-08-01 |
