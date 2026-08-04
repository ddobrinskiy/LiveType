# IOS_BUILD.md — Handover for the LiveType iOS port

## 1. Purpose & scope

This document is the handover for porting **LiveType** — a voice-input keyboard
whose transcription runs on the OpenAI Realtime API, with model choice, token
minting and billing owned by a self-hosted Cloudflare Worker — from Android to
iOS. The Android app (`android/`) and the Worker (`worker/`) are complete and
in production use; the iOS port lives in this worktree's `ios/` tree. The port
now has a complete settings surface, shared App Group configuration, a
keepalive-enabled dictation loop, and a headless simulator harness. Device and
simulator validation are recorded separately from compile-only claims below.
`AGENTS.md` at the worktree root is the operational guide and the worker
contract; this file maps the Android code to the iOS code, states what works
and what does not, and lists what a fresh agent should do next.

---

## 2. Repository layout

| Path | What it is |
|---|---|
| `android/` | The complete Kotlin IME (no Compose, one dependency OkHttp). Source of truth for behaviour. |
| `worker/` | The Cloudflare Worker that mints ephemeral client secrets, owns model choice and prices usage into D1. The wire contract both apps talk to. |
| `data/` | `keywords.txt` (gitignored personal vocabulary) + encrypted `keywords.txt.age`. Baked into the Android debug build only; no iOS equivalent yet. |
| `scripts/` | age encrypt/decrypt wrappers for the keyword list. |
| `ios/LiveType/` | Host app target: `LiveTypeApp.swift` (SwiftUI `@main`) + `SettingsView.swift`. |
| `ios/LiveTypeKeyboard/` | Keyboard extension target: `KeyboardViewController.swift` + its `Info.plist`. |
| `ios/Shared/` | Nine Swift files compiled into **both** targets: `BuildSettings`, `EndpointMode`, `RecordingLimit`, `Settings`, `MoneyFormat`, `TokenProvider`, `UsageReporter`, `RealtimeTranscriber`, `PcmAudioRecorder`. |
| `ios/LiveType.xcodeproj` | Xcode project, two targets, object version 56 (Xcode 14 format). |
| `ios/build/` | xcodebuild output. Covered by the existing `**/build/` gitignore entry. |

The `ios/` tree is part of the `ios_version` branch and is committed together
with the port and its validation harness.

---

## 3. iOS project structure

- **Two targets**, both `CreatedOnToolsVersion = 15.0`, deployment target
  **iOS 16.0** (project-level `IPHONEOS_DEPLOYMENT_TARGET = 16.0`, inherited by
  both targets).
  - **LiveType** (`com.apple.product-type.application`) — bundle id
    `dev.dobrinskiy.livetype`, `MARKETING_VERSION 0.1.0`. Sources:
    `LiveTypeApp.swift`, `SettingsView.swift`, and the nine Shared files.
  - **LiveTypeKeyboard** (`com.apple.product-type.app-extension`) — bundle id
    `dev.dobrinskiy.livetype.keyboard`. Sources: `KeyboardViewController.swift`
    and the same nine Shared files (a second `PBXSourcesBuildPhase` — the
    Shared files are **listed in both phases**, there is no shared framework).
- **Extension embedding**: the LiveType app target depends on
  LiveTypeKeyboard (`PBXTargetDependency` + container proxy) and embeds it via
  an **"Embed Foundation Extensions"** copy phase
  (`PBXCopyFilesBuildPhase`, `dstSubfolderSpec = 13` → `PlugIns`). Building
  the app builds and embeds the extension.
- **Code signing**: `CODE_SIGN_STYLE = Automatic`, `DEVELOPMENT_TEAM = ""` for
  both targets, both configurations. Nothing is signed today (hence builds use
  `CODE_SIGNING_ALLOWED=NO`).
- **No asset catalog**: there is no `Assets.xcassets`, no app icon
  (`ASSETCATALOG_COMPILER_APPICON_NAME` is not set anywhere), and no
  localization files (no `.lproj`, no `Localizable.strings`).
