# LiveType

LiveType is a personal Android voice input method (IME) backed by OpenAI
`gpt-live-transcribe`.

The repository contains:

- `android/` — an Android Studio project written in Kotlin.
- `worker/` — a Cloudflare Worker that exchanges a long-lived OpenAI API key
  for a short-lived Realtime client secret.

The Android app never stores the OpenAI API key. Audio is sent directly from
the phone to OpenAI; the Worker only handles short-lived token creation.

## 1. Deploy the token Worker

Prerequisites: Node.js 20+ and a Cloudflare account.

```bash
cd worker
npm install
npx wrangler login
npx wrangler secret put OPENAI_API_KEY
npx wrangler secret put DEVICE_SECRET
npm run deploy
```

Use a long random value for `DEVICE_SECRET`, for example:

```bash
openssl rand -hex 32
```

Copy the resulting URL and append `/token`, for example:

```text
https://livetype-token.your-account.workers.dev/token
```

For automatic deployment, connect the repository in Cloudflare Dashboard:
Workers & Pages → your Worker → Settings → Builds → Connect. Use `worker` as
the root directory, `npm install` as the build command, and
`npx wrangler deploy` as the deploy command. Add only `DEVICE_SECRET` to the
Android app; keep `OPENAI_API_KEY` exclusively in Cloudflare.

## 2. Build the Android app

Open the `android` directory in Android Studio. Let Gradle sync, then run the
`app` configuration on your phone. The project requires JDK 17 and Android SDK
35 or newer.

You can also build from a terminal:

```bash
cd android
./gradlew assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 3. Configure LiveType

1. Open the LiveType app.
2. Grant microphone permission.
3. Enter the Worker `/token` URL.
4. Enter the same `DEVICE_SECRET` used in Cloudflare.
5. Save settings.
6. Tap **Enable LiveType** and enable it in Android input-method settings.
7. Tap **Choose keyboard** and select LiveType.

When LiveType is active:

- Tap the microphone to begin dictation.
- Tap **Finish** to commit the audio buffer.
- Partial text is shown as composing text in the current field.
- The final transcript replaces the partial text.
- If enabled, LiveType switches back to the previous keyboard.

LiveType refuses to record in password fields and does not store transcript
history.

## Configuration

The setup screen supports:

- expected languages, comma-separated (`ru,en`);
- contextual prompt;
- keywords, one per line;
- automatic return to the previous keyboard.

The defaults are intended for Russian/English technical dictation.

## Security notes

`DEVICE_SECRET` prevents casual public use of the token endpoint, but a secret
embedded in a personal APK can be extracted by a determined attacker. This is
reasonable for a sideloaded personal application when combined with:

- a strict OpenAI project spending limit;
- a short Realtime client-secret lifetime;
- Cloudflare rate limiting;
- rotating `DEVICE_SECRET` if the APK is shared.

Do not publish this version to Google Play without replacing the shared secret
with real user authentication or device attestation.

