# Техническое задание: OpenAI-compatible API для TTS/ASR поверх Yandex SpeechKit

## 1. Что это за сервис

Этот сервис - HTTP-прокси на Java (Spring Boot), который дает клиенту интерфейс, похожий на OpenAI Audio API, а внутри вызывает Yandex SpeechKit.

Поддерживаются 2 внешних endpoint:

1. `POST /v1/audio/speech` - синтез речи (TTS, text-to-speech).
2. `POST /v1/audio/transcriptions` - распознавание речи (ASR/STT, speech-to-text).

Ссылки на внешние API:

- OpenAI Audio guide: https://platform.openai.com/docs/guides/audio
- OpenAI Speech endpoint (`/audio/speech`): https://platform.openai.com/docs/api-reference/audio/createSpeech
- OpenAI Transcriptions endpoint (`/audio/transcriptions`): https://platform.openai.com/docs/api-reference/audio/createTranscription
- Yandex SpeechKit (общая документация): https://yandex.cloud/ru/docs/speechkit/
- Yandex TTS v3 (`utteranceSynthesis`): https://yandex.cloud/ru/docs/speechkit/tts/api/tts-v3
- Yandex STT v1 (`stt:recognize`): https://yandex.cloud/ru/docs/speechkit/stt/api/request-api

---

## 2. Границы системы и зона ответственности

### 2.1. Что делает наш сервис

1. Принимает OpenAI-подобные запросы от клиента.
2. Валидирует входные параметры.
3. Добавляет корреляцию через `X-Request-Id` и MDC (`request_id`, `path`).
4. Преобразует/маппит форматы и часть параметров под SpeechKit.
5. Использует IAM-токен для авторизации в Yandex Cloud.
6. Нормализует ASR-аудио через `ffmpeg` перед отправкой в SpeechKit.
7. Возвращает ответ клиенту в согласованном формате.
8. Преобразует ошибки в единый OpenAI-style envelope.

### 2.2. Что делает upstream (Yandex SpeechKit)

1. Генерирует аудио из текста (TTS).
2. Распознает текст из аудиобайтов (STT).

### 2.3. Что не поддерживается

1. SSE-стриминг TTS (`stream_format=sse`) - принудительный `400 not_supported`.
2. Расширенные OpenAI-режимы (`verbose_json`, субтитры, async и т.п.).
3. Диаризация, таймкоды и продвинутая разметка.

---

## 3. Технический контекст

1. Стек: Spring Boot `3.5.9`, Java `21`.
2. Базовый префикс API: `/v1`.
3. Порт по умолчанию: `8081`.
4. Health endpoint: `GET /actuator/health` (в exposure включен `health`).
5. Входящая локальная авторизация к нашему сервису отсутствует (endpoint доступны без auth).

### 3.1. Минимум для первого запуска

Перед первым запуском должны быть готовы:

1. Java `21`.
2. `ffmpeg` в `PATH` (или абсолютный путь в `ASR_NORMALIZE_FFMPEG_PATH`).
3. `YANDEX_FOLDER_ID`.
4. `YANDEX_IAM_TOKEN`.

### 3.2. Как задать переменные окружения в IntelliJ IDEA

Рекомендуемый способ запуска - через Run Configuration в IDEA.

1. Открыть `Run | Edit Configurations...`.
2. Выбрать конфигурацию Spring Boot для проекта.
3. В поле `Environment variables` указать переменные через точку с запятой.

Пример значения поля:

```text
YANDEX_FOLDER_ID=<ваш_folder_id>;YANDEX_IAM_TOKEN=<ваш_iam_token>;ASR_NORMALIZE_FFMPEG_PATH=ffmpeg
```

4. Сохранить конфигурацию и запустить приложение.

Примечание: при замене IAM-токена нужно обновить значение в `Environment variables` и перезапустить конфигурацию.

---

## 4. Корреляция и логи (`X-Request-Id`)

### 4.1. Правила

1. Клиент может передать заголовок `X-Request-Id`.
2. Если заголовок отсутствует/пустой - сервис генерирует UUID.
3. Сервис всегда добавляет `X-Request-Id` в ответ.
4. На время обработки запроса в MDC кладутся:

   - `request_id`
   - `path`

### 4.1.1. Что такое MDC

`MDC` (Mapped Diagnostic Context) - это "контекст логирования" на уровне текущего потока выполнения.

Практический смысл:

1. Перед обработкой запроса в MDC кладутся служебные поля (например, `request_id`, `path`).
2. Логгер автоматически подставляет эти поля в каждую строку лога (если они есть в logging pattern).
3. В конце запроса MDC очищается, чтобы данные одного запроса не "протекали" в другой.

Почему это важно:

1. По `request_id` можно быстро собрать все логи конкретного запроса.
2. Проще диагностировать ошибки между слоями `controller -> service -> client`.
3. Логи остаются читаемыми даже при параллельной обработке нескольких запросов.

### 4.2. Зачем это нужно

