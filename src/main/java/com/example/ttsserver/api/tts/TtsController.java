package com.example.ttsserver.api.tts;

import com.example.ttsserver.error.ApiException;
import com.example.ttsserver.service.TtsResult;
import com.example.ttsserver.service.TtsService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/audio")
public class TtsController {

    private static final Logger log = LoggerFactory.getLogger(TtsController.class);

    private final TtsService ttsService;

    public TtsController(TtsService ttsService) {
        this.ttsService = ttsService;
    }

    @PostMapping("/speech")
    public ResponseEntity<byte[]> speech(@Valid @RequestBody TtsRequest request) {
        String requestId = currentRequestId();
        int inputLength = request.input() == null ? 0 : request.input().length();
        log.info("TTS request received request_id={} model={} voice={} response_format={} speed={} input_length={}",
                requestId,
                request.model(),
                request.voice(),
                request.response_format(),
                request.speed(),
                inputLength);
        if ("sse".equalsIgnoreCase(request.stream_format())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "stream_format=sse is not supported in MVP", "invalid_request_error", "stream_format", "not_supported");
        }

        TtsResult result = ttsService.synthesize(request);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(result.format().mediaType());
        headers.setContentDisposition(ContentDisposition.attachment().filename("speech." + result.format().extension()).build());
        return ResponseEntity.ok().headers(headers).body(result.bytes());
    }

    private String currentRequestId() {
        String requestId = MDC.get("request_id");
        if (requestId == null || requestId.isBlank()) {
            return "unknown";
        }
        return requestId;
    }
}
