package com.example.ttsserver.service;

import com.example.ttsserver.api.tts.TtsRequest;
import com.example.ttsserver.config.SpeechKitProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TtsServiceTest {

    @Test
    void mapsAlloyVoiceToMashaForUpstreamSynthesis() {
        SpeechKitClient client = mock(SpeechKitClient.class);
        when(client.synthesize("hello", "masha", "ru-RU", 1.0, AudioFormat.MP3))
                .thenReturn(new byte[]{1});

        SpeechKitProperties properties = new SpeechKitProperties();
        properties.setDefaultLanguage("ru-RU");
        properties.setVoiceMapping(Map.of("alloy", "masha"));

        TtsService service = new TtsService(client, properties);
        service.synthesize(new TtsRequest("gpt-4o-mini-tts", "hello", "alloy", "mp3", 1.0, null));

        verify(client).synthesize(eq("hello"), eq("masha"), eq("ru-RU"), eq(1.0), eq(AudioFormat.MP3));
    }
}