По `X-Request-Id` можно связать:

1. Логи контроллера.
2. Логи service/client слоя.
3. Ответ клиенту.
4. Диагностический файл `/tmp/tts-upstream-<request_id>.json` (если включен debug payload dump).

### 4.3. Как реализовать `X-Request-Id` фильтр (пошагово)

Ниже - готовый алгоритм, который нужно повторить без изменений логики.

1. Создать servlet-фильтр на базе `OncePerRequestFilter`.

   - Почему именно так: `OncePerRequestFilter` гарантирует один проход фильтра на запрос в рамках Spring MVC-цепочки.

2. Определить константу заголовка:

   - `REQUEST_ID_HEADER = "X-Request-Id"`

3. В `doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)` сделать:

   1. Прочитать `request.getHeader("X-Request-Id")`.
   2. Если значение `null` или blank -> `UUID.randomUUID().toString()`.
   3. Положить в MDC:

      - `MDC.put("request_id", requestId)`
      - `MDC.put("path", request.getRequestURI())`

   4. Добавить header в ответ:

      - `response.setHeader("X-Request-Id", requestId)`

   5. Вызвать `chain.doFilter(request, response)`.
   6. В `finally` обязательно очистить MDC:

      - `MDC.remove("request_id")`
      - `MDC.remove("path")`

4. Зарегистрировать фильтр как Spring bean:

   - простой вариант: `@Component` на классе фильтра.

5. Убедиться, что лог-паттерн использует MDC-ключи:

   - `%X{request_id:-}`
   - `%X{path:-}`

### 4.4. Что нужно проверить тестами

1. Если клиент прислал `X-Request-Id`, сервис возвращает тот же id.
2. Если клиент не прислал `X-Request-Id`, сервис возвращает непустой UUID.
3. Для обоих сценариев endpoint работает штатно (не ломается бизнес-логика).

### 4.5. Ссылки на документацию Spring

1. Spring Boot reference (Servlet stack, фильтры):

   - https://docs.spring.io/spring-boot/reference/web/servlet.html

2. Spring Framework reference (Servlet Filters):

   - https://docs.spring.io/spring-framework/reference/web/webmvc/filters.html

3. `OncePerRequestFilter` (Javadoc):

   - https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/filter/OncePerRequestFilter.html

4. Spring Boot logging (как использовать MDC в паттерне логов):

   - https://docs.spring.io/spring-boot/reference/features/logging.html

---

## 5. Внешний API: TTS

### 5.1. Endpoint

- `POST /v1/audio/speech`
- `Content-Type: application/json`

### 5.2. Тело запроса

```json
{
  "model": "gpt-4o-mini-tts",
  "input": "Привет! Это проверка синтеза.",
  "voice": "alloy",
  "response_format": "mp3",
  "speed": 1.0,
  "stream_format": "none"
}
```

Поля:

1. `model` (`string`, required, not blank).
2. `input` (`string`, required, not blank).
3. `voice` (`string`, optional).
4. `response_format` (`string`, optional): `mp3 | ogg | wav | pcm`, default = `mp3`.
5. `speed` (`number`, optional): `0.25..3.0`.
6. `stream_format` (`string`, optional): если `sse` (без учета регистра), запрос отклоняется.

Примечание по `model`: поле обязательно для OpenAI-совместимого контракта, но на выбор upstream-модели SpeechKit не влияет.

### 5.3. Ответ при успехе

1. HTTP `200 OK`.
2. Тело - бинарные аудиобайты.
3. Заголовки:

   - `X-Request-Id: <id>`
   - `Content-Disposition: attachment; filename="speech.<ext>"`
   - `Content-Type` зависит от `response_format`:

     - `mp3` -> `audio/mpeg`
     - `ogg` -> `audio/ogg`
     - `wav` -> `audio/wav`
     - `pcm` -> `audio/pcm`

### 5.4. Маппинг TTS-голосов и параметров

1. Если `voice` не передан/пустой -> используется `app.speechkit.default-voice` (по умолчанию `alena`).
2. Применяется `voice-mapping`: например `alloy -> masha`.
3. Для итогового SpeechKit-голоса читаются `tts.voice-settings.<voice>`:

   - `role`
   - `speed`
   - `pitch`

4. Приоритет скорости:

   1. `speed` из request.
   2. Иначе `voice-settings.<voice>.speed`.
   3. Иначе `speed` не отправляется в upstream.

### 5.5. Ошибки TTS

1. `stream_format=sse` -> `400`, `code=not_supported`, `param=stream_format`.
2. Валидация request (`model`, `input`, `speed`) -> `400`, `code=validation_error`.
3. Неподдерживаемый `response_format` -> `400`, `code=validation_error`, `param=null`.
4. Ошибки upstream/сети/таймауты -> по правилам раздела 10.

---

## 6. Внешний API: ASR

### 6.1. Endpoint

- `POST /v1/audio/transcriptions`
- `Content-Type: multipart/form-data`

