package com.example.ttsserver.api.asr;

import com.example.ttsserver.config.CompatProperties;
import com.example.ttsserver.error.ApiException;
import com.example.ttsserver.service.AsrService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

@Validated
@RestController
@RequestMapping("/v1/audio")
public class AsrController {

    private static final Set<String> SUPPORTED_FIELDS = Set.of("file", "model", "language", "response_format");

    private final AsrService asrService;
    private final CompatProperties compatProperties;

    public AsrController(AsrService asrService, CompatProperties compatProperties) {
        this.asrService = asrService;
        this.compatProperties = compatProperties;
    }

    @PostMapping(path = "/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> transcriptions(
            @RequestParam("file") MultipartFile file,
            @RequestParam("model") @NotBlank String model,
            @RequestParam(name = "language", required = false) String language,
            @RequestParam(name = "response_format", defaultValue = "json") String responseFormat,
            @RequestParam MultiValueMap<String, String> params
    ) {
        if (compatProperties.isStrict()) {
            params.keySet().stream()
                    .filter(key -> !SUPPORTED_FIELDS.contains(key))
                    .findFirst()
                    .ifPresent(key -> {
                        throw new ApiException(HttpStatus.BAD_REQUEST,
                                "Unsupported field in strict mode: " + key,
                                "invalid_request_error",
                                key,
                                "unsupported_field");
                    });
        }

        String text = asrService.transcribe(file, language);
        if ("text".equalsIgnoreCase(responseFormat)) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(text);
        }
        if (!"json".equalsIgnoreCase(responseFormat)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "response_format must be json or text",
                    "invalid_request_error",
                    "response_format",
                    "validation_error");
        }
        return ResponseEntity.ok(new TranscriptionResponse(text));
    }
}
