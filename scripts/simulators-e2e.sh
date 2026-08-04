#!/usr/bin/env bash
set -euo pipefail

# Headless, local end-to-end smoke test for both clients. The fake server is
# deliberately protocol-shaped like the Worker plus Realtime API: the clients
# still use their real HTTP/WebSocket implementations, but no OpenAI key or
# deployed Cloudflare account is needed.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="${ROOT_DIR}/artifacts/e2e"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_AVD="${LIVETYPE_ANDROID_AVD:-Medium_Phone_API_36.1}"
ANDROID_SERIAL="${LIVETYPE_ANDROID_SERIAL:-emulator-5554}"
E2E_PORT="${LIVETYPE_E2E_PORT:-8788}"
IOS_DEVICE_NAME="${LIVETYPE_IOS_DEVICE_NAME:-LiveType E2E iPhone}"
PROGRESS_PORT="${LIVETYPE_PROGRESS_PORT:-8790}"
PROGRESS_FILE="${ARTIFACT_DIR}/progress.json"
PROGRESS_SERVER_PID=""
RUN_ID="simulators-$(date -u +%Y%m%dT%H%M%SZ)"

mkdir -p "${ARTIFACT_DIR}/android" "${ARTIFACT_DIR}/ios"
# Screenshots and UI dumps are evidence for the current run. Remove only the
# generated files whose stale contents could otherwise be mistaken for a fresh
# pass after an interrupted runner; progress.json remains append-only history.
rm -f \
    "${ARTIFACT_DIR}/android/keyboard-before.png" \
    "${ARTIFACT_DIR}/android/keyboard-recording.png" \
    "${ARTIFACT_DIR}/android/keyboard-stop-5.png" \
    "${ARTIFACT_DIR}/android/keyboard-stop-15.png" \
    "${ARTIFACT_DIR}/android/keyboard-after.png" \
    "${ARTIFACT_DIR}/android/window-before.xml" \
    "${ARTIFACT_DIR}/android/window-after.xml" \
    "${ARTIFACT_DIR}/ios/e2e.png"

progress_cmd() {
    node "${ROOT_DIR}/scripts/progress-update.mjs" --file "${PROGRESS_FILE}" "$@" \
        >/dev/null 2>&1 || true
}

progress_current() {
    progress_cmd --section current --platform "$1" --id "$2" --title "$3" --status "$4" \
        --message "$5" --details "${6:-}"
}

progress_card() {
    progress_cmd --section "$1" --platform "$2" --id "$3" --title "$4" --result "$5" \
        --message "$6" --details "${7:-}"
}

progress_cmd --section reset --run-id "${RUN_ID}"
progress_current "shared" "runner" "Headless iOS + Android E2E" "in_progress" \
    "Starting the local protocol double and simulator workflow" \
    "Progress page: http://127.0.0.1:${PROGRESS_PORT}/"

if ! curl -fsS "http://127.0.0.1:${PROGRESS_PORT}/health" >/dev/null 2>&1; then
    node "${ROOT_DIR}/scripts/e2e-progress-server.mjs" \
        --port "${PROGRESS_PORT}" --progress "${PROGRESS_FILE}" \
        --html "${ROOT_DIR}/scripts/e2e-progress.html" \
        >"${ARTIFACT_DIR}/progress-server.log" 2>&1 &
    PROGRESS_SERVER_PID=$!
fi

SERVER_LOG="${ARTIFACT_DIR}/fake-server.log"
node "${ROOT_DIR}/scripts/e2e-fake-server.mjs" --port "${E2E_PORT}" >"${SERVER_LOG}" 2>&1 &
SERVER_PID=$!
ANDROID_EMULATOR_PID=""
IOS_DEVICE_UDID=""