### 6.2. Поля формы

1. `file` (`MultipartFile`, required).
2. `model` (`string`, required, not blank).
3. `language` (`string`, optional) - если не передан, используется `app.speechkit.default-language`.
4. `response_format` (`string`, optional): `json` (default) или `text`.

Примечание по `model`: поле обязательно для OpenAI-совместимого контракта, но напрямую в STT-запрос SpeechKit не пробрасывается.

### 6.3. Совместимость strict/non-strict

Есть переключатель `app.compat.strict` (`COMPAT_STRICT`, default `false`):

1. `strict=false` (по умолчанию): лишние поля в multipart игнорируются.
2. `strict=true`: если есть неизвестное поле (кроме `file`, `model`, `language`, `response_format`) ->
   `400`, `code=unsupported_field`, `param=<имя_поля>`.

### 6.4. Ответ при успехе

1. Если `response_format=text` ->

   - HTTP `200`
   - `Content-Type: text/plain`
   - тело: строка распознанного текста

2. Если `response_format=json` ->

   - HTTP `200`
   - `Content-Type: application/json`
   - тело:

```json
{ "text": "..." }
```

### 6.5. Ошибки ASR

1. Нет `file` или `model` -> `400`, `code=missing_parameter`.
2. `file` пустой -> `400`, `code=validation_error`, `param=file`.
3. Неподдерживаемый `response_format` -> `400`, `code=validation_error`, `param=response_format`.
4. Multipart limit exceeded -> `413`, `code=file_too_large`, `param=file`.
5. Ошибка чтения файла -> `400`, `code=invalid_file`, `param=file`.
6. Ошибки нормализации `ffmpeg` -> см. раздел 9.
7. Ошибки upstream -> см. раздел 10.

---

## 7. Интеграция с Yandex SpeechKit

### 7.1. TTS upstream (v3)

1. Base URL: `app.speechkit.base-url` (default `https://tts.api.cloud.yandex.net`).
2. Method/path: `POST /tts/v3/utteranceSynthesis`.
3. Заголовки:

   - `Authorization: Bearer <IAM_TOKEN>`
   - `x-folder-id: <FOLDER_ID>`

4. Тело запроса:

   - `text`
   - `hints`: всегда содержит `{"voice": "..."}`; опционально `role`, `speed`, `pitchShift`
   - `outputAudioSpec`:

     - `mp3` -> `containerAudio.containerAudioType=MP3`
     - `ogg` -> `containerAudio.containerAudioType=OGG_OPUS`
     - `pcm` и `wav` -> `rawAudio.audioEncoding=LINEAR16_PCM` + `sampleRateHertz`

5. Обработка ответа:

   - ожидается JSON c `result.audioChunk.data` или `audioChunk.data`
   - `data` декодируется как base64 (включая tolerant-обработку url-safe символов `-`/`_` и missing padding)
   - для внешнего `wav` сервис оборачивает raw PCM в WAV-контейнер

### 7.2. STT upstream (v1)

1. Base URL: `app.speechkit.stt-base-url` (default `https://stt.api.cloud.yandex.net`).
2. Method/path: `POST /speech/v1/stt:recognize`.
3. Query params:

   - `folderId=<FOLDER_ID>`
   - `lang=<language>`
   - опционально `format=<format>`
   - опционально `sampleRateHertz=<rate>`

Примечание для этого сервиса: из-за обязательной нормализации ASR фактически всегда передаются `format=lpcm` и `sampleRateHertz=ASR_NORMALIZE_TARGET_SAMPLE_RATE_HERTZ`.

4. Тело: сырые байты файла (`application/octet-stream`, не multipart).

---

## 8. ASR-пайплайн и особенность `lpcm`

### 8.1. Единый ASR-пайплайн

Для всех входных файлов используется один путь обработки:

1. Файл конвертируется через `ffmpeg` в WAV (`pcm_s16le`, mono, target sample rate).
2. В STT-запрос передается `format=lpcm`.
3. В STT-запрос передается `sampleRateHertz=ASR_NORMALIZE_TARGET_SAMPLE_RATE_HERTZ`.

Это упрощает поведение API: независимо от исходного формата вход приводится к единому виду перед распознаванием.

### 8.2. Особенность формата `lpcm`

При `format=lpcm` upstream получает байты WAV-контейнера после нормализации, а не чистый raw LPCM.

В спецификации SpeechKit для `lpcm` ожидается raw PCM16LE без WAV-заголовка. Это нужно учитывать при интеграции и при планировании развития API.

---

## 9. Нормализация ASR через `ffmpeg`

### 9.1. Режим работы

Нормализация обязательна и выполняется для каждого ASR-запроса.

Сервис всегда:

1. Конвертирует вход через `ffmpeg`.
2. Для STT вызывает `format=lpcm`.
3. Передает `sampleRateHertz=ASR_NORMALIZE_TARGET_SAMPLE_RATE_HERTZ`.

### 9.2. Алгоритм

