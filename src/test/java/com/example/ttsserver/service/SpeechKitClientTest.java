package com.example.ttsserver.service;

import com.example.ttsserver.config.SpeechKitProperties;
import com.example.ttsserver.error.ApiException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeechKitClientTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void mapsUnauthorizedFromUpstream() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));

        SpeechKitClient client = new SpeechKitClient(
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                testProperties(),
                new FixedTokenProvider("iam-token")
        );

        assertThatThrownBy(() -> client.synthesize("hello", "alena", "ru-RU", null, AudioFormat.MP3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(api.getCode()).isEqualTo("auth_error");
                });
    }

    @Test
    void sendsTtsV3RequestAndDecodesAudioChunk() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"result\":{\"audioChunk\":{\"data\":\"" + encoded + "\"}}}"));

        SpeechKitClient client = new SpeechKitClient(
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                testProperties(),
                new FixedTokenProvider("iam-token")
        );

        byte[] bytes = client.synthesize("hello", "masha", "ru-RU", 1.1, AudioFormat.MP3);
        assertThat(bytes).containsExactly((byte) 1, (byte) 2, (byte) 3);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/tts/v3/utteranceSynthesis");
        assertThat(request.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer iam-token");
        assertThat(request.getHeader("x-folder-id")).isEqualTo("folder");
        assertThat(request.getBody().readUtf8()).contains("\"voice\":\"masha\"")
                .contains("\"containerAudioType\":\"MP3\"")
                .contains("\"speed\":1.1");
    }

    @Test
    void includesRoleAndPitchShiftHintsInTtsV3Request() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"result\":{\"audioChunk\":{\"data\":\"" + encoded + "\"}}}"));

        SpeechKitClient client = new SpeechKitClient(
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                testProperties(),
                new FixedTokenProvider("iam-token")
        );

        byte[] bytes = client.synthesize("hello", "masha", "ru-RU", null, "friendly", 120.0, AudioFormat.MP3);
        assertThat(bytes).containsExactly((byte) 1, (byte) 2, (byte) 3);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getBody().readUtf8()).contains("\"voice\":\"masha\"")
                .contains("\"role\":\"friendly\"")
                .contains("\"pitchShift\":120.0")
                .doesNotContain("\"speed\"");
    }

    @Test
    void decodesUnpaddedAudioChunkData() {
        String unpadded = "SUQzBA";
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"result\":{\"audioChunk\":{\"data\":\"" + unpadded + "\"}}}"));

        SpeechKitClient client = new SpeechKitClient(
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                testProperties(),
                new FixedTokenProvider("iam-token")
        );

        byte[] bytes = client.synthesize("hello", "masha", "ru-RU", null, AudioFormat.MP3);
        assertThat(bytes).hasSizeGreaterThanOrEqualTo(3);
        assertThat(bytes[0]).isEqualTo((byte) 'I');
        assertThat(bytes[1]).isEqualTo((byte) 'D');
        assertThat(bytes[2]).isEqualTo((byte) '3');
    }

    @Test
    void parsesSttResult() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"result\":\"ok text\"}"));

        SpeechKitClient client = new SpeechKitClient(
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                testProperties(),
                new FixedTokenProvider("iam-token")
        );

        String result = client.recognize("abc".getBytes(), "a.wav", "ru-RU", null, null);
        assertThat(result).isEqualTo("ok text");

        RecordedRequest request;
        try {
            request = server.takeRequest(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer iam-token");
        assertThat(request.getHeader(HttpHeaders.CONTENT_TYPE))
                .startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .doesNotContain("multipart/form-data");
        assertThat(request.getBody().readByteArray()).containsExactly("abc".getBytes());

        URI uri = URI.create("http://localhost" + request.getPath());
        Map<String, String> params = queryParams(uri.getRawQuery());
        assertThat(uri.getPath()).isEqualTo("/speech/v1/stt:recognize");
        assertThat(params).containsEntry("folderId", "folder");
        assertThat(params).containsEntry("lang", "ru-RU");
    }

    @Test
    void sttUsesApiKeyHeaderWhenConfigured() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"result\":\"ok text\"}"));

        SpeechKitProperties properties = testProperties();
        properties.setAuthMode(SpeechKitProperties.AuthMode.API_KEY);
        properties.setApiKey("stt-api-key");

        SpeechKitClient client = new SpeechKitClient(
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                properties,
                new FixedTokenProvider("iam-token")
        );

        String result = client.recognize("abc".getBytes(), "a.wav", "ru-RU", null, null);
        assertThat(result).isEqualTo("ok text");

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Api-Key stt-api-key");
    }

    @Test
    void sttAddsFormatAndSampleRateQueryParamsWhenProvided() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"result\":\"ok text\"}"));

        SpeechKitClient client = new SpeechKitClient(
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                testProperties(),
                new FixedTokenProvider("iam-token")
        );

        String result = client.recognize("abc".getBytes(), "audio.wav", "ru-RU", "lpcm", 48000);
        assertThat(result).isEqualTo("ok text");

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        URI uri = URI.create("http://localhost" + request.getPath());
        Map<String, String> params = queryParams(uri.getRawQuery());
        assertThat(params).containsEntry("format", "lpcm");
        assertThat(params).containsEntry("sampleRateHertz", "48000");
    }

    @Test
    void retriesOnceAfterAuthErrorAndRefreshesToken() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        server.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"result\":{\"audioChunk\":{\"data\":\"" + encoded + "\"}}}"));

        SpeechKitProperties properties = testProperties();
        properties.setMaxRetryOnAuthError(1);

        RotatingTokenProvider tokenProvider = new RotatingTokenProvider("expired-token", "fresh-token");

        SpeechKitClient client = new SpeechKitClient(
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                properties,
                tokenProvider
        );

        byte[] bytes = client.synthesize("hello", "masha", "ru-RU", null, AudioFormat.MP3);
        assertThat(bytes).containsExactly((byte) 1, (byte) 2, (byte) 3);

        RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest second = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer expired-token");
        assertThat(second.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer fresh-token");
        assertThat(tokenProvider.refreshCalls()).isEqualTo(1);
    }

    @Test
    void dumpsRawUpstreamPayloadOnDecodeFailureWhenDebugEnabled() throws Exception {
        String requestId = "test-decode-failure";
        Path dumpPath = Path.of("/tmp", "tts-upstream-" + requestId + ".json");
        Files.deleteIfExists(dumpPath);
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"result\":{\"audioChunk\":{\"data\":\"%%%\"}}}"));

        SpeechKitProperties properties = testProperties();
        properties.setDebugLogTtsPayload(true);

        SpeechKitClient client = new SpeechKitClient(
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                properties,
                new FixedTokenProvider("iam-token")
        );

        MDC.put("request_id", requestId);
        try {
            assertThatThrownBy(() -> client.synthesize("hello", "masha", "ru-RU", null, AudioFormat.MP3))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException api = (ApiException) ex;
                        assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                        assertThat(api.getCode()).isEqualTo("upstream_error");
                    });

            assertThat(Files.exists(dumpPath)).isTrue();
            assertThat(Files.readString(dumpPath)).contains("\"data\":\"%%%\"");
        } finally {
            MDC.remove("request_id");
            Files.deleteIfExists(dumpPath);
        }
    }

    @Test
    void mapsUnexpectedRuntimeExceptionDuringTtsCallToBadGateway() throws Exception {
        String requestId = "test-runtime-" + UUID.randomUUID();
        SpeechKitClient client = new SpeechKitClient(
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                RestClient.builder().baseUrl(server.url("/").toString()).build(),
                testProperties(),
                new ThrowingTokenProvider(new IllegalArgumentException("token provider failed"))
        );

        MDC.put("request_id", requestId);
        try {
            assertThatThrownBy(() -> client.synthesize("hello", "masha", "ru-RU", null, AudioFormat.MP3))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException api = (ApiException) ex;
                        assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                        assertThat(api.getCode()).isEqualTo("upstream_error");
                        assertThat(api.getMessage()).isEqualTo("Upstream service error");
                    });
            assertThat(server.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            MDC.remove("request_id");
        }
    }

    private SpeechKitProperties testProperties() {
        SpeechKitProperties p = new SpeechKitProperties();
        p.setAuthMode(SpeechKitProperties.AuthMode.IAM);
        p.setFolderId("folder");
        p.setMaxRetryOnAuthError(0);
        return p;
    }

    private Map<String, String> queryParams(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = kv[0];
            String value = kv.length > 1 ? kv[1] : "";
            params.put(key, value);
        }
        return params;
    }

    private static final class FixedTokenProvider implements TokenProvider {

        private final String token;

        private FixedTokenProvider(String token) {
            this.token = token;
        }

        @Override
        public String getToken() {
            return token;
        }

        @Override
        public void forceRefresh() {
        }
    }

    private static final class RotatingTokenProvider implements TokenProvider {

        private final String initialToken;
        private final String refreshedToken;
        private final AtomicInteger refreshCalls = new AtomicInteger();

        private RotatingTokenProvider(String initialToken, String refreshedToken) {
            this.initialToken = initialToken;
            this.refreshedToken = refreshedToken;
        }

        @Override
        public String getToken() {
            return refreshCalls.get() > 0 ? refreshedToken : initialToken;
        }

        @Override
        public void forceRefresh() {
            refreshCalls.incrementAndGet();
        }

        int refreshCalls() {
            return refreshCalls.get();
        }
    }

    private static final class ThrowingTokenProvider implements TokenProvider {

        private final RuntimeException ex;

        private ThrowingTokenProvider(RuntimeException ex) {
            this.ex = ex;
        }

        @Override
        public String getToken() {
            throw ex;
        }

        @Override
        public void forceRefresh() {
        }
    }
}