- The extension `Info.plist` declares `NSExtensionPointIdentifier =
  com.apple.keyboard-service`, principal class
  `$(PRODUCT_MODULE_NAME).KeyboardViewController`, and
  **`RequestsOpenAccess = true`**. Both Info.plists carry
  `NSMicrophoneUsageDescription`.
- The keyboard extension's `KeyboardViewController` subclasses
  `UIInputViewController` (the `UIInputViewController` equivalent of Android's
  `InputMethodService`).

---

## 4. Porting map

| Android file | iOS file | Status |
|---|---|---|
| `network/RealtimeTranscriber.kt` | `Shared/RealtimeTranscriber.swift` | `1:1 port` |
| `network/TokenProvider.kt` | `Shared/TokenProvider.swift` | `1:1 port` |
| `network/UsageReporter.kt` | `Shared/UsageReporter.swift` | `1:1 port` |
| `audio/PcmAudioRecorder.kt` | `Shared/PcmAudioRecorder.swift` | `structural port` |
| `config/AppSettings.kt` | `Shared/Settings.swift` | `structural port` |
| `config/EndpointMode.kt` | `Shared/EndpointMode.swift` | `1:1 port` |
| `config/RecordingLimit.kt` | `Shared/RecordingLimit.swift` | `1:1 port` |
| `config/FeatureFlags.kt` | — (no file) | `not started` |
| `MoneyFormat.kt` | `Shared/MoneyFormat.swift` | `1:1 port` |
| `ime/LiveTypeImeService.kt` | `LiveTypeKeyboard/KeyboardViewController.swift` | `structural port` |
| `MainActivity.kt` | `LiveType/SettingsView.swift` | `skeleton` |

*(iOS-only companion: `Shared/BuildSettings.swift` — the iOS twin of the
Gradle `buildConfigField` injection in `android/app/build.gradle.kts`.)*

### What each `structural`/`skeleton` row means in plain words

**`PcmAudioRecorder.swift` — structural port (complete, one API gap).** The
capture pipeline is entirely different but the contract is the same: 24 kHz
mono 16-bit little-endian PCM delivered in ~100 ms chunks. Android uses
`AudioRecord` at 24 kHz directly; iOS installs an `AVAudioEngine` tap at the
hardware rate (usually 48 kHz) and downsamples with `AVAudioConverter` to the
target `AVAudioFormat` (int16, 24 kHz, mono). It works and compiles. Two
deliberate, unimplemented gaps vs. Android:

- **`onSilencedChanged` has no counterpart.** Android detects a
  higher-priority client stealing the mic (`isClientSilenced`) and surfaces it
  as a recoverable warning. On iOS the tap just stops delivering; the doc
  comment says to detect it via session interruption/deactivation callbacks.
  Nothing does yet.
- `start()` does not check the mic permission itself (Android throws if
  `RECORD_AUDIO` is not granted); the caller is expected to have handled
  full-access/permission, and `AVAudioEngine` will fail on its own if it cannot
  open the input.

**`Settings.swift` — structural port (complete).**
`AppSettings` over `UserDefaults` is the twin of `SharedPreferences`: same key
names, same `load()`/`save()`/`saveEndpointSelection()` semantics, same
"defaults only, saved values win" rule, same `isConfigured`,
`usageEndpoint`-derivation and `isAllowedTokenEndpoint` (with the `http://`
branch gated `#if DEBUG`). The structural difference is that the keyboard
extension runs in **its own sandboxed container**, so it uses a `UserDefaults`
**suite** `group.dev.dobrinskiy.livetype`. Both targets now declare the matching
App Group entitlement; a signed device build still requires the owner's
Development Team and provisioning profile. See §5.4.

**`EndpointMode.swift` and `BuildSettings.swift` — 1:1 ports of the enum +
build-injection logic.** Same `prod`/`dev`/`custom` semantics, `isAvailable`,
`isSelectable` (debug only), `from()`, `defaultMode` (dev in debug, custom in
release). The build-time injection moves from Gradle reading `worker/.dev.vars`
to `#if DEBUG` constants in `BuildSettings.swift`. Note the consequences,
covered in §7: `prodTokenEndpoint` is always `""` (the deployed worker still
does not exist), `defaultDeviceSecret` is `""` unless the env var
`LIVETYPE_DEVICE_SECRET` is set, and `defaultKeywords` is `""` always.