1. Проверка размера входа: `inputBytes <= max-input-bytes`.
2. (Опционально) ограничение длительности: `-t <max-duration-seconds>`, если > 0.
3. Создание временных файлов `asr-input-*.bin` и `asr-output-*.wav`.
4. Запуск `ffmpeg` через `ProcessBuilder` (без shell):

```text
<ffmpegPath> -hide_banner -loglevel error -y -i <input> [-t <maxSeconds>] -ac <channels> -ar <sampleRate> -acodec pcm_s16le -f wav <output>
```

5. Захват `stderr` с лимитом `max-stderr-bytes`.
6. Ожидание завершения не дольше `timeout-ms`.
7. Чтение результата из выходного файла.
8. Гарантированная очистка временных файлов в `finally`.
9. Если задан `concurrency-max-processes`, используется `Semaphore` для ограничения параллельных `ffmpeg` процессов.

### 9.3. Готовая команда `ffmpeg` и откуда берется каждый параметр

Ниже - точный шаблон запуска, который нужно использовать:

```text
<ffmpegPath> -hide_banner -loglevel error -y -i <input> [-t <maxSeconds>] -ac <channels> -ar <sampleRate> -acodec pcm_s16le -f wav <output>
```

Чтобы не угадывать, используйте такую подстановку:

1. `<ffmpegPath>` -> `app.speechkit.asr-normalize.ffmpeg-path`

   - обычно: `ffmpeg`
   - если бинарник лежит не в `PATH`, задается абсолютный путь
   - пример: `/opt/homebrew/bin/ffmpeg`

2. `<input>` -> путь к временному входному файлу `asr-input-*.bin`

   - файл создается приложением во временной директории
   - в него записываются байты загруженного `multipart file`

3. `[-t <maxSeconds>]` -> опционально, только если `max-duration-seconds > 0`

   - `<maxSeconds>` берется из `app.speechkit.asr-normalize.max-duration-seconds`
   - если значение `0`, параметр `-t` в команду не добавляется

4. `<channels>` -> `app.speechkit.asr-normalize.target-channels`

   - в типовом режиме: `1` (моно)

5. `<sampleRate>` -> `app.speechkit.asr-normalize.target-sample-rate-hertz`

   - в типовом режиме: `16000`

6. `<output>` -> путь к временному выходному файлу `asr-output-*.wav`

   - создается приложением рядом с входным временным файлом
   - после завершения `ffmpeg` читается в память и отправляется в STT

### 9.4. Что означает каждый флаг в команде

Разбор команды "человеческим языком":

1. `-hide_banner`

   - убирает служебный баннер `ffmpeg` из вывода
   - делает лог чище

2. `-loglevel error`

   - выводит только ошибки
   - предупреждения и информационные строки не печатаются

3. `-y`

   - разрешает перезапись выходного файла без интерактивного вопроса
   - важно для автоматического запуска из сервиса

4. `-i <input>`

   - входной аудиофайл для конвертации

5. `-t <maxSeconds>`

   - ограничивает длительность обрабатываемого аудио
   - защищает от очень длинных файлов

6. `-ac <channels>`

   - количество каналов в выходном аудио
   - `1` = mono, `2` = stereo

7. `-ar <sampleRate>`

   - частота дискретизации (samples per second)
   - пример: `16000` = 16 kHz

8. `-acodec pcm_s16le`

   - аудиокодек выхода: PCM, 16-bit, little-endian
   - это "сырой" формат данных, удобный для STT

9. `-f wav`

   - формат выходного контейнера: WAV
   - контейнер = файл-обертка с метаданными

10. `<output>`

   - путь выходного файла, который потом отправляется в SpeechKit

### 9.5. Словарь терминов (без которых трудно читать раздел)

1. **Нормализация аудио**

   - приведение разных входных файлов к одному предсказуемому виду
   - в этом ТЗ: mono + заданный sample rate + PCM s16le + WAV контейнер

2. **Канал (channel)**

   - отдельная аудиодорожка
   - mono = 1 канал, stereo = 2 канала

3. **Sample rate (частота дискретизации)**

   - сколько аудио-сэмплов в секунду
   - измеряется в Гц (например `16000`)

4. **PCM (Pulse-Code Modulation)**

   - несжатые аудиоданные
   - простая и предсказуемая форма сигнала

5. **s16le**

   - `s16` = signed 16-bit
   - `le` = little-endian порядок байт

6. **Контейнер WAV**

   - формат файла, в котором лежат аудиоданные + заголовки/метаданные
   - не путать с кодеком: WAV может содержать разные кодеки

7. **Кодек**

   - способ представления (кодирования) аудиоданных
   - здесь используется `pcm_s16le`

8. **`stderr`**

   - поток ошибок процесса
   - сервис читает его с лимитом, чтобы не переполнить память логами

9. **`ProcessBuilder` без shell**

   - команда запускается как список аргументов, а не как строка через `/bin/sh`
   - это безопаснее и предсказуемее (нет shell-интерпретации)

