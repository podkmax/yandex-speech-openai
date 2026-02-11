# TTS/ASR Proxy (OpenAI-compatible) over Yandex SpeechKit

Spring Boot 3.5 / Java 21 MVP service that exposes:

- `POST /v1/audio/speech`
- `POST /v1/audio/transcriptions`

## Requirements

- Java 21+
- Yandex Cloud SpeechKit credentials

## Environment variables

Required:

- `YANDEX_FOLDER_ID=<your-folder-id>`

IAM token source priority (highest first):

1. Service account authorized key JSON:
   - `YANDEX_SA_KEY_FILE=/absolute/path/to/key.json` (recommended for local dev)
   - or `YANDEX_SA_KEY_JSON='{"id":"...","service_account_id":"...","private_key":"..."}'`
2. Static token: `YANDEX_IAM_TOKEN=<iam-token>`
3. Metadata service (when running in YC VM): `YANDEX_IAM_METADATA_ENABLED=true`

Optional:

- `DEFAULT_VOICE=alena`
- `DEFAULT_LANGUAGE=ru-RU`
- `COMPAT_STRICT=false`
- `MAX_FILE_SIZE=10MB`
- `MAX_REQUEST_SIZE=10MB`
- `UPSTREAM_CONNECT_TIMEOUT=5s`
- `UPSTREAM_READ_TIMEOUT=30s`
- `DEBUG_LOG_TTS_PAYLOAD=false`
- `YANDEX_SPEECHKIT_AUTH_MODE=iam` (preferred, default)
- `YANDEX_API_KEY=<api-key>` (optional, only for STT v1 when `auth_mode=api_key`)
- `YANDEX_IAM_TOKEN_URL=https://iam.api.cloud.yandex.net/iam/v1/tokens`
- `YANDEX_IAM_METADATA_URL=http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token`
- `YANDEX_IAM_TOKEN_SKEW_SECONDS=60`
- `YANDEX_IAM_MIN_TTL_SECONDS=120`
- `YANDEX_IAM_REFRESH_RETRY_BASE_MILLIS=200`
- `YANDEX_IAM_REFRESH_RETRY_MAX_MILLIS=3000`
- `YANDEX_IAM_REFRESH_RETRY_ATTEMPTS=3`
- `YANDEX_IAM_MAX_RETRY_ON_AUTH_ERROR=1`

## Build and test

```bash
./mvnw test
```

```bash
./mvnw package
```

## Run

```bash
YANDEX_SA_KEY_FILE=/absolute/path/to/authorized_key.json \
YANDEX_FOLDER_ID=yyy \
./mvnw spring-boot:run
```

Health check:

```bash
curl -s http://localhost:8081/actuator/health
```

## API examples

### TTS

```bash
curl -X POST http://localhost:8081/v1/audio/speech \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: demo-tts-1" \
  -d '{
    "model":"gpt-4o-mini-tts",
    "input":"Privet! Eto proverka TTS proxy.",
    "voice":"alloy",
    "response_format":"mp3",
    "speed":1.0
  }' \
  --output speech.mp3
```

### ASR

```bash
curl -X POST http://localhost:8081/v1/audio/transcriptions \
  -H "X-Request-Id: demo-asr-1" \
  -F file=@sample.wav \
  -F model=whisper-1 \
  -F language=ru-RU \
  -F response_format=json
```

Text format:

```bash
curl -X POST http://localhost:8081/v1/audio/transcriptions \
  -F file=@sample.wav \
  -F model=whisper-1 \
  -F response_format=text
```

## Notes

- SSE streaming (`stream_format=sse`) is intentionally not supported in MVP.
- Unsupported ASR fields are ignored by default; set `COMPAT_STRICT=true` to return `400` for unknown fields.
- TTS is routed to SpeechKit API v3 REST endpoint (`/tts/v3/utteranceSynthesis`), while ASR remains on v1.
- TTS always uses IAM token; in `api_key` mode ASR v1 can still use API key if `YANDEX_API_KEY` is set.
- On SpeechKit `401/403`, IAM token is refreshed and request is retried once.
- `voice=alloy` maps to SpeechKit voice `masha` by default.
- To troubleshoot recurring `502 Upstream returned invalid audio payload`, set `DEBUG_LOG_TTS_PAYLOAD=true`.
- With debug enabled, logs include safe TTS payload diagnostics (`request_id`, field presence, base64 stats, masked first/last 16 chars).
- On TTS base64 decode failure and debug enabled, raw upstream JSON is dumped to `/tmp/tts-upstream-<request_id>.json`.
- Share `request_id`, related log lines, and `/tmp/tts-upstream-<request_id>.json` file contents for investigation (never share tokens/Authorization headers).
- Error responses use OpenAI-style envelope:

```json
{
  "error": {
    "message": "...",
    "type": "invalid_request_error",
    "param": "...",
    "code": "..."
  }
}
```
