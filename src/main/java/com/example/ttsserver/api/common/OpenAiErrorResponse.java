package com.example.ttsserver.api.common;

public record OpenAiErrorResponse(ErrorBody error) {
    public record ErrorBody(String message, String type, String param, String code) {
    }
}
