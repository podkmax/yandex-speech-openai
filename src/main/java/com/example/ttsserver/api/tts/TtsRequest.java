package com.example.ttsserver.api.tts;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

public record TtsRequest(
        @NotBlank String model,
        @NotBlank String input,
        String voice,
        String response_format,
        @DecimalMin("0.25") @DecimalMax("3.0") Double speed,
        String stream_format
) {
}
