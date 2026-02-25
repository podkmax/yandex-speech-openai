package com.example.ttsserver.service;

import com.example.ttsserver.config.SpeechKitProperties;
import com.example.ttsserver.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class AsrService {

    private final SpeechKitClient speechKitClient;
    private final SpeechKitProperties properties;
    private final Semaphore normalizationSemaphore;

    public AsrService(SpeechKitClient speechKitClient, SpeechKitProperties properties) {
        this.speechKitClient = speechKitClient;
        this.properties = properties;
        this.normalizationSemaphore = createNormalizationSemaphore(properties.getAsrNormalize().getConcurrencyMaxProcesses());
    }

    public String transcribe(MultipartFile file, String language) {
        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "file must not be empty", "invalid_request_error", "file", "validation_error");
        }
        try {
            String lang = (language == null || language.isBlank()) ? properties.getDefaultLanguage() : language;
            byte[] bytes = file.getBytes();
            SpeechKitProperties.AsrNormalizeProperties normalize = properties.getAsrNormalize();
            if (normalize.isEnabled()) {
                byte[] normalizedBytes = normalizeWithFfmpeg(bytes);
                return speechKitClient.recognize(
                        normalizedBytes,
                        file.getOriginalFilename(),
                        lang,
                        "lpcm",
                        normalize.getTargetSampleRateHertz()
                );
            }
            DetectedAudioFormat detectedFormat = detectFormat(file.getOriginalFilename(), file.getContentType(), bytes);
            return speechKitClient.recognize(
                    bytes,
                    file.getOriginalFilename(),
                    lang,
                    detectedFormat.format(),
                    detectedFormat.sampleRateHertz()
            );
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unable to read uploaded file", "invalid_request_error", "file", "invalid_file");
        }
    }

    private byte[] normalizeWithFfmpeg(byte[] inputBytes) {
        SpeechKitProperties.AsrNormalizeProperties normalize = properties.getAsrNormalize();
        if (inputBytes.length > normalize.getMaxInputBytes()) {
            throw new ApiException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Audio file too large for normalization",
                    "invalid_request_error",
                    "file",
                    "file_too_large"
            );
        }

        boolean acquired = false;
        Path inputPath = null;
        Path outputPath = null;
        try {
            acquired = acquireNormalizationPermit();
            Path tempDir = resolveTempDir(normalize.getTempDir());
            inputPath = Files.createTempFile(tempDir, "asr-input-", ".bin");
            outputPath = Files.createTempFile(tempDir, "asr-output-", ".wav");

            Files.write(inputPath, inputBytes);

            Process process = startFfmpegProcess(normalize, inputPath, outputPath);
            StderrCapture stderrCapture = new StderrCapture(process.getErrorStream(), normalize.getMaxStderrBytes());
            Thread stderrThread = Thread.ofPlatform().daemon().start(stderrCapture::readToEnd);

            boolean finished = process.waitFor(normalize.getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                joinQuietly(stderrThread);
                throw conversionFailed("Audio conversion timed out", stderrCapture.asString());
            }

            joinQuietly(stderrThread);
            if (process.exitValue() != 0) {
                throw conversionFailed("Audio conversion failed", stderrCapture.asString());
            }

            return Files.readAllBytes(outputPath);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw conversionFailed("Audio conversion interrupted", null);
        } catch (IOException ex) {
            if (isMissingExecutable(ex)) {
                throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "ASR normalization backend is unavailable",
                        "server_error",
                        "file",
                        "upstream_unavailable"
                );
            }
            throw conversionFailed("Audio conversion failed", ex.getMessage());
        } finally {
            deleteQuietly(inputPath);
            deleteQuietly(outputPath);
            if (acquired && normalizationSemaphore != null) {
                normalizationSemaphore.release();
            }
        }
    }

    private Process startFfmpegProcess(SpeechKitProperties.AsrNormalizeProperties normalize, Path inputPath, Path outputPath) throws IOException {
        List<String> args = new ArrayList<>();
        args.add(normalize.getFfmpegPath());
        args.add("-hide_banner");
        args.add("-loglevel");
        args.add("error");
        args.add("-y");
        args.add("-i");
        args.add(inputPath.toString());
        if (normalize.getMaxDurationSeconds() > 0) {
            args.add("-t");
            args.add(String.valueOf(normalize.getMaxDurationSeconds()));
        }
        args.add("-ac");
        args.add(String.valueOf(normalize.getTargetChannels()));
        args.add("-ar");
        args.add(String.valueOf(normalize.getTargetSampleRateHertz()));
        args.add("-acodec");
        args.add("pcm_s16le");
        args.add("-f");
        args.add("wav");
        args.add(outputPath.toString());
        return new ProcessBuilder(args).start();
    }

    private Path resolveTempDir(String configuredTempDir) throws IOException {
        if (configuredTempDir == null || configuredTempDir.isBlank()) {
            return Paths.get(System.getProperty("java.io.tmpdir"));
        }
        Path dir = Paths.get(configuredTempDir);
        Files.createDirectories(dir);
        return dir;
    }

    private boolean acquireNormalizationPermit() throws InterruptedException {
        if (normalizationSemaphore == null) {
            return false;
        }
        normalizationSemaphore.acquire();
        return true;
    }

    private Semaphore createNormalizationSemaphore(Integer maxProcesses) {
        if (maxProcesses == null || maxProcesses < 1) {
            return null;
        }
        return new Semaphore(maxProcesses);
    }

    private ApiException conversionFailed(String prefix, String stderrOrMessage) {
        String suffix = sanitizeErrorDetail(stderrOrMessage);
        String message = suffix.isEmpty() ? prefix : prefix + ": " + suffix;
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                message,
                "invalid_request_error",
                "file",
                "unsupported_media_type"
        );
    }

    private String sanitizeErrorDetail(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String singleLine = trimmed.replaceAll("\\s+", " ");
        if (singleLine.length() <= 240) {
            return singleLine;
        }
        return singleLine.substring(0, 240);
    }

    private boolean isMissingExecutable(IOException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("error=2")
                || normalized.contains("no such file")
                || normalized.contains("cannot find the file")
                || normalized.contains("not found");
    }

    private void joinQuietly(Thread thread) {
        try {
            thread.join(1000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private DetectedAudioFormat detectFormat(String originalFilename, String contentType, byte[] bytes) {
        String extension = fileExtension(originalFilename);
        String normalizedContentType = normalizeContentType(contentType);

        boolean isWav = "wav".equals(extension)
                || "audio/wav".equals(normalizedContentType)
                || "audio/x-wav".equals(normalizedContentType)
                || "audio/wave".equals(normalizedContentType);
        if (isWav) {
            WavMetadata metadata = parseWavMetadata(bytes);
            if (metadata != null) {
                validatePcm16Wav(metadata);
            }
            Integer sampleRate = metadata == null ? properties.getSampleRateHertz() : metadata.sampleRateHertz();
            return new DetectedAudioFormat("lpcm", sampleRate);
        }

        boolean isOgg = "ogg".equals(extension)
                || "audio/ogg".equals(normalizedContentType)
                || "application/ogg".equals(normalizedContentType);
        if (isOgg) {
            return new DetectedAudioFormat("oggopus", null);
        }

        boolean isMp3 = "mp3".equals(extension)
                || "audio/mpeg".equals(normalizedContentType);
        if (isMp3) {
            return new DetectedAudioFormat("mp3", null);
        }

        if (properties.isRequireKnownAsrFormat()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Unknown audio format. Supported file extensions: .wav, .ogg, .mp3",
                    "invalid_request_error",
                    "file",
                    "unsupported_media_type"
            );
        }

        return new DetectedAudioFormat(null, null);
    }

    private String fileExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "";
        }
        String sanitized = originalFilename.replace('\\', '/');
        int slash = sanitized.lastIndexOf('/');
        String baseName = slash >= 0 ? sanitized.substring(slash + 1) : sanitized;
        int dot = baseName.lastIndexOf('.');
        if (dot < 0 || dot == baseName.length() - 1) {
            return "";
        }
        return baseName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String value = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private void validatePcm16Wav(WavMetadata metadata) {
        if (metadata.audioFormat() == 1 && metadata.bitsPerSample() == 16) {
            return;
        }
        throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "WAV must be 16-bit PCM for ASR. Convert with ffmpeg: ffmpeg -i input.wav -ac 1 -ar 48000 -sample_fmt s16 output.wav",
                "invalid_request_error",
                "file",
                "unsupported_media_type"
        );
    }

    private WavMetadata parseWavMetadata(byte[] bytes) {
        if (bytes == null || bytes.length < 20) {
            return null;
        }
        if (!hasAscii(bytes, 0, "RIFF") || !hasAscii(bytes, 8, "WAVE")) {
            return null;
        }

        int offset = 12;
        while (offset + 8 <= bytes.length) {
            int chunkHeaderOffset = offset;
            int dataOffset = offset + 8;
            long chunkSize = uint32Le(bytes, offset + 4);
            long nextOffset = dataOffset + chunkSize + (chunkSize % 2);
            if (nextOffset > bytes.length) {
                return null;
            }

            if (hasAscii(bytes, chunkHeaderOffset, "fmt ")) {
                if (chunkSize < 16 || dataOffset + 16 > bytes.length) {
                    return null;
                }
                int audioFormat = uint16Le(bytes, dataOffset);
                int sampleRateHertz = (int) uint32Le(bytes, dataOffset + 4);
                int bitsPerSample = uint16Le(bytes, dataOffset + 14);
                if (sampleRateHertz < 8000) {
                    return null;
                }
                return new WavMetadata(audioFormat, bitsPerSample, sampleRateHertz);
            }

            offset = (int) nextOffset;
        }

        return null;
    }

    private boolean hasAscii(byte[] bytes, int offset, String expected) {
        if (offset < 0 || offset + expected.length() > bytes.length) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < expectedBytes.length; i++) {
            if (bytes[offset + i] != expectedBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private int uint16Le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private long uint32Le(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xff)
                | (((long) bytes[offset + 1] & 0xff) << 8)
                | (((long) bytes[offset + 2] & 0xff) << 16)
                | (((long) bytes[offset + 3] & 0xff) << 24);
    }

    private record DetectedAudioFormat(String format, Integer sampleRateHertz) {
    }

    private record WavMetadata(int audioFormat, int bitsPerSample, int sampleRateHertz) {
    }

    private static final class StderrCapture {

        private final InputStream inputStream;
        private final int maxBytes;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private StderrCapture(InputStream inputStream, int maxBytes) {
            this.inputStream = inputStream;
            this.maxBytes = Math.max(1, maxBytes);
        }

        private void readToEnd() {
            byte[] buffer = new byte[1024];
            int read;
            try {
                while ((read = inputStream.read(buffer)) >= 0) {
                    int available = maxBytes - output.size();
                    if (available <= 0) {
                        continue;
                    }
                    int toWrite = Math.min(available, read);
                    output.write(buffer, 0, toWrite);
                }
            } catch (IOException ignored) {
            }
        }

        private String asString() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