cleanup() {
    local exit_status=$?
    if [[ "${exit_status}" -ne 0 ]]; then
        progress_card "tried" "shared" "runner-${RUN_ID}" "Headless iOS + Android E2E run" "fail" \
            "Runner stopped before all simulator checks passed" \
            "Exit status ${exit_status}; inspect artifacts/e2e/*.log and the preceding platform cards."
        progress_current "shared" "runner" "Headless iOS + Android E2E" "failed" \
            "Runner stopped before all checks passed" \
            "Exit status ${exit_status}; inspect artifacts/e2e/*.log and the last card."
    fi
    if [[ -n "${IOS_DEVICE_UDID}" ]]; then
        xcrun simctl shutdown "${IOS_DEVICE_UDID}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${ANDROID_EMULATOR_PID}" ]]; then
        adb -s "${ANDROID_SERIAL}" emu kill >/dev/null 2>&1 || true
        kill "${ANDROID_EMULATOR_PID}" >/dev/null 2>&1 || true
    fi
    kill "${SERVER_PID}" >/dev/null 2>&1 || true
    # Keep the progress server alive after the runner exits so the already-open
    # Chrome page continues to show the final state. A later run reuses it.
    return "${exit_status}"
}
trap cleanup EXIT

for _ in $(seq 1 60); do
    if grep -q "E2E_SERVER_READY ${E2E_PORT}" "${SERVER_LOG}"; then break; fi
    sleep 1
done
grep -q "E2E_SERVER_READY ${E2E_PORT}" "${SERVER_LOG}"
progress_card "succeeded" "shared" "protocol-double" "Local protocol double" "pass" \
    "HTTP token/usage and WebSocket endpoints are ready" \
    "The clients use their real network code without an OpenAI key."

