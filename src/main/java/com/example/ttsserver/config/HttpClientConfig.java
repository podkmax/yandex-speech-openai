package com.example.ttsserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    @Bean
    RestClient ttsRestClient(SpeechKitProperties properties) {
        ClientHttpRequestFactory requestFactory = requestFactory(properties);

        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    @Bean
    RestClient sttRestClient(SpeechKitProperties properties) {
        ClientHttpRequestFactory requestFactory = requestFactory(properties);

        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.getSttBaseUrl())
                .build();
    }

    @Bean
    RestClient iamRestClient(SpeechKitProperties properties) {
        ClientHttpRequestFactory requestFactory = requestFactory(properties);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    private ClientHttpRequestFactory requestFactory(SpeechKitProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        return factory;
    }
}
