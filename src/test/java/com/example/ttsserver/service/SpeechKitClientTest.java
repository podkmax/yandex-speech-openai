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
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
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

        String result = client.recognize("abc".getBytes(), "a.wav", "ru-RU");
        assertThat(result).isEqualTo("ok text");
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

    private SpeechKitProperties testProperties() {
        SpeechKitProperties p = new SpeechKitProperties();
        p.setAuthMode(SpeechKitProperties.AuthMode.IAM);
        p.setFolderId("folder");
        p.setMaxRetryOnAuthError(0);
        return p;
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
}
