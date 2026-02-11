package com.example.ttsserver.service;

import com.example.ttsserver.config.SpeechKitProperties;
import com.example.ttsserver.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YandexIamTokenProviderTest {

    private MockWebServer iamServer;

    @BeforeEach
    void setUp() throws Exception {
        iamServer = new MockWebServer();
        iamServer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        iamServer.shutdown();
    }

    @Test
    void cachesTokenAndRefreshesWhenTtlIsTooLow() {
        String firstExpiry = Instant.now().plusSeconds(70).toString();
        String secondExpiry = Instant.now().plusSeconds(3600).toString();
        iamServer.enqueue(iamTokenResponse("first-token", firstExpiry));
        iamServer.enqueue(iamTokenResponse("second-token", secondExpiry));

        SpeechKitProperties properties = basePropertiesWithSaKey();
        properties.setTokenSkewSeconds(0);
        properties.setTokenMinTtlSeconds(120);

        YandexIamTokenProvider provider = new YandexIamTokenProvider(
                properties,
                RestClient.builder().build(),
                new ObjectMapper()
        );

        assertThat(provider.getToken()).isEqualTo("first-token");
        assertThat(provider.getToken()).isEqualTo("second-token");
        assertThat(iamServer.getRequestCount()).isEqualTo(2);
    }

    @Test
    void singleflightRefreshMakesOnlyOneIamCall() throws Exception {
        String expiry = Instant.now().plusSeconds(3600).toString();
        iamServer.enqueue(iamTokenResponse("shared-token", expiry));

        SpeechKitProperties properties = basePropertiesWithSaKey();
        properties.setTokenSkewSeconds(0);
        properties.setTokenMinTtlSeconds(0);

        YandexIamTokenProvider provider = new YandexIamTokenProvider(
                properties,
                RestClient.builder().build(),
                new ObjectMapper()
        );

        int workers = 12;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<String>> futures = java.util.stream.IntStream.range(0, workers)
                    .mapToObj(i -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return provider.getToken();
                    }))
                    .toList();

            ready.await();
            start.countDown();

            for (Future<String> future : futures) {
                assertThat(future.get()).isEqualTo("shared-token");
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(iamServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void mapsInvalidCredentialsToConfigError() {
        iamServer.enqueue(new MockResponse().setResponseCode(400).setBody("bad request"));

        SpeechKitProperties properties = basePropertiesWithSaKey();
        YandexIamTokenProvider provider = new YandexIamTokenProvider(
                properties,
                RestClient.builder().build(),
                new ObjectMapper()
        );

        assertThatThrownBy(provider::getToken)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(api.getCode()).isEqualTo("upstream_auth_config_error");
                });
    }

    private MockResponse iamTokenResponse(String token, String expiresAt) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"iamToken\":\"" + token + "\",\"expiresAt\":\"" + expiresAt + "\"}");
    }

    private SpeechKitProperties basePropertiesWithSaKey() {
        SpeechKitProperties p = new SpeechKitProperties();
        p.setIamTokenUrl(iamServer.url("/iam/v1/tokens").toString());
        p.setSaKeyJson(serviceAccountKeyJson());
        return p;
    }

    private String serviceAccountKeyJson() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            String pkcs8 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
            String pem = "-----BEGIN PRIVATE KEY-----\n" + pkcs8 + "\n-----END PRIVATE KEY-----";
            return "{"
                    + "\"id\":\"key-id\"," 
                    + "\"service_account_id\":\"sa-id\"," 
                    + "\"private_key\":\"" + pem.replace("\n", "\\n") + "\""
                    + "}";
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