### 9.6. Мини-шпаргалка как собрать аргументы без ошибок

Используйте строго этот порядок аргументов:

1. `ffmpegPath`
2. `-hide_banner`
3. `-loglevel`, `error`
4. `-y`
5. `-i`, `inputPath`
6. (опционально) `-t`, `maxDurationSeconds` если `> 0`
7. `-ac`, `targetChannels`
8. `-ar`, `targetSampleRateHertz`
9. `-acodec`, `pcm_s16le`
10. `-f`, `wav`
11. `outputPath`

Если придерживаться этого списка, `ffmpeg`-часть решается как конструктор и не требует отдельного глубокого изучения `ffmpeg`.

### 9.7. Ошибки нормализации

1. Входной файл слишком большой -> `413`, `code=file_too_large`, `param=file`.
2. `ffmpeg` не найден/не запускается -> `502`, `code=upstream_unavailable`, `param=file`.
3. Таймаут/ошибка конвертации/прерывание -> `400`, `code=unsupported_media_type`, `param=file`.

---

## 10. Ошибки: единый формат и маппинг

### 10.1. Формат ответа ошибки (OpenAI-style)

```json
{
  "error": {
    "message": "...",
    "type": "...",
    "param": "...",
    "code": "..."
  }
}
```

### 10.2. Основные ошибки

| Сценарий | HTTP | type | code | param |
|---|---:|---|---|---|
| Валидация JSON/параметров | 400 | `invalid_request_error` | `validation_error` | поле или `null` |
| Нет обязательного multipart-поля | 400 | `invalid_request_error` | `missing_parameter` | `null` |
| Пустой файл | 400 | `invalid_request_error` | `validation_error` | `file` |
| Ошибка чтения файла | 400 | `invalid_request_error` | `invalid_file` | `file` |
| Неподдерживаемый media type | 400 | `invalid_request_error` | `unsupported_media_type` | обычно `file` |
| `stream_format=sse` | 400 | `invalid_request_error` | `not_supported` | `stream_format` |
| Лишнее поле в strict режиме | 400 | `invalid_request_error` | `unsupported_field` | имя поля |
| Слишком большой upload | 413 | `invalid_request_error` | `file_too_large` | `file` |
| Upstream timeout | 504 | `server_error` | `upstream_timeout` | `null` |
| Upstream 429 | 429 | `rate_limit_error` | `rate_limit_exceeded` | `tts`/`transcription` |
| Upstream 5xx/connection errors | 502 | `server_error` | `upstream_error` | зависит от контекста |
| Upstream 401/403 | 401/403 | `authentication_error` | `auth_error` | `tts`/`transcription` |
| Ошибка IAM config | 502 | `server_error` | `upstream_auth_config_error` | `null` |

### 10.3. Поведение на неучтенную ошибку

Глобальный fallback возвращает:

1. HTTP `502`.
2. `type=server_error`.
3. `code=upstream_error`.
4. `message="Upstream error while calling <URI>"`.

### 10.4. Как реализовать единую модель ошибок (кратко)

Рекомендуемый шаблон реализации:

1. Создать собственное исключение `ApiException` с полями:

   - `HttpStatus status`
   - `String message`
   - `String type`
   - `String param`
   - `String code`

2. Создать DTO ответа ошибки:

   - `OpenAiErrorResponse`
   - вложенный объект `error` c полями `message/type/param/code`

3. Сделать глобальный обработчик `@RestControllerAdvice`, который:

   - ловит `ApiException` и возвращает его поля как есть
   - маппит типовые framework-исключения (`MethodArgumentNotValidException`, `MissingServletRequestPartException`, `MaxUploadSizeExceededException` и т.д.) в формат из раздела 10.1
   - имеет fallback `@ExceptionHandler(Exception.class)` -> `502 upstream_error`

4. В контроллерах/сервисах бросать только `ApiException` для бизнес-ошибок API.

### 10.5. Мини-примеры

Пример 1 - валидационная ошибка:

```json
{
  "error": {
    "message": "response_format must be json or text",
    "type": "invalid_request_error",
    "param": "response_format",
    "code": "validation_error"
  }
}
```

Пример 2 - upstream timeout:

```json
{
  "error": {
    "message": "Upstream timeout",
    "type": "server_error",
    "param": null,
    "code": "upstream_timeout"
  }
}
```

### 10.6. Что проверить тестами

1. Все ошибки имеют один JSON-формат: `{ "error": { ... } }`.
2. Для каждой ключевой ситуации возвращается правильный HTTP-статус.
3. Проверяются минимум сценарии: `400`, `413`, `429`, `502`, `504`.
4. Проверяется fallback-обработчик на непредвиденное исключение.

### 10.7. Ссылки на документацию Spring

1. Error handling в Spring MVC:

   - https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html

2. `@ControllerAdvice` / `@RestControllerAdvice`:

   - https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-advice.html

