package com.example.ttsserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.ttsserver.config.SpeechKitProperties;
import com.example.ttsserver.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SpeechKitClient {

    private static final Logger log = LoggerFactory.getLogger(SpeechKitClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String TTS_SYNTHESIS_PATH = "/tts/v3/utteranceSynthesis";

    private final RestClient ttsRestClient;
    private final RestClient sttRestClient;
    private final SpeechKitProperties properties;
    private final TokenProvider tokenProvider;

    public SpeechKitClient(RestClient ttsRestClient,
                           RestClient sttRestClient,
                           SpeechKitProperties properties,
                           TokenProvider tokenProvider) {
        this.ttsRestClient = ttsRestClient;
        this.sttRestClient = sttRestClient;
        this.properties = properties;
        this.tokenProvider = tokenProvider;
    }

    public byte[] synthesize(String text, String voice, String lang, Double speed, AudioFormat format) {
        try {
            String requestId = currentRequestId();
            String outputAudioSpecType = outputAudioSpecType(format);
            log.info("Calling TTS upstream request_id={} endpoint={} output_audio_spec_type={} format={}",
                    requestId, TTS_SYNTHESIS_PATH, outputAudioSpecType, format);
            ResponseEntity<String> responseEntity = executeWithAuthRetry(true, () -> ttsRestClient.post()
                    .uri(TTS_SYNTHESIS_PATH)
                    .headers(this::setTtsHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(utteranceSynthesisBody(text, voice, speed, format))
                    .retrieve()
                    .toEntity(String.class));
            String contentType = responseEntity.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            int bodyLength = responseEntity.getBody() == null ? 0 : responseEntity.getBody().length();
            log.info("TTS upstream response received request_id={} endpoint={} status={} content_type={} body_length={}",
                    requestId,
                    TTS_SYNTHESIS_PATH,
                    responseEntity.getStatusCode().value(),
                    contentType,
                    bodyLength);
            Map<?, ?> response = parseJsonObject(responseEntity.getBody(), requestId, contentType);
            return decodeAudioChunk(response, responseEntity.getBody(), requestId, contentType);
        } catch (RestClientResponseException ex) {
            throw mapUpstreamException(ex, "tts");
        } catch (ResourceAccessException ex) {
            if (ex.getCause() instanceof SocketTimeoutException) {
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "Upstream timeout", "server_error", null, "upstream_timeout");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Upstream connection error", "server_error", null, "upstream_error");
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Upstream returned invalid audio payload", "server_error", "tts", "upstream_error");
        }
    }

    public String recognize(byte[] bytes, String filename, String language) {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);

        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename == null ? "audio" : filename;
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, fileHeaders));
        body.add("folderId", properties.getFolderId());
        body.add("lang", language);

        try {
            boolean iamAuth = useIamForStt();
            Map<?, ?> response = executeWithAuthRetry(iamAuth, () -> sttRestClient.post()
                    .uri("/speech/v1/stt:recognize")
                    .headers(this::setSttHeaders)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(Map.class));
            Object result = response == null ? null : response.get("result");
            return result == null ? "" : String.valueOf(result);
        } catch (RestClientResponseException ex) {
            throw mapUpstreamException(ex, "transcription");
        } catch (ResourceAccessException ex) {
            if (ex.getCause() instanceof SocketTimeoutException) {
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "Upstream timeout", "server_error", null, "upstream_timeout");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Upstream connection error", "server_error", null, "upstream_error");
        }
    }

    private ApiException mapUpstreamException(RestClientResponseException ex, String param) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            return new ApiException(HttpStatus.BAD_GATEWAY, "Unexpected upstream status", "server_error", param, "upstream_error");
        }
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            return new ApiException(status, "SpeechKit authentication failed", "authentication_error", param, "auth_error");
        }
        if (status == HttpStatus.PAYLOAD_TOO_LARGE) {
            return new ApiException(status, "Audio file too large", "invalid_request_error", param, "file_too_large");
        }
        if (status == HttpStatus.UNSUPPORTED_MEDIA_TYPE) {
            return new ApiException(status, "Unsupported media type", "invalid_request_error", param, "unsupported_media_type");
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            return new ApiException(status, "Rate limit exceeded", "rate_limit_error", param, "rate_limit_exceeded");
        }
        if (status.is5xxServerError()) {
            return new ApiException(HttpStatus.BAD_GATEWAY, "Upstream service error", "server_error", param, "upstream_error");
        }
        return new ApiException(HttpStatus.BAD_REQUEST, "Upstream rejected request", "invalid_request_error", param, "upstream_bad_request");
    }

    private Map<String, Object> utteranceSynthesisBody(String text, String voice, Double speed, AudioFormat format) {
        List<Map<String, Object>> hints = new ArrayList<>();
        hints.add(Map.of("voice", voice));
        if (speed != null) {
            hints.add(Map.of("speed", speed));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("hints", hints);
        body.put("outputAudioSpec", outputAudioSpec(format));
        return body;
    }

    private Map<String, Object> outputAudioSpec(AudioFormat format) {
        return switch (format) {
            case MP3 -> Map.of("containerAudio", Map.of("containerAudioType", "MP3"));
            case OGG -> Map.of("containerAudio", Map.of("containerAudioType", "OGG_OPUS"));
            case PCM, WAV -> Map.of("rawAudio", Map.of(
                    "audioEncoding", "LINEAR16_PCM",
                    "sampleRateHertz", properties.getSampleRateHertz()
            ));
        };
    }

    private String outputAudioSpecType(AudioFormat format) {
        return switch (format) {
            case MP3, OGG -> "containerAudio";
            case PCM, WAV -> "rawAudio";
        };
    }

    private byte[] decodeAudioChunk(Map<?, ?> response, String rawResponseBody, String requestId, String contentType) {
        log.info("Decoding TTS payload request_id={} endpoint={} debug_log_tts_payload={}",
                requestId, TTS_SYNTHESIS_PATH, properties.isDebugLogTtsPayload());
        PayloadStats stats = PayloadStats.from(response);
        logPayloadDiagnostics("TTS audio payload diagnostics", requestId, contentType, stats);

        if (!(stats.rootMap() instanceof Map<?, ?> rootMap)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Upstream returned unexpected payload", "server_error", "tts", "upstream_error");
        }
        Object audioChunk = rootMap.get("audioChunk");
        if (!(audioChunk instanceof Map<?, ?> chunkMap)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Upstream returned unexpected payload", "server_error", "tts", "upstream_error");
        }
        Object data = chunkMap.get("data");
        if (data == null || String.valueOf(data).isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Upstream returned empty audio payload", "server_error", "tts", "upstream_error");
        }

        String rawData = String.valueOf(data);
        String sanitized = rawData.replaceAll("\\s+", "");
        String normalized = sanitized.replace('-', '+').replace('_', '/');
        int remainder = normalized.length() % 4;
        if (remainder != 0) {
            normalized = normalized + "=".repeat(4 - remainder);
        }
        try {
            return Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException ex) {
            log.warn("Failed to decode TTS audio payload request_id={} endpoint={} content_type={} error={}",
                    requestId, TTS_SYNTHESIS_PATH, contentType, ex.getMessage());
            logPayloadDiagnostics("TTS audio payload diagnostics on decode failure", requestId, contentType, stats);
            if (properties.isDebugLogTtsPayload()) {
                dumpRawUpstreamResponse(rawResponseBody, requestId);
            }
            throw ex;
        }
    }

    private Map<?, ?> parseJsonObject(String rawBody, String requestId, String contentType) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(rawBody, Map.class);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to parse TTS upstream JSON request_id={} endpoint={} content_type={} error={}",
                    requestId, TTS_SYNTHESIS_PATH, contentType, ex.getOriginalMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Upstream returned unexpected payload", "server_error", "tts", "upstream_error");
        }
    }

    private void logPayloadDiagnostics(String message, String requestId, String contentType, PayloadStats stats) {
        log.debug("{} request_id={} endpoint={} content_type={} has_result={} has_audio_chunk={} has_data={} data_length={} data_mod4={} dash_count={} underscore_count={} has_whitespace={}",
                message,
                requestId,
                TTS_SYNTHESIS_PATH,
                contentType,
                stats.hasResult(),
                stats.hasAudioChunk(),
                stats.hasData(),
                stats.dataLength(),
                stats.remainderBeforePadding(),
                stats.dashCount(),
                stats.underscoreCount(),
                stats.hasWhitespace());
        if (properties.isDebugLogTtsPayload() && stats.hasData()) {
            log.debug("TTS payload edge preview request_id={} endpoint={} data_preview={}",
                    requestId, TTS_SYNTHESIS_PATH, stats.maskedPreview());
        }
    }

    private String currentRequestId() {
        String requestId = MDC.get("request_id");
        if (requestId == null || requestId.isBlank()) {
            return "unknown";
        }
        return requestId;
    }

    private void dumpRawUpstreamResponse(String rawResponseBody, String requestId) {
        String safeRequestId = requestId == null || requestId.isBlank()
                ? "unknown"
                : requestId.replaceAll("[^A-Za-z0-9._-]", "_");
        Path path = Path.of("/tmp", "tts-upstream-" + safeRequestId + ".json");
        try {
            String payload = rawResponseBody == null ? "" : rawResponseBody;
            Files.writeString(path, payload, StandardCharsets.UTF_8);
            log.warn("Dumped raw TTS upstream payload request_id={} path={}", requestId, path);
        } catch (IOException ex) {
            log.warn("Failed to dump raw TTS upstream payload request_id={} path={} error={}",
                    requestId, path, ex.getMessage());
        }
    }

    private record PayloadStats(
            Object rootMap,
            boolean hasResult,
            boolean hasAudioChunk,
            boolean hasData,
            int dataLength,
            int remainderBeforePadding,
            int dashCount,
            int underscoreCount,
            boolean hasWhitespace,
            String maskedPreview
    ) {

        static PayloadStats from(Map<?, ?> response) {
            boolean hasResult = false;
            Object root = response;
            if (response instanceof Map<?, ?> rootMap) {
                Object result = rootMap.get("result");
                hasResult = result instanceof Map<?, ?>;
                if (hasResult) {
                    root = result;
                }
            }

            if (!(root instanceof Map<?, ?> map)) {
                return new PayloadStats(null, hasResult, false, false, 0, 0, 0, 0, false, "");
            }

            Object audioChunk = map.get("audioChunk");
            boolean hasAudioChunk = audioChunk instanceof Map<?, ?>;
            if (!hasAudioChunk) {
                return new PayloadStats(map, hasResult, false, false, 0, 0, 0, 0, false, "");
            }

            Map<?, ?> chunkMap = (Map<?, ?>) audioChunk;
            Object dataValue = chunkMap.get("data");
            boolean hasData = dataValue != null;
            if (!hasData) {
                return new PayloadStats(map, hasResult, true, false, 0, 0, 0, 0, false, "");
            }

            String rawData = String.valueOf(dataValue);
            int dashCount = countChar(rawData, '-');
            int underscoreCount = countChar(rawData, '_');
            boolean hasWhitespace = rawData.chars().anyMatch(Character::isWhitespace);
            String sanitized = rawData.replaceAll("\\s+", "");
            int remainderBeforePadding = sanitized.length() % 4;

            return new PayloadStats(
                    map,
                    hasResult,
                    true,
                    true,
                    rawData.length(),
                    remainderBeforePadding,
                    dashCount,
                    underscoreCount,
                    hasWhitespace,
                    maskPayloadEdges(rawData)
            );
        }
    }

    private static int countChar(String value, char ch) {
        return (int) value.chars().filter(c -> c == ch).count();
    }

    private static String maskPayloadEdges(String value) {
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() == 1) {
            return "*";
        }
        int startLength = Math.min(16, value.length() - 1);
        int maxEndLength = Math.max(0, value.length() - startLength - 1);
        int endLength = Math.min(16, maxEndLength);
        String start = value.substring(0, startLength);
        String end = endLength == 0 ? "" : value.substring(value.length() - endLength);
        return start + "..." + end;
    }

    private void setSttHeaders(HttpHeaders headers) {
        if (useApiKeyForStt()) {
            headers.set(HttpHeaders.AUTHORIZATION, "Api-Key " + properties.getApiKey());
            return;
        }
        setIamAuthHeader(headers);
    }

    private void setIamAuthHeader(HttpHeaders headers) {
        headers.setBearerAuth(tokenProvider.getToken());
    }

    private boolean useApiKeyForStt() {
        if (properties.getAuthMode() == SpeechKitProperties.AuthMode.API_KEY) {
            String apiKey = properties.getApiKey();
            return apiKey != null && !apiKey.isBlank();
        }
        return false;
    }

    private boolean useIamForStt() {
        return !useApiKeyForStt();
    }

    private void setTtsHeaders(HttpHeaders headers) {
        setIamAuthHeader(headers);
        headers.set("x-folder-id", properties.getFolderId());
    }

    private <T> T executeWithAuthRetry(boolean iamAuth, UpstreamCall<T> call) {
        int retries = Math.max(0, properties.getMaxRetryOnAuthError());
        int attempt = 0;

        while (true) {
            try {
                return call.call();
            } catch (RestClientResponseException ex) {
                if (iamAuth && isAuthFailure(ex) && attempt < retries) {
                    tokenProvider.forceRefresh();
                    attempt++;
                    continue;
                }
                throw ex;
            }
        }
    }

    private boolean isAuthFailure(RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        return status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value();
    }

    @FunctionalInterface
    private interface UpstreamCall<T> {
        T call();
    }
}
