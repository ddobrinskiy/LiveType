# LiveType: быстрый запуск

В проекте уже есть Android-клавиатура и Cloudflare Worker. Для первого запуска
нужно только добавить собственные секреты и собрать APK.

## Что понадобится

- Android Studio;
- аккаунт Cloudflare;
- отдельный OpenAI API key с включённым биллингом;
- Android-телефон с Android 9 или новее.

Подписка ChatGPT Plus не включает использование OpenAI API.

## Шаг 1. Создать секрет устройства

На компьютере:

```bash
openssl rand -hex 32
```

Сохрани получившуюся строку. Она будет использоваться как `DEVICE_SECRET`.

## Шаг 2. Задеплоить Cloudflare Worker

```bash
cd worker
npm install
npx wrangler login
npx wrangler secret put OPENAI_API_KEY
npx wrangler secret put DEVICE_SECRET
npm run deploy
```

В первую команду `secret put` вставь OpenAI API key, во вторую — секрет из
первого шага.

После деплоя Cloudflare покажет адрес примерно такого вида:

```text
https://livetype-token.example.workers.dev
```

Для приложения нужен адрес с `/token`:

```text
https://livetype-token.example.workers.dev/token
```

Чтобы включить автодеплой, подключи GitHub-репозиторий на странице Worker:
**Settings → Builds → Connect**. Корневая папка — `worker`, команда деплоя —
`npx wrangler deploy`.

## Шаг 3. Собрать Android-приложение

1. Открой папку `android` в Android Studio.
2. Дождись Gradle Sync.
3. Подключи телефон с включённым USB debugging.
4. Нажми Run.

Либо выполни:

```bash
cd android
./gradlew assembleDebug
```

APK появится здесь:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Шаг 4. Настроить клавиатуру

1. Открой установленное приложение LiveType.
2. Разреши доступ к микрофону.
3. Вставь Worker URL с `/token`.
4. Вставь `DEVICE_SECRET`.
5. Сохрани настройки.
6. Нажми **Включить LiveType**.
7. В системных настройках включи `LiveType Voice Input`.
8. Открой любое текстовое поле и выбери LiveType через системную кнопку смены
   клавиатуры.

После завершения диктовки LiveType вставит финальный текст и вернётся к
предыдущей клавиатуре.

## Перед реальным использованием

В OpenAI Platform создай отдельный Project для LiveType и установи небольшой
месячный budget/alert. Не публикуй APK: текущий `DEVICE_SECRET` рассчитан на
личное sideloaded-приложение.

