package com.example.ttsserver.service;

import com.example.ttsserver.api.tts.TtsRequest;
import com.example.ttsserver.config.SpeechKitProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TtsServiceTest {

    @Test
    void mapsAlloyVoiceToMashaForUpstreamSynthesis() {
        SpeechKitClient client = mock(SpeechKitClient.class);
        when(client.synthesize("hello", "masha", "ru-RU", 1.0, null, null, AudioFormat.MP3))
                .thenReturn(new byte[]{1});

        SpeechKitProperties properties = new SpeechKitProperties();
        properties.setDefaultLanguage("ru-RU");
        properties.setVoiceMapping(Map.of("alloy", "masha"));

        TtsService service = new TtsService(client, properties);
        service.synthesize(new TtsRequest("gpt-4o-mini-tts", "hello", "alloy", "mp3", 1.0, null));

        verify(client).synthesize(eq("hello"), eq("masha"), eq("ru-RU"), eq(1.0), isNull(), isNull(), eq(AudioFormat.MP3));
    }

    @Test
    void usesConfiguredVoiceSpeedWhenRequestSpeedIsMissing() {
        SpeechKitClient client = mock(SpeechKitClient.class);
        when(client.synthesize("hello", "masha", "ru-RU", 0.85, "friendly", 120.0, AudioFormat.MP3))
                .thenReturn(new byte[]{1});

        SpeechKitProperties properties = new SpeechKitProperties();
        properties.setDefaultLanguage("ru-RU");
        properties.setVoiceMapping(Map.of("alloy", "masha"));

        SpeechKitProperties.VoiceSettingsProperties mashaSettings = new SpeechKitProperties.VoiceSettingsProperties();
        mashaSettings.setRole("friendly");
        mashaSettings.setSpeed(0.85);
        mashaSettings.setPitch(120.0);
        SpeechKitProperties.TtsProperties ttsProperties = new SpeechKitProperties.TtsProperties();
        ttsProperties.setVoiceSettings(Map.of("masha", mashaSettings));
        properties.setTts(ttsProperties);

        TtsService service = new TtsService(client, properties);
        service.synthesize(new TtsRequest("gpt-4o-mini-tts", "hello", "alloy", "mp3", null, null));

        verify(client).synthesize(eq("hello"), eq("masha"), eq("ru-RU"), eq(0.85), eq("friendly"), eq(120.0), eq(AudioFormat.MP3));
    }

    @Test
    void requestSpeedOverridesConfiguredVoiceSpeed() {
        SpeechKitClient client = mock(SpeechKitClient.class);
        when(client.synthesize("hello", "masha", "ru-RU", 1.25, "friendly", 120.0, AudioFormat.MP3))
                .thenReturn(new byte[]{1});

        SpeechKitProperties properties = new SpeechKitProperties();
        properties.setDefaultLanguage("ru-RU");
        properties.setVoiceMapping(Map.of("alloy", "masha"));

        SpeechKitProperties.VoiceSettingsProperties mashaSettings = new SpeechKitProperties.VoiceSettingsProperties();
        mashaSettings.setRole("friendly");
        mashaSettings.setSpeed(0.85);
        mashaSettings.setPitch(120.0);
        SpeechKitProperties.TtsProperties ttsProperties = new SpeechKitProperties.TtsProperties();
        ttsProperties.setVoiceSettings(Map.of("masha", mashaSettings));
        properties.setTts(ttsProperties);

        TtsService service = new TtsService(client, properties);
        service.synthesize(new TtsRequest("gpt-4o-mini-tts", "hello", "alloy", "mp3", 1.25, null));

        verify(client).synthesize(eq("hello"), eq("masha"), eq("ru-RU"), eq(1.25), eq("friendly"), eq(120.0), eq(AudioFormat.MP3));
    }
}