**`KeyboardViewController.swift` — structural port, dictation loop wired.**
The class is a `UIInputViewController` with a `RealtimeTranscriber`, a
`PcmAudioRecorder` and a mic toggle. As of 2026-08-04 the session lifecycle is
wired end to end: the recorder's chunks feed `transcriber.appendAudio`, the
`TokenProvider` fetch result is passed to `transcriber.connect(secret:)`
(instead of being discarded), `onTranscriptDelta`/`onTranscriptCompleted`
insert text into `textDocumentProxy`, `onTranscriptCompleted` reports the
verbatim `usage` object via `UsageReporter`, and the max-recording-limit
auto-stop commits and finishes the phrase. It gates on `hasFullAccess` and
`settings.isConfigured`, and has a cancel/error path (`failSession`). iOS uses
commit-only insertion because it has no composing-text API, and the WebSocket
now has a 20-second keepalive. Still not ported vs. Android: prewarm/grace/idle
ceilings and password-field refusal.

**`SettingsView.swift` — complete debug/release settings surface.** The SwiftUI
`Form` has endpoint mode selection, endpoint and secret validation, languages,
prompt, keywords, recording limit, microphone state, keyboard-settings guidance,
and worker-backed billing windows with estimated/source/as-of labels. The
previous duplicate device-secret field (plain + secure, same binding) is fixed.

