package com.example.ttsserver.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "app.speechkit")
public class SpeechKitProperties {

    @NotBlank
    private String baseUrl = "https://tts.api.cloud.yandex.net";

    @NotBlank
    private String sttBaseUrl = "https://stt.api.cloud.yandex.net";

    @NotBlank
    private String folderId;

    @NotNull
    private AuthMode authMode = AuthMode.IAM;

    private String apiKey;

    private String iamToken;

    private String saKeyFile;

    private String saKeyJson;

    @NotBlank
    private String iamTokenUrl = "https://iam.api.cloud.yandex.net/iam/v1/tokens";

    private boolean iamMetadataEnabled;

    @NotBlank
    private String iamMetadataUrl = "http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token";

    @Min(0)
    private int tokenSkewSeconds = 60;

    @Min(0)
    private int tokenMinTtlSeconds = 120;

    @Min(0)
    private int tokenRefreshRetryBaseMillis = 200;

    @Min(0)
    private int tokenRefreshRetryMaxMillis = 3000;

    @Min(1)
    private int tokenRefreshRetryAttempts = 3;

    @Min(0)
    private int maxRetryOnAuthError = 1;

    @NotBlank
    private String defaultVoice = "alena";

    @NotBlank
    private String defaultLanguage = "ru-RU";

    private Map<String, String> voiceMapping = new HashMap<>();

    @Min(8000)
    private int sampleRateHertz = 48000;

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(5);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(30);

    private boolean debugLogTtsPayload;

    public enum AuthMode {
        API_KEY,
        IAM
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSttBaseUrl() {
        return sttBaseUrl;
    }

    public void setSttBaseUrl(String sttBaseUrl) {
        this.sttBaseUrl = sttBaseUrl;
    }

    public String getFolderId() {
        return folderId;
    }

    public void setFolderId(String folderId) {
        this.folderId = folderId;
    }

    public AuthMode getAuthMode() {
        return authMode;
    }

    public void setAuthMode(AuthMode authMode) {
        this.authMode = authMode;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getIamToken() {
        return iamToken;
    }

    public void setIamToken(String iamToken) {
        this.iamToken = iamToken;
    }

    public String getSaKeyFile() {
        return saKeyFile;
    }

    public void setSaKeyFile(String saKeyFile) {
        this.saKeyFile = saKeyFile;
    }

    public String getSaKeyJson() {
        return saKeyJson;
    }

    public void setSaKeyJson(String saKeyJson) {
        this.saKeyJson = saKeyJson;
    }

    public String getIamTokenUrl() {
        return iamTokenUrl;
    }

    public void setIamTokenUrl(String iamTokenUrl) {
        this.iamTokenUrl = iamTokenUrl;
    }

    public boolean isIamMetadataEnabled() {
        return iamMetadataEnabled;
    }

    public void setIamMetadataEnabled(boolean iamMetadataEnabled) {
        this.iamMetadataEnabled = iamMetadataEnabled;
    }

    public String getIamMetadataUrl() {
        return iamMetadataUrl;
    }

    public void setIamMetadataUrl(String iamMetadataUrl) {
        this.iamMetadataUrl = iamMetadataUrl;
    }

    public int getTokenSkewSeconds() {
        return tokenSkewSeconds;
    }

    public void setTokenSkewSeconds(int tokenSkewSeconds) {
        this.tokenSkewSeconds = tokenSkewSeconds;
    }

    public int getTokenMinTtlSeconds() {
        return tokenMinTtlSeconds;
    }

    public void setTokenMinTtlSeconds(int tokenMinTtlSeconds) {
        this.tokenMinTtlSeconds = tokenMinTtlSeconds;
    }

    public int getTokenRefreshRetryBaseMillis() {
        return tokenRefreshRetryBaseMillis;
    }

    public void setTokenRefreshRetryBaseMillis(int tokenRefreshRetryBaseMillis) {
        this.tokenRefreshRetryBaseMillis = tokenRefreshRetryBaseMillis;
    }

    public int getTokenRefreshRetryMaxMillis() {
        return tokenRefreshRetryMaxMillis;
    }

    public void setTokenRefreshRetryMaxMillis(int tokenRefreshRetryMaxMillis) {
        this.tokenRefreshRetryMaxMillis = tokenRefreshRetryMaxMillis;
    }

    public int getTokenRefreshRetryAttempts() {
        return tokenRefreshRetryAttempts;
    }

    public void setTokenRefreshRetryAttempts(int tokenRefreshRetryAttempts) {
        this.tokenRefreshRetryAttempts = tokenRefreshRetryAttempts;
    }

    public int getMaxRetryOnAuthError() {
        return maxRetryOnAuthError;
    }

    public void setMaxRetryOnAuthError(int maxRetryOnAuthError) {
        this.maxRetryOnAuthError = maxRetryOnAuthError;
    }

    public String getDefaultVoice() {
        return defaultVoice;
    }

    public void setDefaultVoice(String defaultVoice) {
        this.defaultVoice = defaultVoice;
    }

    public String getDefaultLanguage() {
        return defaultLanguage;
    }

    public void setDefaultLanguage(String defaultLanguage) {
        this.defaultLanguage = defaultLanguage;
    }

    public Map<String, String> getVoiceMapping() {
        return voiceMapping;
    }

    public void setVoiceMapping(Map<String, String> voiceMapping) {
        this.voiceMapping = voiceMapping;
    }

    public int getSampleRateHertz() {
        return sampleRateHertz;
    }

    public void setSampleRateHertz(int sampleRateHertz) {
        this.sampleRateHertz = sampleRateHertz;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public boolean isDebugLogTtsPayload() {
        return debugLogTtsPayload;
    }

    public void setDebugLogTtsPayload(boolean debugLogTtsPayload) {
        this.debugLogTtsPayload = debugLogTtsPayload;
    }
}
