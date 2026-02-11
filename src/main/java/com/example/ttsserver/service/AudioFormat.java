package com.example.ttsserver.service;

import org.springframework.http.MediaType;

public enum AudioFormat {
    MP3("mp3", "mp3", MediaType.valueOf("audio/mpeg"), false),
    OGG("oggopus", "ogg", MediaType.valueOf("audio/ogg"), false),
    PCM("lpcm", "pcm", MediaType.valueOf("audio/pcm"), false),
    WAV("lpcm", "wav", MediaType.valueOf("audio/wav"), true);

    private final String speechKitFormat;
    private final String extension;
    private final MediaType mediaType;
    private final boolean wavWrap;

    AudioFormat(String speechKitFormat, String extension, MediaType mediaType, boolean wavWrap) {
        this.speechKitFormat = speechKitFormat;
        this.extension = extension;
        this.mediaType = mediaType;
        this.wavWrap = wavWrap;
    }

    public String speechKitFormat() {
        return speechKitFormat;
    }

    public String extension() {
        return extension;
    }

    public MediaType mediaType() {
        return mediaType;
    }

    public boolean wavWrap() {
        return wavWrap;
    }

    public static AudioFormat fromOpenAi(String value) {
        if (value == null || value.isBlank()) {
            return MP3;
        }
        return switch (value.toLowerCase()) {
            case "mp3" -> MP3;
            case "ogg" -> OGG;
            case "pcm" -> PCM;
            case "wav" -> WAV;
            default -> throw new IllegalArgumentException("Unsupported response_format: " + value);
        };
    }
}
