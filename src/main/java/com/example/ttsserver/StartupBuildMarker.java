package com.example.ttsserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StartupBuildMarker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupBuildMarker.class);
    private static final String BUILD_MARKER_ID = "5f79e0a3";
    private static final String BUILD_TIME_UTC = "2026-02-11T14:02:00Z";

    @Override
    public void run(ApplicationArguments args) {
        log.info("BUILD_MARKER tts-server {} {}", BUILD_MARKER_ID, BUILD_TIME_UTC);
    }
}
