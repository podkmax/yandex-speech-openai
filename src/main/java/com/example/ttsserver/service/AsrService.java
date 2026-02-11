package com.example.ttsserver.service;

import com.example.ttsserver.config.SpeechKitProperties;
import com.example.ttsserver.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
public class AsrService {

    private final SpeechKitClient speechKitClient;
    private final SpeechKitProperties properties;

    public AsrService(SpeechKitClient speechKitClient, SpeechKitProperties properties) {
        this.speechKitClient = speechKitClient;
        this.properties = properties;
    }

    public String transcribe(MultipartFile file, String language) {
        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "file must not be empty", "invalid_request_error", "file", "validation_error");
        }
        try {
            String lang = (language == null || language.isBlank()) ? properties.getDefaultLanguage() : language;
            return speechKitClient.recognize(file.getBytes(), file.getOriginalFilename(), lang);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unable to read uploaded file", "invalid_request_error", "file", "invalid_file");
        }
    }
}