run_android() {
    progress_current "android" "android-avd" "Android headless AVD" "in_progress" \
        "Starting or reusing ${ANDROID_AVD}" \
        "Serial is pinned to ${ANDROID_SERIAL}; no physical device is addressed."
    local emulator_bin="${ANDROID_HOME}/emulator/emulator"
    [[ -x "${emulator_bin}" ]] || { echo "Android emulator not found: ${emulator_bin}" >&2; return 1; }

    if ! adb -s "${ANDROID_SERIAL}" get-state >/dev/null 2>&1; then
        "${emulator_bin}" -avd "${ANDROID_AVD}" -port "${ANDROID_SERIAL#emulator-}" \
            -no-window -no-audio -no-boot-anim \
            -gpu swiftshader_indirect -no-snapshot-load \
            >"${ARTIFACT_DIR}/android/emulator.log" 2>&1 &
        ANDROID_EMULATOR_PID=$!
    fi

    adb -s "${ANDROID_SERIAL}" wait-for-device >/dev/null
    local boot_completed=""
    for _ in $(seq 1 120); do
        boot_completed="$(adb -s "${ANDROID_SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '[:space:]')"
        if [[ "${boot_completed}" == "1" ]]; then break; fi
        sleep 1
    done
    [[ "${boot_completed}" == "1" ]]

    # A restored Play Store snapshot can leave system_server showing an ANR
    # dialog even after sys.boot_completed becomes 1. Clear that VM-only
    # dialog and wait until Package Manager answers before attempting install;
    # otherwise `adb install` can block forever on the hung service.
    local package_ready=""
    for _ in $(seq 1 30); do
        if timeout 5 adb -s "${ANDROID_SERIAL}" shell uiautomator dump \
            /sdcard/livetype-window-boot.xml >/dev/null 2>&1 \
            && timeout 5 adb -s "${ANDROID_SERIAL}" exec-out cat \
            /sdcard/livetype-window-boot.xml >"${ARTIFACT_DIR}/android/window-boot.xml" \
            && grep -q "isn't responding" "${ARTIFACT_DIR}/android/window-boot.xml"; then
            adb -s "${ANDROID_SERIAL}" shell input tap 540 1330 >/dev/null 2>&1 || true
        fi
        if timeout 5 adb -s "${ANDROID_SERIAL}" shell cmd package list packages \
            >/dev/null 2>&1; then
            package_ready=1
            break
        fi
        sleep 1
    done
    [[ "${package_ready}" == "1" ]]
    progress_current "android" "android-build" "Android E2E APK" "in_progress" \
        "Building and repackaging the E2E debug variant" \
        "Gradle is forced to run packaging tasks so endpoint fields are fresh."

    (
        cd "${ROOT_DIR}/android"
        # The E2E endpoint/manifest are configuration-time inputs. Gradle's
        # normal up-to-date check does not see a command-line property change
        # in those custom fields, so force this variant to be repackaged rather
        # than risking installation of a prior non-E2E APK.
        ANDROID_HOME="${ANDROID_HOME}" ./gradlew assembleDebug --rerun-tasks \
            -PlivetypeE2e=true -PlivetypeE2ePort="${E2E_PORT}"
    ) >"${ARTIFACT_DIR}/android/build.log" 2>&1
    local apk="${ROOT_DIR}/android/app/build/outputs/apk/debug/app-debug.apk"
    [[ -s "${apk}" ]]
    timeout 45 adb -s "${ANDROID_SERIAL}" install -r "${apk}" \
        >"${ARTIFACT_DIR}/android/install.log"
    adb -s "${ANDROID_SERIAL}" shell pm clear dev.dobrinskiy.livetype >/dev/null
    adb -s "${ANDROID_SERIAL}" shell pm grant dev.dobrinskiy.livetype android.permission.RECORD_AUDIO >/dev/null
    adb -s "${ANDROID_SERIAL}" shell ime enable dev.dobrinskiy.livetype/.ime.LiveTypeImeService >/dev/null
    adb -s "${ANDROID_SERIAL}" shell ime set dev.dobrinskiy.livetype/.ime.LiveTypeImeService >/dev/null
    # This AVD advertises a hardware qwerty device. Ask Android to show a soft
    # IME anyway; without this, the focused editor is visible but the keyboard
    # window is intentionally suppressed.
    adb -s "${ANDROID_SERIAL}" shell settings put secure show_ime_with_hard_keyboard 1
    adb -s "${ANDROID_SERIAL}" shell am start -n dev.dobrinskiy.livetype/.E2eActivity >"${ARTIFACT_DIR}/android/launch.log"
    for _ in $(seq 1 30); do
        if adb -s "${ANDROID_SERIAL}" shell dumpsys window 2>/dev/null \
            | grep -q 'dev.dobrinskiy.livetype/dev.dobrinskiy.livetype.E2eActivity'; then
            break
        fi
        sleep 1
    done
    # Re-assert the selected IME after the debug APK is installed and the host
    # activity is focused. Some Play Store snapshots switch back to LatinIME
    # during package-change handling even after an earlier `ime set`.
    adb -s "${ANDROID_SERIAL}" shell ime set dev.dobrinskiy.livetype/.ime.LiveTypeImeService >/dev/null
    # A tap on the editor also covers emulator images that ignore the initial
    # SHOW_IMPLICIT request while the activity is still becoming interactive.
    adb -s "${ANDROID_SERIAL}" shell input tap 220 300 >/dev/null 2>&1 || true

    # Boot completion only means the system server is up. The keyboard window
    # appears later, after the host activity gets focus and asks IME manager to
    # show it. Wait for the service's own input view rather than searching the
    # accessibility dump: Android exposes the focused app there, but not the
    # separate InputMethod window.
    local ime_ready=""
    for _ in $(seq 1 30); do
        if adb -s "${ANDROID_SERIAL}" shell dumpsys input_method 2>/dev/null \
            | grep -q 'mIsInputViewShown=true'; then
            ime_ready=1
            break
        fi
        # Android 16 can show a transient system-process ANR dialog during
        # first snapshot restore. The dialog's "Wait" action is safe and lets
        # the system service finish; tapping this VM-only coordinate is the
        # same deterministic action a user would take, without opening a
        # window. The title varies between "System UI" and "Process system"
        # across emulator snapshots, so match the stable suffix.
        if adb -s "${ANDROID_SERIAL}" shell uiautomator dump /sdcard/livetype-window.xml >/dev/null 2>&1 \
            && adb -s "${ANDROID_SERIAL}" exec-out cat /sdcard/livetype-window.xml >"${ARTIFACT_DIR}/android/window-before.xml" \
            && grep -q "isn't responding" "${ARTIFACT_DIR}/android/window-before.xml"; then
            adb -s "${ANDROID_SERIAL}" shell input tap 540 1330 >/dev/null 2>&1 || true
        fi
        sleep 1
    done
    [[ "${ime_ready}" == "1" ]]
    # The default AVD is 1080x2400 at 420dpi; the rightmost top-row key is the
    # mic, centred at (948, 1634) in that layout. Keep the coordinate explicit
    # and pair it with the screenshots below so a layout change is visible in QA.
    local tap_x=948
    local tap_y=1634
    progress_current "android" "android-ime" "Android real IME flow" "in_progress" \
        "Keyboard window is visible; waiting for the prewarmed realtime session" \
        "The first mic tap is held until the local server confirms session.updated, so a cold AVD cannot swallow it."

    # The IME prewarms after the input view appears. Waiting for the protocol
    # double's session.update is stronger than sleeping a guessed duration: it
    # proves token fetch, WebSocket upgrade, and the app's ready handshake all
    # completed before the tap that starts recording. On an unusually slow
    # image, one fallback tap asks the app to start as soon as that handshake
    # arrives; in that branch we do not send a second start tap below.
    local session_ready=""
    local recording_started_by_fallback="0"
    for _ in $(seq 1 45); do
        if grep -q '"event":"session_update"' "${SERVER_LOG}"; then
            session_ready=1
            break
        fi
        sleep 1
    done
    if [[ -z "${session_ready}" ]]; then
        adb -s "${ANDROID_SERIAL}" shell input tap "${tap_x}" "${tap_y}" >/dev/null 2>&1
        recording_started_by_fallback="1"
        for _ in $(seq 1 30); do
            if grep -q '"event":"session_update"' "${SERVER_LOG}"; then
                session_ready=1
                break
            fi
            sleep 1
        done
    fi
    [[ "${session_ready}" == "1" ]]
    sleep 1
    progress_current "android" "android-ime" "Android real IME flow" "in_progress" \
        "Realtime session is ready; capturing UI evidence and driving dictation" \
        "The focused editor is verified through uiautomator; the IME is verified through service state and screenshots."
    # uiautomator dumps the focused host window, not the separate IME window.
    timeout 5 adb -s "${ANDROID_SERIAL}" shell logcat -c >/dev/null 2>&1 || true
    # Let the freshly rendered input view finish its first touch-dispatch pass.
    # Without this small settle, a cold Android 16 image can swallow the first
    # synthetic tap even though the screenshot already says Ready.
    sleep 1
    adb -s "${ANDROID_SERIAL}" exec-out screencap -p >"${ARTIFACT_DIR}/android/keyboard-before.png"
    sleep 1
    if [[ "${recording_started_by_fallback}" != "1" ]]; then
        adb -s "${ANDROID_SERIAL}" shell input tap "${tap_x}" "${tap_y}"
    fi
    # The visible recording layout is the reliable frontend signal. A clean
    # AVD can concurrently restart an optional Play Store/TTS process, so its
    # logcat is evidence only and must not gate the app's own UI flow.
    sleep 2
    timeout 5 adb -s "${ANDROID_SERIAL}" logcat -d -t 500 \
        >"${ARTIFACT_DIR}/android/recording-logcat.txt" 2>&1 || true
    adb -s "${ANDROID_SERIAL}" exec-out screencap -p >"${ARTIFACT_DIR}/android/keyboard-recording.png"
    progress_current "android" "android-recording" "Android dictation recording" "in_progress" \
        "The first mic tap entered the real RECORDING state" \
        "The automated stop tap is scheduled at 5 seconds; a second 15-second checkpoint remains as fallback."

    auto_stop_tap() {
        local checkpoint="$1"
        echo "E2E_ANDROID_AUTO_STOP checkpoint=${checkpoint} x=${tap_x} y=${tap_y}"
        progress_current "android" "android-stop-${checkpoint}" "Android automated stop tap" "in_progress" \
            "Sending the stop tap at ${tap_x},${tap_y}" \
            "The command is timeout-protected so a slow emulator cannot hide the failure."
        if timeout 10 adb -s "${ANDROID_SERIAL}" shell input tap "${tap_x}" "${tap_y}"; then
            timeout 10 adb -s "${ANDROID_SERIAL}" exec-out screencap -p \
                >"${ARTIFACT_DIR}/android/keyboard-stop-${checkpoint}.png" || true
            progress_card "tried" "android" "android-stop-${checkpoint}" "Android automated stop tap" "pass" \
                "ADB stop tap returned; waiting for ws_commit and usage_post" \
                "The stop-state screenshot is keyboard-stop-${checkpoint}.png."
            progress_current "android" "android-stop-${checkpoint}" "Android automated stop tap" "passed" \
                "Stop tap returned; waiting for ws_commit and usage_post" \
                "The stop-state screenshot is keyboard-stop-${checkpoint}.png."
        else
            progress_card "tried" "android" "android-stop-${checkpoint}" "Android automated stop tap" "fail" \
                "The ADB tap timed out" \
                "The recording state screenshot remains available for diagnosis."
        fi
    }

    # Use a direct timed checkpoint instead of relying on a loop counter for
    # the first stop. This keeps the automatic action observable and avoids
    # losing it when an emulator command takes longer than one second.
    sleep 5
    auto_stop_tap 5
    wait_for_commit() {
        local seconds="$1"
        for attempt in $(seq 1 "${seconds}"); do
            if grep -q '"event":"usage_post"' "${SERVER_LOG}" \
                && grep -q '"event":"ws_commit"' "${SERVER_LOG}"; then
                return 0
            fi
            sleep 1
        done
        return 1
    }

    # If the first tap landed during the last frame of the IME transition, the
    # screenshot may still show the mic even though the state machine has not
    # entered RECORDING yet. Give that tap ten seconds, then send exactly one
    # explicit retry and wait again. Keeping the retry outside a counter-based
    # conditional also leaves a visible checkpoint card/evidence on failure.
    if ! wait_for_commit 10; then
        auto_stop_tap 15
        wait_for_commit 25
    fi

    adb -s "${ANDROID_SERIAL}" exec-out screencap -p >"${ARTIFACT_DIR}/android/keyboard-after.png"
    adb -s "${ANDROID_SERIAL}" shell uiautomator dump /sdcard/livetype-window-after.xml >/dev/null
    adb -s "${ANDROID_SERIAL}" exec-out cat /sdcard/livetype-window-after.xml >"${ARTIFACT_DIR}/android/window-after.xml"
    grep -q "Hello from LiveType" "${ARTIFACT_DIR}/android/window-after.xml"
    progress_card "succeeded" "android" "android-ime-e2e" "Android headless IME E2E" "pass" \
        "The real IME inserted Hello from LiveType and reported usage" \
        "Evidence: Android before/recording/after screenshots, window-after.xml, and fake-server.log."
    echo "Android headless E2E: PASS"
}

