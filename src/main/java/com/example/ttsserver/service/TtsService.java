package com.example.ttsserver.service;

import com.example.ttsserver.api.tts.TtsRequest;
import com.example.ttsserver.config.SpeechKitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    private final SpeechKitClient speechKitClient;
    private final SpeechKitProperties properties;

    public TtsService(SpeechKitClient speechKitClient, SpeechKitProperties properties) {
        this.speechKitClient = speechKitClient;
        this.properties = properties;
    }

    public TtsResult synthesize(TtsRequest request) {
        String requestId = currentRequestId();
        AudioFormat format = AudioFormat.fromOpenAi(request.response_format());
        String voice = mapVoice(request.voice());
        SpeechKitProperties.VoiceSettingsProperties voiceSettings = resolveVoiceSettings(voice);
        Double speed = request.speed() != null ? request.speed() : voiceSettings.getSpeed();
        String role = voiceSettings.getRole();
        Double pitch = voiceSettings.getPitch();
        log.info("TTS synthesis prepared request_id={} requested_voice={} mapped_voice={} audio_format={} wav_wrap={}",
                requestId,
                request.voice(),
                voice,
                format,
                format.wavWrap());
        byte[] bytes = speechKitClient.synthesize(
                request.input(),
                voice,
                properties.getDefaultLanguage(),
                speed,
                role,
                pitch,
                format
        );

        if (format.wavWrap()) {
            bytes = WavEncoder.fromPcmS16Le(bytes, properties.getSampleRateHertz(), 1);
        }
        return new TtsResult(bytes, format);
    }

    private String currentRequestId() {
        String requestId = MDC.get("request_id");
        if (requestId == null || requestId.isBlank()) {
            return "unknown";
        }
        return requestId;
    }

    private String mapVoice(String requestedVoice) {
        if (requestedVoice == null || requestedVoice.isBlank()) {
            return properties.getDefaultVoice();
        }
        return properties.getVoiceMapping().getOrDefault(requestedVoice, requestedVoice);
    }

    private SpeechKitProperties.VoiceSettingsProperties resolveVoiceSettings(String speechKitVoice) {
        return properties.getTts().getVoiceSettings()
                .getOrDefault(speechKitVoice, new SpeechKitProperties.VoiceSettingsProperties());
    }
}
