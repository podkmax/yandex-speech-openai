package com.example.ttsserver.api.tts;

import com.example.ttsserver.service.AudioFormat;
import com.example.ttsserver.service.TtsResult;
import com.example.ttsserver.service.TtsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TtsController.class)
class TtsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TtsService ttsService;

    @Test
    void returnsValidationErrorEnvelope() throws Exception {
        mockMvc.perform(post("/v1/audio/speech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"\",\"input\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void returnsBadRequestForSse() throws Exception {
        mockMvc.perform(post("/v1/audio/speech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"x\",\"input\":\"hello\",\"stream_format\":\"sse\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.param").value("stream_format"));
    }

    @Test
    void echoesRequestIdHeader() throws Exception {
        given(ttsService.synthesize(any())).willReturn(new TtsResult("abc".getBytes(), AudioFormat.MP3));

        mockMvc.perform(post("/v1/audio/speech")
                        .header("X-Request-Id", "rid-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"x\",\"input\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "rid-123"));
    }
}
