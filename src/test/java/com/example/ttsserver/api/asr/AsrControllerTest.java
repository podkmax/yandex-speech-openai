package com.example.ttsserver.api.asr;

import com.example.ttsserver.config.CompatProperties;
import com.example.ttsserver.service.AsrService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AsrController.class)
class AsrControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AsrService asrService;

    @MockBean
    private CompatProperties compatProperties;

    @Test
    void rejectsUnsupportedFieldInStrictMode() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.wav", MediaType.APPLICATION_OCTET_STREAM_VALUE, "abc".getBytes());
        given(compatProperties.isStrict()).willReturn(true);

        mockMvc.perform(multipart("/v1/audio/transcriptions")
                        .file(file)
                        .param("model", "whisper-1")
                        .param("prompt", "ignored"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_field"));
    }

    @Test
    void returnsTextResponseFormat() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.wav", MediaType.APPLICATION_OCTET_STREAM_VALUE, "abc".getBytes());
        given(compatProperties.isStrict()).willReturn(false);
        given(asrService.transcribe(any(), nullable(String.class))).willReturn("hello world");

        mockMvc.perform(multipart("/v1/audio/transcriptions")
                        .file(file)
                        .param("model", "whisper-1")
                        .param("response_format", "text"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("hello world"));
    }
}
