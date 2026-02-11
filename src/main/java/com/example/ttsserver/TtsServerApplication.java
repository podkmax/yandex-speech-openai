package com.example.ttsserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TtsServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TtsServerApplication.class, args);
    }
}