**`FeatureFlags.kt` — not started.** No Swift equivalent exists.
`RETURN_TO_PREVIOUS_KEYBOARD` has no obvious iOS meaning yet — iOS keyboards
have no API to programmatically switch back to the previous keyboard (the globe
key is the system's), so this flag is likely a no-op on iOS. Decide whether to
port it as documentation-only or drop it.

---

## 5. iOS-specific constraints (no Android equivalent)

### 5.1 Full Access is a hard requirement for networking and the mic

A custom keyboard runs in its own process/container and is heavily sandboxed.
Unless the user grants **Full Access** (`RequestOpenAccess = true` is already in
the extension `Info.plist`, which is what makes the "Allow Full Access" toggle
appear), the extension has **no network** and **no microphone** — the WebSocket
to OpenAI, the token fetch and `AVAudioEngine` all fail. `KeyboardViewController`
already checks `hasFullAccess` and shows "Enable Full Access in Settings". This
is a user-visible setup step with no Android equivalent (Android's IME just
needs the two manifest permissions).

### 5.2 No composing text: `insertText` behaves like paste

Android writes deltas into a **composing region** that streams visibly and is
then committed; Enter and the final commit can act on it with cursor ranges.
iOS has no composing text: everything goes through
`textDocumentProxy.insertText(_:)`, which lands in the focused field like a
**paste** — you cannot render "uncommitted" text, you cannot partially replace
it, and the cursor jumps to the end of the inserted run. This is the single
biggest functional regression vs. Android and is stated as such in
`KeyboardViewController`'s doc comment. Practical consequences for the port:

- The streamed-delta UX ("text appears and is revised as you talk") cannot be
  reproduced faithfully. The realistic options are to insert only on
  completion, or to insert deltas and accept that the user sees churn.
- The Android mid-dictation Enter / Paste / word-delete logic that freezes a
  composing region (`committedChars`) has no equivalent — the iOS keyboard
  would just `insertText("\n")` etc.
- `textDocumentProxy` has no reliable analogue of `InputConnection`; cursor and
  selection access depend on the host app's cooperation.

### 5.3 Microphone: permission strings present, capture is engine-based

`NSMicrophoneUsageDescription` exists in **both** Info.plists (host app and
extension — the extension can request the mic itself). Capture is the
`AVAudioEngine` tap + downsampler described in §4; there is no Android
`AudioRecord`-style silenced-mic signal.

### 5.4 The App Group is configured; signing is still account-owned

`Settings.swift` reads `UserDefaults(suiteName: "group.dev.dobrinskiy.livetype")`
(falling back to `.standard` if the suite fails). Both targets now include the
matching `com.apple.security.application-groups` entitlement. The project
still has an empty `DEVELOPMENT_TEAM`, so unsigned simulator builds validate
the code and layout, while a physical-device build needs the account owner's
team and a provisioning profile before the entitlement can be signed.

#### Free Personal Team versus a paid Developer Program membership

Apple allows personal on-device testing from Xcode with an ordinary Apple
Account (Xcode calls this a **Personal Team**), without enrolling in the paid
Apple Developer Program. It is a temporary development path: Apple limits the
account to 10 App IDs and 3 devices, and the provisioning profile expires
after 7 days, so the app must be rebuilt and reinstalled periodically. It is
not TestFlight, App Store, or ad-hoc distribution.

That distinction matters for this port. The host app and keyboard extension
share all runtime configuration through the provisioned App Group: Worker
endpoint, device secret, languages, prompt, keywords, recording limit and
endpoint mode. Apple requires a `group.` identifier to be present in the
provisioning profile. The current full LiveType configuration therefore needs
a team/profile that can provision App Groups; the supported path is a paid
Apple Developer Program team (or an existing team that already provides the
capability). The free Personal Team is not the supported full-device setup for
this project.

If App Groups are removed to make a free-account experiment build, the
keyboard becomes a separate sandbox. The host app's saved settings no longer
reach it: in debug it may appear to work only because both targets have the
same baked defaults; edited settings, production endpoint/secret, custom
keywords and usage reporting from the keyboard will not follow the host app.
Restoring equivalent behaviour would require a different configuration
architecture, such as a separate settings UI/configuration inside the
extension or build-time-only values. It is not a signing-only change.

The free Personal Team can still be useful for testing a reduced keyboard
sample without the App Group, but it does not validate the shipped LiveType
configuration. Full Access remains a separate requirement for the keyboard's
network and microphone access, regardless of which signing team is used.

### 5.5 No `adb reverse`: the debug endpoint works in the Simulator, not on a phone

Android reaches the laptop's `wrangler dev` through a USB reverse tunnel
(`adb reverse tcp:8787 tcp:8787`, so `http://127.0.0.1:8787/token` on the phone
is really the Mac). iOS has **no USB reverse tunnel**. The debug endpoint
`http://127.0.0.1:8787/token` (baked into `BuildSettings.defaultTokenEndpoint`
in `#if DEBUG`) is reachable from the **Simulator** (it shares the Mac's
network stack) but on a **physical iPhone `127.0.0.1` is the phone itself** —
the endpoint is dead there. This is an **open problem for device testing**;
candidate answers are in §8. Note also the geography constraint from
`AGENTS.md`/`ARCHITECTURE.md §3.8.1`: the phone must egress from an
OpenAI-supported country, and the audio WebSocket leaves the device directly.

### 5.6 ATS / cleartext is debug-gated

The code-level split is done (`isAllowedTokenEndpoint` accepts `http://` only
in `#if DEBUG`, matching Android's debug-only cleartext). Debug builds use
`Info-Debug.plist` with `NSAllowsLocalNetworking=true`; release builds use the
normal plist with no ATS exception. This is a local-networking allowance, not a
blanket ATS disable, and is selected by the Xcode configuration.

### 5.7 The worker owns model choice — never violate it on the wire

The iOS `RealtimeTranscriber` is a faithful port and must stay that way
(AGENTS.md "Model Notes"):

- Connect to **bare `wss://api.openai.com/v1/realtime`** — never pass
  `?model=` on the URL (transcription sessions get their model from the
  ephemeral token; passing one is rejected outright).
- `session.update` **must not contain a `transcription` block** — OpenAI makes
  `model` mandatory whenever that block is present, and a device-sent model
  would override the token's. The update only re-asserts the audio format and
  produces `session.updated`, the ready signal. The ported `sessionUpdate()`
  already omits it correctly.
- The `POST /token` body is **hints only** (`languages`, `prompt`, `keywords`);
  never send a `model` field.

### 5.8 The device renders, it never prices

Billing follows AGENTS.md "Billing and usage tracking": OpenAI puts the billable
quantity on the transcription-completed event
(`usage: {"type":"duration","seconds":N}` or the token shape); the phone
forwards that object **verbatim** in `POST /usage` (iOS `UsageReporter.report`
does exactly this) and renders `usd_micros` from `GET /usage` — never converting
seconds to dollars or holding a price table. The ported `MoneyFormat.usd(_:)`
takes integer micro-USD and formats it. `tz_offset_minutes` for `GET /usage`
comes from `TimeZone.current.secondsFromGMT() / 60`, matching Android.

---

## 6. Build & run

### Verified build

```bash
cd ios
xcodebuild -project LiveType.xcodeproj -target LiveType \
  -configuration Debug -sdk iphoneos CODE_SIGNING_ALLOWED=NO build
```

**Verified working** on 2026-08-04: `** BUILD SUCCEEDED **`, building both the
app and the embedded keyboard extension against the `iphoneos` SDK
(iPhoneOS 26.4, Xcode 17E192).

### Environment caveats

- Xcode 26.4 is installed with the **iOS 26.4 (23E244)** Simulator runtime.
  The headless runner creates/reuses a named iPhone simulator and does not open
  the Simulator window.
- `CODE_SIGNING_ALLOWED=NO` is used for the local simulator/device builds
  because `DEVELOPMENT_TEAM` is empty. A signed device build still needs the
  account owner's team and provisioning profile.

### Headless end-to-end validation

From the repository root:

```bash
./scripts/simulators-e2e.sh
```

The script starts a local protocol-shaped HTTP/WebSocket double, builds and
installs the debug Android APK into the named Android AVD
(`Medium_Phone_API_36.1`, explicit `adb -s emulator-5554`), drives the real
IME into a focused editor, verifies the fake transcript is inserted and that
the usage POST arrives, then builds/installs the iOS app plus keyboard
extension into an iOS Simulator and verifies token/usage HTTP calls and the
host-app QA screen. Android runs with `-no-window -no-audio`; iOS is driven via
`simctl`, so neither platform opens a UI window. Screenshots, UI hierarchy
snapshots, build logs, and the fake-server event log are written to
`artifacts/e2e/`.

The fake server is intentionally local and deterministic: it validates the
same token, WebSocket, transcript, and usage shapes without requiring an
OpenAI key or a deployed Worker. The runner forces the Android E2E variant to
repackage (`--rerun-tasks`) so a configuration-time endpoint change cannot
silently reuse an older APK. Android uses a cold boot (`-no-snapshot-load`),
waits for Package Manager before install, and keeps ADB operations timeout
protected so a broken Play Store snapshot cannot hang the run indefinitely.
Before the first mic tap it waits for the protocol double's `session.updated`
handshake and settles the freshly rendered IME view; the automatic stop waits
briefly after the recording UI appears and retries once only if
`ws_commit`/usage do not arrive. Generated screenshots/XML are cleared at run
start so a failed run cannot leave stale evidence behind. The final pass
proved `ws_commit`, usage, transcript insertion, and the visible Ready →
recording → Done states.

### Live progress viewer

The runner also maintains a small local progress dashboard. It writes the
current operation plus append-only high-level `cards` atomically to
`artifacts/e2e/progress.json`. Each card has a `platform` (`ios`, `android`, or
`shared`), a `kind` (`success` or `attempt`), a result, details, timestamp, and
run id. The HTML is a static renderer; it polls the JSON once per second, so it
never needs to be edited or manually refreshed.

The page puts iOS and Android history in separate columns, with newest cards at
the top. Shared infrastructure cards remain in a full-width common section.
The current, shared, iOS, and Android sections are native collapsible panels;
their open/closed state is remembered in the browser's local storage.
Starting a run with `--section reset` starts a new run but deliberately keeps
all cards from previous runs. This is what makes the history survive runner
restarts. `--clear-history` is an explicit destructive opt-in for intentionally
starting with an empty board.

`simulators-e2e.sh` starts or reuses the local server at
`http://127.0.0.1:8790/`. Open it in Chrome once:

```bash
open -a 'Google Chrome' http://127.0.0.1:8790/
```

If that URL is already open, keep using the existing tab; do not run the
`open` command again. The runner never launches Chrome, and the page stays
available after the E2E runner exits while showing the final card state. If the
runner is not being used, start the viewer directly from the
repository root:

```bash
node scripts/progress-update.mjs --file artifacts/e2e/progress.json \
  --section reset --run-id manual
node scripts/e2e-progress-server.mjs --port 8790 \
  --progress artifacts/e2e/progress.json \
  --html "$PWD/scripts/e2e-progress.html"
```

This viewer is local-only (`127.0.0.1`), requires no credentials, and does not
launch an emulator or a browser window by itself. The E2E runner addresses only
the Android emulator serial `emulator-5554`; it does not touch a connected
physical device.

### Open in Xcode

```bash
open ios/LiveType.xcodeproj
```

### Install on a device later

1. Use a team that can provision App Groups, then set its **Development Team**
   in both targets' Signing & Capabilities. Register/enable
   `group.dev.dobrinskiy.livetype` for both bundle IDs; this is what makes the
   shared settings suite usable on a physical iPhone. A free Personal Team is
   suitable only for a reduced build without this entitlement (see §5.4).
2. Run the `LiveType` app on a physical iPhone (the app embeds the keyboard).
3. **Settings → General → Keyboard → Keyboards → Add New Keyboard → LiveType**.
4. Tap the LiveType keyboard row → toggle **Allow Full Access** on (required for
   network + mic).
5. Grant the microphone when prompted.
6. Configure the endpoint/secret in the host app; the App Group entitlement is
   present, so the extension uses the shared settings suite once the signed
   profile permits it.

---

## 7. Current implementation status

Honest read of the tree as of 2026-08-04:

**Latest live run status:** `simulators-20260804T144247Z` passed both headless
platform flows after merge commit `8f5ca5d`. Android passed the real IME
dictation path through the local
protocol double, including the prewarm handshake, visible recording state,
automatic stop, `ws_commit`, usage POST, and `Hello from LiveType` in the
focused editor. iOS built and installed the host app plus keyboard extension,
then its QA host passed token + usage calls; visual QA showed green PASS
checkmarks for every row and a Ready keyboard preview. Earlier failed attempts
remain visible as append-only cards in the dashboard rather than being erased.
The authoritative live view is the existing Chrome dashboard tab; raw evidence
is under `artifacts/e2e/`.

- **Compiles successfully**: the whole Shared layer and both targets build with
  `** BUILD SUCCEEDED **` against both `iphoneos` and `iphonesimulator` using
  Xcode 26.4. The log contains only the AppIntents metadata informational
  messages plus Xcode's existing orientation warning.
- **Complete and faithful**: `TokenProvider`, `UsageReporter`, `RealtimeTranscriber`
  (wire protocol, event handling, `session.update`), `MoneyFormat`,
  `EndpointMode`, `RecordingLimit`, and `Settings`' load/save. These are
  drop-in logic and need no porting work.
- **`KeyboardViewController`'s dictation loop is wired** (2026-08-04): recorder
  chunks → `transcriber.appendAudio`; token fetch → `transcriber.connect`;
  deltas/transcripts → commit-only `textDocumentProxy.insertText`; completed →
  verbatim usage report; max-recording-limit auto-stop commits and finishes.
  The iOS socket has a 20-second keepalive. Remaining parity gaps are
  prewarm/grace/idle ceilings and password-field refusal.
- **`SettingsView` is complete for the current port**: endpoint-mode dropdown,
  endpoint/secret/language validation, keywords, recording limit, permission and
  keyboard-settings guidance, plus price/estimated/source/as-of usage windows.
- **Debug configuration is injected by the build**: debug plist/build settings
  carry the local endpoint, E2E overrides, and secret when supplied; release
  carries empty credentials and the production bare Realtime URL. Both targets
  use the App Group suite for shared settings.
- **Headless runtime validation is automated and passed for the current scope**:
  the Android side drives the real IME against the local HTTP/WebSocket double;
  the iOS side installs the host app with its embedded keyboard extension and
  drives the QA host screen against the same double. The definitive pass and
  screenshots are recorded in `QA.md` and `artifacts/e2e/`. iOS keyboard
  activation through the Settings UI remains outside the public `simctl` API.
- **Worker contract tests are green**: after the merge, `cd worker && npm test`
  passed all 89 Miniflare-backed tests. A post-merge generic `iphoneos`
  compile-only build also passed with `CODE_SIGNING_ALLOWED=NO`; it did not
  install or contact the connected iPhone. The simulator runner still uses its
  deterministic local protocol double so the client E2E remains offline from
  OpenAI and does not need a deployed Worker.

---

## 8. Next steps / TODO for the next agent

Ordered roughly by dependency:

1. **Repeat the headless validation after future changes** with
   `./scripts/simulators-e2e.sh`; keep the latest run id and screenshot evidence
   in `QA.md` and leave prior cards in the progress history.
2. **Add iOS keyboard activation to the simulator harness if practical.** iOS
   `simctl` has no public command for enabling third-party keyboards, so the
   current passing iOS check exercises the installed host app and embedded
   extension binary plus the shared token/usage paths. If activation can be
   driven via the Settings UI without a visible window, extend the runner and
   document it.
3. **Decide and execute the signed real-device testing story** — no `adb reverse`
   exists for iOS (§5.5). The full LiveType configuration needs a team that can
   provision App Groups; a free Personal Team is only a reduced-build option
   and expires after 7 days. For networking, options are a Mac LAN endpoint
   with the debug plist, the deployed Worker (blocked on the Cloudflare account
   gates in `OPEN_QUESTIONS.md` A1), or a bundled tunnel helper. Record any
   account-owner decision in `OPEN_QUESTIONS.md`; do not touch a connected
   physical Android device for this validation workflow.
4. **Add remaining optional Android parity**: prewarm/grace/idle ceilings and
   password-field refusal if the iOS proxy exposes the necessary metadata.
5. **Asset catalog / app icon / localization** remain product polish: no
   `Assets.xcassets` exists, and RU/EN `Localizable.strings` has not been added.
6. **Decide what to do with `FeatureFlags`** — `RETURN_TO_PREVIOUS_KEYBOARD`
   has no iOS mechanism (§4); either document it as a no-op or drop the flag.
7. **Re-run both `iphoneos` and `iphonesimulator` builds after future changes**
   and keep compile warnings separate from simulator QA evidence.

---

## 9. References

- **`AGENTS.md`** (worktree root) — the operational guide: local worker setup
  (`npx wrangler dev`, D1 migrations), the Model Notes (§"Model Notes") that
  govern the WebSocket URL and `session.update`, and the Billing section that
  governs `POST/GET /usage` and the "phone renders, never prices" rule. The iOS
  code must obey the same wire contract the Android app does.
- **`ARCHITECTURE.md`** — why the design is what it is (direct phone→OpenAI
  audio, worker-owned model choice, billing backend, prewarm/ceilings). The
  session-lifecycle sections (§3.4, §3.7, §3.12, §3.13) describe behaviour the
  iOS keyboard should aim to match where the platform allows.
- **`QA.md` / `OPEN_QUESTIONS.md`** — what is actually verified on Android, and
  the open questions (notably A1: the Worker is still not deployed).
- **Branch and worktree**: work happens on branch **`ios_version`** in the
  worktree `.worktrees/ios_version/`; the iOS port and headless validation
  harness are committed on that branch.
- Note: `Settings.swift` and `KeyboardViewController.swift` both reference a
  `PORTING.md` that **does not exist**. This `IOS_BUILD.md` is the handover for
  now; either rename/point those references here or create `PORTING.md` as the
  running port log.
