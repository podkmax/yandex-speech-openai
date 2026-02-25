package com.example.ttsserver.service;

import com.example.ttsserver.config.SpeechKitProperties;
import com.example.ttsserver.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
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

    @Test
    void mapsInvalidPrivateKeyBase64ToConfigError() {
        SpeechKitProperties properties = new SpeechKitProperties();
        properties.setIamTokenUrl(iamServer.url("/iam/v1/tokens").toString());
        properties.setSaKeyJson("{"
                + "\"id\":\"key-id\"," 
                + "\"service_account_id\":\"sa-id\"," 
                + "\"private_key\":\"-----BEGIN PRIVATE KEY-----\\nabc!def\\n-----END PRIVATE KEY-----\""
                + "}");

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
                    assertThat(api.getMessage()).isEqualTo("Service account private_key is invalid (not base64 PEM)");
                });
    }

    @Test
    void acceptsPkcs1RsaPrivateKeyPem() {
        String expiry = Instant.now().plusSeconds(3600).toString();
        iamServer.enqueue(iamTokenResponse("pkcs1-token", expiry));

        SpeechKitProperties properties = new SpeechKitProperties();
        properties.setIamTokenUrl(iamServer.url("/iam/v1/tokens").toString());
        properties.setSaKeyJson("{"
                + "\"id\":\"key-id\"," 
                + "\"service_account_id\":\"sa-id\"," 
                + "\"private_key\":\"" + pkcs1PrivateKeyPem().replace("\n", "\\n") + "\""
                + "}");

        YandexIamTokenProvider provider = new YandexIamTokenProvider(
                properties,
                RestClient.builder().build(),
                new ObjectMapper()
        );

        assertThat(provider.getToken()).isEqualTo("pkcs1-token");
        assertThat(iamServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void acceptsPkcs8PrivateKeyPemWithPrefixLine() throws Exception {
        String expiry = Instant.now().plusSeconds(3600).toString();
        iamServer.enqueue(iamTokenResponse("prefixed-pkcs8-token", expiry));

        SpeechKitProperties properties = new SpeechKitProperties();
        properties.setIamTokenUrl(iamServer.url("/iam/v1/tokens").toString());
        properties.setSaKeyJson(serviceAccountKeyJsonWithPrefixLine());

        YandexIamTokenProvider provider = new YandexIamTokenProvider(
                properties,
                RestClient.builder().build(),
                new ObjectMapper()
        );

        assertThat(provider.getToken()).isEqualTo("prefixed-pkcs8-token");
        RecordedRequest request = iamServer.takeRequest();
        assertThat(request.getBody().readUtf8()).contains("\"jwt\":\"");
        assertThat(iamServer.getRequestCount()).isEqualTo(1);
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

    private String serviceAccountKeyJsonWithPrefixLine() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            String pkcs8 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
            String pem = "PLEASE DO NOT REMOVE THIS LINE! Yandex service account key format warning\n"
                    + "-----BEGIN PRIVATE KEY-----\n"
                    + pkcs8
                    + "\n-----END PRIVATE KEY-----";
            return "{"
                    + "\"id\":\"key-id\","
                    + "\"service_account_id\":\"sa-id\","
                    + "\"private_key\":\"" + pem.replace("\n", "\\n") + "\""
                    + "}";
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String pkcs1PrivateKeyPem() {
        return """
                -----BEGIN RSA PRIVATE KEY-----
                MIIEowIBAAKCAQEAkcrNTjP7qEsm2AABQHdD3Y/aXBcvthrs4B5gB2QUtgPHVsVI
                Wsw1wEYOGtorGDhEYQzivK2c0U/tXF51JVZHtxeK/l6teqQZBEtE9zj9jJiKcmdf
                u9XyDB1Ubdts+xDigcdnSer9vhNnsua1UWcJ55RRV90/8KsFGqJ7syrPKRis94qQ
                Aa6B1IDfA2gxv/rf8jzCfiQV5VmS9tb+C5RU74Q2zoY4ZK6mn84n6VFX+xtbW1GI
                1XKT7y31w7mIYlBC0FogI+VM7EMT3my0lgPofTVkkm7JfU21ZZnvtFVUByn+i8yx
                N0p0vaQqDVKTjhgPzBoRhu1bDGhQBDzP69cK0wIDAQABAoIBAAl4CwKPxMIG84Rm
                563osRs0SyHdDae9svHRdzozqVazAyDjGlJvXfRZeHQNBGyDxwkonZdUjVFY6Bku
                N1yP8IN3P3tK6eiDvO4290k5Rdp6U8+fYCviduCLjR6/eCIMVDHKoi4+WXGdcAhC
                DLe3QgDs9KWIxKzcZq32rKMT3jWUgA81IBeKJB1K0YGEqJXQRNCe6jaAXqOFcZwN
                hhu5dqRFmWzUMY10SVuSBJjKcSxOrbxGYauiG4rkx1yYyqjEMqIPMxmirRFaqN+W
                XlRNmeBUVUuSc2LYeqeWUtNQb6Oy0f1KcasIYPpR4H0hHX7G7gK5+KE+NcHnxmHp
                Y6/HRTUCgYEAzUkHJAk/rHlp9TmL5PZem141UpLhONLjNwPaWqbsta3tLdSslQvE
                46xwWIjaY/w8Kh6DvhdNst8HIXJc85Y9/oohZE6mUiavv8vU0j9Gx0+HACK/RpMM
                xxKSpDxV/of/hKgv65IhwWrLUYjxQCDRV2hryjppV7bk6U11lbJLhg0CgYEAtc84
                X/3zvanSNi4rcTxuyrRXnkOya8TIRDkLuXsdLiR/Bqou5foqwfeGGJUtjOUb/qE0
                wrZ1hVB8Vnm1ZcL33q1B1fmla+ZHn9adqbQdjwEaOMcmNOvit2jU7haE+aZaCcZ6
                GhZ3VlSCfdIUPqdhE2gpnGs6fOBxQC5Tp/nqfF8CgYAgJ1UX/t7bS/UdtNLFnRU6
                bqoZceoW9WkjX9YeptCisEhbClmxyrMfGg5Kv7y9Nm/SBQ+Lgajo4GgEhB4tBRZW
                vRn31R3V9jtG5k1CVSjn5Pv3OHoPOs8gizcuxEiP4otSIunGkw/4dJq9/Z4T9k/z
                yeZAOZ/wXjRCmqBVPyT3LQKBgE9WgTxqfs36aJBaL3z3qUVt03puAlNDCT20QyiK
                0B4NsR0AcPzM4ZHJaUwa9Uixxjiksnhx1PD7QXcfH4irvyz+IGe2zHg8gm7+4chn
                oCqCiaXTShn4AfSVm63WR+sFq+7uHOR7f1I+CL/NSCZbNmKYpufqZxiNfP5L+Rep
                WKkzAoGBAI5vjr/mQxtdPmDQjWiREM1sSg4ThAZ4fWOT/cwqCmJVZQku2SpVdsLG
                XP7XD14Oy4LK5GIw8MswFsInCvcv7Zp6Wv0L6fpyW8atsOIsvRT+a4WhbQIlFacU
                9jqlWj2g17h63ujDD/xTQzrA4Ia8rshxQ8667OOUGRk7VVXNprFa
                -----END RSA PRIVATE KEY-----
                """;
    }
}