3. Bean Validation в Spring Boot:

   - https://docs.spring.io/spring-boot/reference/io/validation.html

---

## 11. Аутентификация к Yandex Cloud

### 11.1. Единый режим аутентификации

И TTS, и STT всегда используют IAM bearer токен:

- `Authorization: Bearer <IAM_TOKEN>`

В рамках этого ТЗ используется один источник: статический IAM-токен из переменной окружения `YANDEX_IAM_TOKEN`.

Если `YANDEX_IAM_TOKEN` не задан, запросы к upstream завершаются ошибкой конфигурации (`502 upstream_auth_config_error`).

### 11.2. Как получить IAM-токен через YC CLI

1. Установить и настроить `yc` CLI (если не настроен).
2. Получить токен командой:

```bash
yc iam create-token
```

3. Записать токен в переменную окружения:

```bash
export YANDEX_IAM_TOKEN="<вставьте_токен_сюда>"
```

4. Запустить сервис в том же shell-сеансе.

Примечание: IAM-токен имеет ограниченный срок жизни (обычно до 12 часов). После истечения токен нужно получить заново и обновить переменную `YANDEX_IAM_TOKEN`.

Документация Yandex Cloud:

- https://yandex.cloud/ru/docs/iam/operations/iam-token/create

### 11.3. Поведение при `401/403` от upstream

Для вызовов SpeechKit:

1. Если upstream вернул `401` или `403`, это означает невалидный/истекший IAM-токен.
2. Необходимо получить новый токен через `yc iam create-token`.
3. Обновить `YANDEX_IAM_TOKEN` в Run Configuration IDEA (`Environment variables`).
4. Перезапустить приложение.
5. Повторить запрос.

---

## 12. Конфигурация (`application.yml`)

Ниже параметры, влияющие на поведение API и интеграции.

### 12.1. Сервис и multipart

```yaml
server:
  port: ${SERVER_PORT:8081}

spring:
  servlet:
    multipart:
      max-file-size: ${MAX_FILE_SIZE:10MB}
      max-request-size: ${MAX_REQUEST_SIZE:10MB}
```

### 12.2. SpeechKit и совместимость

```yaml
app:
  compat:
    strict: ${COMPAT_STRICT:false}
  speechkit:
    base-url: ${YANDEX_TTS_BASE_URL:https://tts.api.cloud.yandex.net}
    stt-base-url: ${YANDEX_STT_BASE_URL:https://stt.api.cloud.yandex.net}
    folder-id: ${YANDEX_FOLDER_ID:}
    default-voice: ${DEFAULT_VOICE:alena}
    default-language: ${DEFAULT_LANGUAGE:ru-RU}
    sample-rate-hertz: ${DEFAULT_SAMPLE_RATE_HERTZ:48000}
```

### 12.3. IAM

```yaml
app:
  speechkit:
    iam-token: ${YANDEX_IAM_TOKEN:}
```

### 12.4. ASR normalization

```yaml
app:
  speechkit:
    asr-normalize:
      enabled: true
      ffmpeg-path: ${ASR_NORMALIZE_FFMPEG_PATH:ffmpeg}
      temp-dir: ${ASR_NORMALIZE_TEMP_DIR:}
      max-input-bytes: ${ASR_NORMALIZE_MAX_INPUT_BYTES:26214400}
      max-duration-seconds: ${ASR_NORMALIZE_MAX_DURATION_SECONDS:0}
      timeout-ms: ${ASR_NORMALIZE_TIMEOUT_MS:15000}
      target-sample-rate-hertz: ${ASR_NORMALIZE_TARGET_SAMPLE_RATE_HERTZ:16000}
      target-channels: ${ASR_NORMALIZE_TARGET_CHANNELS:1}
      max-stderr-bytes: ${ASR_NORMALIZE_MAX_STDERR_BYTES:8192}
      concurrency-max-processes: ${ASR_NORMALIZE_CONCURRENCY_MAX_PROCESSES:}
```

### 12.5. Timeouts и диагностика

```yaml
app:
  speechkit:
    connect-timeout: ${UPSTREAM_CONNECT_TIMEOUT:5s}
    read-timeout: ${UPSTREAM_READ_TIMEOUT:30s}
    debug-log-tts-payload: ${DEBUG_LOG_TTS_PAYLOAD:false}
```

`debug-log-tts-payload=true` включает расширенную диагностику TTS payload, а при base64 decode error дополнительно пишет сырой JSON upstream в `/tmp/tts-upstream-<request_id>.json`.

---

## 13. Контракты и примеры вызова

### 13.1. TTS пример

```bash
curl -X POST http://localhost:8081/v1/audio/speech \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: demo-tts-1" \
  -d '{
    "model":"gpt-4o-mini-tts",
    "input":"Привет! Это проверка TTS.",
    "voice":"alloy",
    "response_format":"mp3",
    "speed":1.0
  }' \
  --output speech.mp3
```

### 13.2. ASR пример (`json`)

