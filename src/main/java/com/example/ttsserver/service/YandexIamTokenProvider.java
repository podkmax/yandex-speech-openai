package com.example.ttsserver.service;

import com.example.ttsserver.config.SpeechKitProperties;
import com.example.ttsserver.error.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class YandexIamTokenProvider implements TokenProvider {

    private static final TypeReference<Map<String, String>> MAP_OF_STRING = new TypeReference<>() {
    };

    private final SpeechKitProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final TokenSource source;
    private final ServiceAccountKey serviceAccountKey;

    private volatile TokenSnapshot cachedToken;

    @Autowired
    public YandexIamTokenProvider(SpeechKitProperties properties, RestClient iamRestClient, ObjectMapper objectMapper) {
        this(properties, iamRestClient, objectMapper, Clock.systemUTC());
    }

    YandexIamTokenProvider(SpeechKitProperties properties, RestClient restClient, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.source = resolveSource(properties);
        this.serviceAccountKey = loadServiceAccountKeyIfNeeded();
    }

    @Override
    public String getToken() {
        if (source == TokenSource.STATIC) {
            return requireStaticToken();
        }

        Instant now = clock.instant();
        TokenSnapshot snapshot = cachedToken;
        if (snapshot != null && !shouldRefresh(snapshot.expiresAt(), now)) {
            return snapshot.value();
        }
        return refreshSingleflight(false);
    }

    @Override
    public void forceRefresh() {
        if (source == TokenSource.STATIC) {
            return;
        }
        refreshSingleflight(true);
    }

    private String refreshSingleflight(boolean force) {
        refreshLock.lock();
        try {
            Instant now = clock.instant();
            TokenSnapshot snapshot = cachedToken;
            if (!force && snapshot != null && !shouldRefresh(snapshot.expiresAt(), now)) {
                return snapshot.value();
            }

            TokenSnapshot refreshed = refreshWithRetry();
            cachedToken = refreshed;
            return refreshed.value();
        } finally {
            refreshLock.unlock();
        }
    }

    private TokenSnapshot refreshWithRetry() {
        ApiException lastTemporary = null;
        int attempts = Math.max(1, properties.getTokenRefreshRetryAttempts());

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return fetchToken();
            } catch (ApiException ex) {
                if (!isTemporary(ex) || attempt == attempts) {
                    throw ex;
                }
                lastTemporary = ex;
                sleepWithBackoff(attempt);
            }
        }
        if (lastTemporary != null) {
            throw lastTemporary;
        }
        throw temporaryError();
    }

    private TokenSnapshot fetchToken() {
        return switch (source) {
            case SERVICE_ACCOUNT -> exchangeServiceAccountJwt();
            case METADATA -> fetchFromMetadataService();
            case STATIC -> new TokenSnapshot(requireStaticToken(), Instant.MAX);
            case NONE -> throw configError("IAM token source is not configured");
        };
    }

    private TokenSnapshot exchangeServiceAccountJwt() {
        String jwt;
        try {
            jwt = createServiceAccountJwt(serviceAccountKey);
        } catch (GeneralSecurityException ex) {
            throw configError("Failed to sign IAM exchange JWT");
        }

        try {
            Map<?, ?> response = restClient.post()
                    .uri(properties.getIamTokenUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("jwt", jwt))
                    .retrieve()
                    .body(Map.class);
            return parseTokenResponse(response, "iamToken", "expiresAt");
        } catch (RestClientResponseException ex) {
            throw mapAuthUpstreamException(ex);
        } catch (ResourceAccessException ex) {
            throw temporaryError();
        }
    }

    private TokenSnapshot fetchFromMetadataService() {
        try {
            Map<?, ?> response = restClient.get()
                    .uri(properties.getIamMetadataUrl())
                    .header("Metadata-Flavor", "Google")
                    .retrieve()
                    .body(Map.class);

            String token = firstString(response, "iamToken", "access_token");
            Instant expiresAt = parseExpiresAt(response, "expiresAt", "expires_at", "expiresIn", "expires_in");
            return new TokenSnapshot(token, expiresAt);
        } catch (RestClientResponseException ex) {
            throw mapAuthUpstreamException(ex);
        } catch (ResourceAccessException ex) {
            throw temporaryError();
        }
    }

    private TokenSnapshot parseTokenResponse(Map<?, ?> response, String tokenField, String expiresAtField) {
        String token = firstString(response, tokenField, "iam_token", "access_token");
        Instant expiresAt = parseExpiresAt(response, expiresAtField, "expires_at", "expiresIn");
        return new TokenSnapshot(token, expiresAt);
    }

    private Instant parseExpiresAt(Map<?, ?> response, String... keys) {
        if (response == null) {
            throw temporaryError();
        }
        for (String key : keys) {
            Object value = response.get(key);
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return Instant.parse(text);
                } catch (RuntimeException ignored) {
                    if (text.matches("\\d+")) {
                        long seconds = Long.parseLong(text);
                        return clock.instant().plusSeconds(seconds);
                    }
                }
            }
            if (value instanceof Number number) {
                return clock.instant().plusSeconds(number.longValue());
            }
        }
        throw temporaryError();
    }

    private String firstString(Map<?, ?> response, String... keys) {
        if (response == null) {
            throw temporaryError();
        }
        for (String key : keys) {
            Object value = response.get(key);
            if (value != null) {
                String text = String.valueOf(value);
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        throw temporaryError();
    }

    private String createServiceAccountJwt(ServiceAccountKey key) throws GeneralSecurityException {
        Instant now = clock.instant();
        long iat = now.getEpochSecond();
        long exp = now.plusSeconds(360).getEpochSecond();

        String headerJson = toJson(Map.of("alg", "PS256", "typ", "JWT", "kid", key.id()));
        String payloadJson = toJson(Map.of(
                "aud", properties.getIamTokenUrl(),
                "iss", key.serviceAccountId(),
                "iat", iat,
                "exp", exp,
                "jti", UUID.randomUUID().toString()
        ));

        String encodedHeader = base64Url(headerJson.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = encodedHeader + "." + encodedPayload;

        Signature signature = Signature.getInstance("RSASSA-PSS");
        signature.setParameter(new java.security.spec.PSSParameterSpec(
                "SHA-256",
                "MGF1",
                java.security.spec.MGF1ParameterSpec.SHA256,
                32,
                1
        ));
        signature.initSign(readPrivateKey(key.privateKeyPem()));
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] signed = signature.sign();

        return signingInput + "." + base64Url(signed);
    }

    private PrivateKey readPrivateKey(String pem) throws GeneralSecurityException {
        String normalized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] pkcs8 = Base64.getDecoder().decode(normalized);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to build JWT payload", ex);
        }
    }

    private String requireStaticToken() {
        String token = properties.getIamToken();
        if (token == null || token.isBlank()) {
            throw configError("YANDEX_IAM_TOKEN is not set");
        }
        return token;
    }

    private boolean shouldRefresh(Instant expiresAt, Instant now) {
        Instant refreshAt = expiresAt.minusSeconds(Math.max(0, properties.getTokenSkewSeconds()));
        if (!now.isBefore(refreshAt)) {
            return true;
        }
        long ttl = expiresAt.getEpochSecond() - now.getEpochSecond();
        return ttl < properties.getTokenMinTtlSeconds();
    }

    private void sleepWithBackoff(int attempt) {
        long base = Math.max(0, properties.getTokenRefreshRetryBaseMillis());
        long max = Math.max(base, properties.getTokenRefreshRetryMaxMillis());
        long exp = base == 0 ? 0 : base * (1L << Math.max(0, attempt - 1));
        long bounded = Math.min(max, exp);
        double jitter = ThreadLocalRandom.current().nextDouble(0.5d, 1.5d);
        long sleep = Math.max(0, Math.min(max, (long) (bounded * jitter)));
        if (sleep == 0) {
            return;
        }
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw temporaryError();
        }
    }

    private ServiceAccountKey loadServiceAccountKeyIfNeeded() {
        if (source != TokenSource.SERVICE_ACCOUNT) {
            return null;
        }
        String saKeyJson = readSaKeyJson();
        try {
            Map<String, String> keyMap = objectMapper.readValue(saKeyJson, MAP_OF_STRING);
            String id = trimToNull(keyMap.get("id"));
            String serviceAccountId = trimToNull(keyMap.get("service_account_id"));
            String privateKey = trimToNull(keyMap.get("private_key"));
            if (id == null || serviceAccountId == null || privateKey == null) {
                throw configError("Service account key JSON is missing required fields");
            }
            return new ServiceAccountKey(id, serviceAccountId, privateKey);
        } catch (IOException ex) {
            throw configError("Service account key JSON is invalid");
        }
    }

    private String readSaKeyJson() {
        String fromFile = trimToNull(properties.getSaKeyFile());
        if (fromFile != null) {
            try {
                return Files.readString(Path.of(fromFile), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw configError("Unable to read service account key file");
            }
        }
        String fromRaw = trimToNull(properties.getSaKeyJson());
        if (fromRaw != null) {
            return fromRaw;
        }
        throw configError("Service account key is not configured");
    }

    private TokenSource resolveSource(SpeechKitProperties p) {
        if (trimToNull(p.getSaKeyFile()) != null || trimToNull(p.getSaKeyJson()) != null) {
            return TokenSource.SERVICE_ACCOUNT;
        }
        if (trimToNull(p.getIamToken()) != null) {
            return TokenSource.STATIC;
        }
        if (p.isIamMetadataEnabled()) {
            return TokenSource.METADATA;
        }
        return TokenSource.NONE;
    }

    private ApiException mapAuthUpstreamException(RestClientResponseException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            return temporaryError();
        }
        if (status.is5xxServerError() || status == HttpStatus.TOO_MANY_REQUESTS || status == HttpStatus.REQUEST_TIMEOUT) {
            return temporaryError();
        }
        return configError("IAM authentication is misconfigured or rejected");
    }

    private boolean isTemporary(ApiException ex) {
        return ex.getStatus() == HttpStatus.SERVICE_UNAVAILABLE;
    }

    private ApiException temporaryError() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Temporarily unable to obtain IAM token",
                "server_error",
                null,
                "upstream_auth_temporary_error"
        );
    }

    private ApiException configError(String message) {
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                message,
                "server_error",
                null,
                "upstream_auth_config_error"
        );
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private enum TokenSource {
        SERVICE_ACCOUNT,
        STATIC,
        METADATA,
        NONE
    }

    private record TokenSnapshot(String value, Instant expiresAt) {
    }

    private record ServiceAccountKey(String id, String serviceAccountId, String privateKeyPem) {
    }
}