run_ios() {
    progress_current "ios" "ios-runtime" "iOS headless Simulator" "in_progress" \
        "Booting the named iOS Simulator" \
        "The host app and embedded keyboard extension will be installed with simctl."
    local runtime
    runtime=$(xcrun simctl list runtimes -j | python3 -c '
import json, sys
data = json.load(sys.stdin)
for item in data.get("runtimes", []):
    if item.get("platform") == "iOS" and item.get("isAvailable"):
        print(item["identifier"])
        break
')
    [[ -n "${runtime}" ]] || { echo "No available iOS Simulator runtime" >&2; return 1; }

    IOS_DEVICE_UDID=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
runtime, name = sys.argv[1:]
data = json.load(sys.stdin)
for device in data.get("devices", {}).get(runtime, []):
    if device.get("name") == name:
        print(device["udid"])
        break
' "${runtime}" "${IOS_DEVICE_NAME}")
    if [[ -z "${IOS_DEVICE_UDID}" ]]; then
        local device_type
        device_type=$(xcrun simctl list devicetypes | sed -n 's/.*(\(com.apple.CoreSimulator.SimDeviceType.iPhone[^)]*\)).*/\1/p' | head -n 1)
        IOS_DEVICE_UDID=$(xcrun simctl create "${IOS_DEVICE_NAME}" "${device_type}" "${runtime}")
    fi
    xcrun simctl boot "${IOS_DEVICE_UDID}" >/dev/null 2>&1 || true
    xcrun simctl bootstatus "${IOS_DEVICE_UDID}" -b >/dev/null
    progress_current "ios" "ios-build" "iOS Simulator build" "in_progress" \
        "Building the LiveType scheme for iphonesimulator" \
        "Debug overrides point to the local protocol double."

    (
        cd "${ROOT_DIR}/ios"
        xcodebuild -project LiveType.xcodeproj -scheme LiveType -configuration Debug \
            -sdk iphonesimulator -derivedDataPath "${ROOT_DIR}/ios/build/e2e" \
            CODE_SIGNING_ALLOWED=NO \
            LIVETYPE_TOKEN_ENDPOINT="http://127.0.0.1:${E2E_PORT}/token" \
            LIVETYPE_DEVICE_SECRET="e2e-device-secret" \
            LIVETYPE_REALTIME_URL="ws://127.0.0.1:${E2E_PORT}/realtime" build
    ) >"${ARTIFACT_DIR}/ios/build.log" 2>&1
    local app="${ROOT_DIR}/ios/build/e2e/Build/Products/Debug-iphonesimulator/LiveType.app"
    [[ -d "${app}" ]]
    xcrun simctl install "${IOS_DEVICE_UDID}" "${app}" >"${ARTIFACT_DIR}/ios/install.log"
    xcrun simctl privacy "${IOS_DEVICE_UDID}" grant microphone dev.dobrinskiy.livetype >/dev/null 2>&1 || true
    xcrun simctl launch "${IOS_DEVICE_UDID}" dev.dobrinskiy.livetype --livetype-e2e >"${ARTIFACT_DIR}/ios/launch.log"
    progress_current "ios" "ios-host-qa" "iOS host protocol QA" "in_progress" \
        "Waiting for the QA host screen to fetch token and usage data" \
        "The host app includes a visual keyboard preview and the embedded extension binary."

    for _ in $(seq 1 30); do
        if grep -q '"event":"usage_get"' "${SERVER_LOG}"; then break; fi
        sleep 1
    done
    grep -q '"event":"token"' "${SERVER_LOG}"
    grep -q '"event":"usage_get"' "${SERVER_LOG}"
    xcrun simctl io "${IOS_DEVICE_UDID}" screenshot "${ARTIFACT_DIR}/ios/e2e.png" >/dev/null
    [[ -s "${ARTIFACT_DIR}/ios/e2e.png" ]]
    progress_card "succeeded" "ios" "ios-simulator-e2e" "iOS headless Simulator E2E" "pass" \
        "The app/extension build launched and the QA screen completed token + usage calls" \
        "Evidence: ios/build.log, ios/launch.log, fake-server.log, and ios/e2e.png."
    echo "iOS Simulator headless E2E: PASS"
}

run_android
run_ios
progress_card "succeeded" "shared" "runner-${RUN_ID}" "Headless iOS + Android E2E run" "pass" \
    "Both simulator flows passed" \
    "Artifacts: ${ARTIFACT_DIR}; no physical Android device was addressed."
progress_current "shared" "runner" "Headless iOS + Android E2E" "passed" \
    "Both simulator flows passed" \
    "Artifacts: ${ARTIFACT_DIR}; progress page remains available at http://127.0.0.1:${PROGRESS_PORT}/."
echo "Both simulator E2E flows passed. Artifacts: ${ARTIFACT_DIR}"