```bash
curl -X POST http://localhost:8081/v1/audio/transcriptions \
  -H "X-Request-Id: demo-asr-1" \
  -F file=@sample.wav \
  -F model=whisper-1 \
  -F language=ru-RU \
  -F response_format=json
```

### 13.3. ASR пример (`text`)

```bash
curl -X POST http://localhost:8081/v1/audio/transcriptions \
  -F file=@sample.wav \
  -F model=whisper-1 \
  -F response_format=text
```

### 13.4. Health

```bash
curl -s http://localhost:8081/actuator/health
```

Ожидаемый ответ: HTTP `200` и статус `UP`.

---

## 14. Чек-лист приемки

1. `POST /v1/audio/speech` возвращает `200` и аудио в форматах `mp3|ogg|wav|pcm`.
2. `stream_format=sse` возвращает `400`, `code=not_supported`.
3. `X-Request-Id` всегда присутствует в ответах (свой или сгенерированный).
4. `GET /actuator/health` доступен и возвращает `UP`.
5. `POST /v1/audio/transcriptions`:

   - `response_format=json` -> `{ "text": "..." }`
   - `response_format=text` -> plain text

6. При `COMPAT_STRICT=true` лишние multipart-поля дают `400 unsupported_field`.
7. Для каждого ASR-запроса сервис запускает `ffmpeg` и отправляет в STT нормализованные байты.
8. При недоступном `ffmpeg` возвращается `502 upstream_unavailable`.
9. При upstream `429` наружу возвращается `429 rate_limit_exceeded`.
10. При `401/403` требуется получить новый `YANDEX_IAM_TOKEN` и повторить запрос.

---

## 15. Известные ограничения и рекомендации на развитие

1. Для `format=lpcm` в STT используется WAV-контейнер (см. раздел 8.2).
2. Нет поддержки SSE streaming и расширенных OpenAI аудио-режимов.
3. Для production стоит унифицировать и документировать политику `type/code/param` для всех edge cases, чтобы API оставался максимально предсказуемым для клиентов.
4. Для стабильной эксплуатации важно логировать/мониторить:

   - долю `upstream_error`, `upstream_timeout`, `rate_limit_exceeded`
   - время ответа TTS/STT
   - частоту ошибок `401/403` (сигнал просроченного/невалидного IAM-токена)

---

## 16. План разработки с чек-листом

---

### Шаг 0. Ознакомление

**Цель:** понять, что делает сервис и какой поток данных.

**Что сделать:**

1. Прочитать ТЗ целиком.
2. Выписать два endpoint:

   - `POST /v1/audio/speech`
   - `POST /v1/audio/transcriptions`

3. Открыть документацию:

   - OpenAI Audio API
   - Yandex SpeechKit

4. Понять границу ответственности:

   - наша логика
   - upstream SpeechKit

**Чек-лист готовности:**

- [ ] Понимаю, какие endpoint реализуем.
- [ ] Понимаю форматы входа/выхода.
- [ ] Понимаю роль SpeechKit в системе.

---

### Шаг 1. Скелет проекта и запуск

**Цель:** приложение стартует локально.

**Что сделать:**

1. Проверить Java 21.
2. Запустить тесты:

```bash
./mvnw test
```

3. Запустить приложение:

```bash
./mvnw spring-boot:run
```

4. Для запуска из IntelliJ IDEA заполнить `Environment variables` в Run Configuration:

```text
YANDEX_FOLDER_ID=<ваш_folder_id>;YANDEX_IAM_TOKEN=<ваш_iam_token>;ASR_NORMALIZE_FFMPEG_PATH=ffmpeg
```

5. Проверить health:

```text
GET /actuator/health
```

**Чек-лист готовности:**

- [ ] Тесты проходят.
- [ ] Приложение стартует.
- [ ] Переменные окружения заданы в Run Configuration IDEA.
- [ ] Health возвращает `UP`.

---

### Шаг 2. Наблюдаемость: X-Request-Id

**Цель:** у каждого запроса есть корреляционный id.

**Что сделать:**

1. Реализовать фильтр:

   - если `X-Request-Id` пришел -> использовать
   - если не пришел -> сгенерировать UUID
   - всегда вернуть заголовок в response

2. Прокинуть в MDC:

   - `request_id`
   - `path`

3. Добавить тесты:

   - заголовок передан
   - заголовок не передан

**Чек-лист готовности:**

- [ ] В каждом ответе есть `X-Request-Id`.
- [ ] В логах есть `request_id` и `path`.
- [ ] Есть тесты на оба сценария.

---

### Шаг 3. Единая модель ошибок

**Цель:** все ошибки имеют единый формат.

**Что сделать:**

1. Ввести envelope:

```json
{
  "error": {
    "message": "...",
    "type": "...",
    "param": "...",
    "code": "..."
  }
}
```

