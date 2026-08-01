# LiveType: быстрый запуск

Русская версия быстрого старта. Полное описание — в [README.md](README.md).

LiveType — это Android-клавиатура для голосового ввода. Распознавание идёт
через OpenAI Realtime API, а твой OpenAI-ключ живёт только в Cloudflare
Worker, который ты разворачиваешь сам. Телефон получает от Worker временный
токен (60 секунд) и подключается к OpenAI напрямую — аудио через Worker не
проходит. Интерфейс приложения переключается между русским и английским по
языку системы.

## Что понадобится

- телефон с Android 9 или новее;
- аккаунт Cloudflare (хватает бесплатного плана);
- отдельный OpenAI API key с включённым биллингом;
- Node.js 20+ для Worker;
- Android Studio (или JDK 17 + Android SDK), если собираешь APK сам.

Подписка ChatGPT Plus не включает доступ к OpenAI API.

## Шаг 1. Создать секрет устройства

На компьютере:

```bash
openssl rand -hex 32
```

Сохрани строку — это `DEVICE_SECRET`, пароль телефона к твоему Worker.

## Шаг 2. Задеплоить Cloudflare Worker

```bash
cd worker
npm ci
npx wrangler login
npx wrangler secret put OPENAI_API_KEY   # OpenAI API key
npx wrangler secret put DEVICE_SECRET    # строка из шага 1
npm run deploy
```

После деплоя Cloudflare покажет адрес вида
`https://livetype-token.example.workers.dev`. Приложению нужен адрес
с `/token`:

```text
https://livetype-token.example.workers.dev/token
```

Модель распознавания выбирает Worker, а не телефон. По умолчанию это
`gpt-live-transcribe`; сменить её можно переменной `TRANSCRIPTION_MODEL` —
список допустимых моделей и того, какие подсказки каждая принимает, есть
в README.

Автодеплой (по желанию): на странице Worker — **Settings → Builds → Connect**.
Корневая папка `worker`, команда сборки `npm ci`, команда деплоя
`npx wrangler deploy`.

## Шаг 3. Поставить приложение

Готовый APK — на странице [GitHub Releases](../../releases). Либо собери сам:

```bash
cd android
./gradlew assembleDebug
```

APK появится здесь:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew` — небольшой скрипт, который при первом запуске сам скачивает
Gradle 8.9. Нужен Android SDK: либо переменная `ANDROID_HOME`, либо файл
`android/local.properties` со строкой `sdk.dir=/путь/к/Android/sdk`. В Android
Studio достаточно открыть папку `android` и нажать Run.

## Шаг 4. Настроить клавиатуру

1. Открой приложение LiveType.
2. Разреши доступ к микрофону.
3. Вставь Worker URL с `/token`. В release-сборке принимается только `https://`.
4. Вставь `DEVICE_SECRET`.
5. Укажи ожидаемые языки (например, `ru,en`), контекст и термины.
6. Сохрани настройки.
7. Нажми **Включить LiveType** и включи `LiveType Voice Input` в системных
   настройках.
8. Нажми **Выбрать клавиатуру** и выбери LiveType.

## Как пользоваться

- Микрофон — начать диктовку, повторное нажатие — завершить.
- Текст появляется в поле по мере распознавания и уточняется на лету.
- Кнопка ⏎ вставляет перенос строки, в том числе прямо во время диктовки.
- **Отмена** сбрасывает текущую фразу, **Настройки** открывают приложение.
- Два индикатора показывают связь с Worker и с OpenAI; нажми на любой, чтобы
  увидеть статус.
- В полях ввода пароля диктовка не работает — это сделано намеренно.

## Перед реальным использованием

В OpenAI Platform заведи отдельный Project под LiveType и поставь небольшой
месячный budget с алертом.

`DEVICE_SECRET` — общий секрет, а не аутентификация пользователя: тот, кто
получит доступ к настроенному телефону или к настроенному APK, сможет его
достать. Для личной sideloaded-клавиатуры этого достаточно, для публикации
в Google Play — нет. Если телефон потерян, перевыпусти секрет
(`wrangler secret put DEVICE_SECRET` + новый ввод в приложении).

## Локальная разработка

Worker можно гонять локально, а телефон направить на него через USB:

```bash
cd worker && npx wrangler dev        # нужен worker/.dev.vars
adb reverse tcp:8787 tcp:8787
# адрес в приложении: http://127.0.0.1:8787/token
```

Незашифрованный HTTP разрешён **только** в debug-сборке и только для
loopback-адресов. Release-сборка ходит исключительно по HTTPS. Подробности —
в [AGENTS.md](AGENTS.md).