2. Реализовать `ApiException`.
3. Реализовать `GlobalExceptionHandler`.
4. Настроить маппинг:

   - `validation_error` -> `400`
   - `missing_parameter` -> `400`
   - `file_too_large` -> `413`
   - `upstream_timeout` -> `504`
   - `upstream_error` -> `502`
   - `rate_limit_exceeded` -> `429`

5. Добавить тесты на ошибки.

**Чек-лист готовности:**

- [ ] Все ошибки в едином формате.
- [ ] HTTP-коды соответствуют ТЗ.
- [ ] Есть тесты на основные ошибки.

---

### Шаг 4. IAM token

**Цель:** сервис использует статический IAM-токен из окружения.

**Что сделать:**

1. Реализовать `TokenProvider`.
2. Взять значение токена только из `YANDEX_IAM_TOKEN`.
3. Добавить проверку, что при пустом токене возвращается понятная ошибка конфигурации.
4. Описать в README/ТЗ, как получить токен через `yc iam create-token`.
5. Описать, как обновить токен в Run Configuration IDEA и перезапустить приложение.
6. Добавить тесты.

**Чек-лист готовности:**

- [ ] Токен успешно получается.
- [ ] Используется только `YANDEX_IAM_TOKEN`.
- [ ] Есть инструкция обновления токена после истечения срока.

---

### Шаг 5. TTS-клиент (SpeechKit)

**Цель:** работает `POST /v1/audio/speech`.

**Что сделать:**

1. Реализовать вызов:

```text
POST /tts/v3/utteranceSynthesis
```

2. Добавить заголовки:

   - `Authorization: Bearer <IAM_TOKEN>`
   - `x-folder-id`

3. Декодировать `audioChunk.data`.
4. Реализовать форматы:

   - `mp3`
   - `ogg`
   - `pcm`
   - `wav` (через упаковку PCM в WAV)

5. Добавить тесты (MockWebServer).

**Чек-лист готовности:**

- [ ] TTS возвращает аудио.
- [ ] Все 4 формата работают.
- [ ] Заголовки корректны.
- [ ] Есть тесты.

---

### Шаг 6. ASR endpoint

**Цель:** работает `POST /v1/audio/transcriptions`.

**Что сделать:**

1. Реализовать multipart endpoint.
2. Обработать поля:

   - `file`
   - `model`
   - `language`
   - `response_format`

3. Реализовать форматы ответа:

   - `json`
   - `text`

4. Вызвать SpeechKit STT.
5. Добавить тесты.

**Чек-лист готовности:**

- [ ] ASR возвращает текст.
- [ ] `json` и `text` работают.
- [ ] Query-параметры корректны.
- [ ] Есть тесты.

---

### Шаг 7. Нормализация через ffmpeg

**Цель:** стабилизировать входное аудио перед STT.

**Что сделать:**

1. Для каждого ASR-запроса запускать `ffmpeg` через `ProcessBuilder`.
2. Использовать фиксированный пайплайн нормализации:

   - `pcm_s16le`
   - `wav`
   - `target-channels`
   - `target-sample-rate-hertz`

3. Добавить лимиты:

   - размер (`max-input-bytes`)
   - длительность (`max-duration-seconds`)
   - timeout (`timeout-ms`)
   - stderr limit (`max-stderr-bytes`)

4. Чистить временные файлы.
5. Ограничить параллелизм (`concurrency-max-processes`).
6. Добавить тесты.

**Чек-лист готовности:**

- [ ] `ffmpeg` реально вызывается для каждого ASR-запроса.
- [ ] Ошибки маппятся корректно.
- [ ] Временные файлы удаляются.

---

### Шаг 8. Финальная проверка и итог

**Цель:** убедиться, что сервис полностью готов и все ключевые сценарии работают сквозным образом.

**Что сделать:**

1. Запустить полный набор тестов:

```bash
./mvnw test
```

2. Поднять приложение локально и выполнить smoke-проверку:

   - `GET /actuator/health`
   - `POST /v1/audio/speech`
   - `POST /v1/audio/transcriptions` (`json` и `text`)

3. Проверить операционные сценарии:

   - в каждом ответе есть `X-Request-Id`
   - ошибки возвращаются в формате `{ "error": { ... } }`
   - при истекшем IAM-токене после обновления `YANDEX_IAM_TOKEN` запросы снова успешны

4. Зафиксировать результат в кратком отчете (можно в README или отдельном runbook):

   - какие переменные окружения обязательны
   - какие команды запускать для проверки
   - что делать при `401/403`

**Чек-лист готовности:**

- [ ] Все тесты проходят.
- [ ] TTS и ASR успешно работают в ручной проверке.
- [ ] Корреляция и формат ошибок соответствуют ТЗ.
- [ ] IAM-токен обновляется по инструкции и сервис продолжает работать.
- [ ] Есть короткая инструкция запуска и проверки.

Если все пункты выше выполнены, сервис можно считать полностью готовым: он принимает OpenAI-compatible запросы, стабильно проксирует их в SpeechKit и возвращает предсказуемые ответы и ошибки.
