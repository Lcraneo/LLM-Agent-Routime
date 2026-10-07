package com.youkeda.project.voice;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 将 PCM 音频编码为微信语音使用的 SILK 格式。
 *
 * <p>微信语音仅支持 SILK 编码（Tencent 变体，文件头为 0x02 + "#!SILK_V3"）。
 * DashScope TTS 只输出 pcm/wav/mp3/opus，因此需要本地编码器完成 PCM → SILK 转换。
 * 编码器来自 silk-v3-decoder 项目（GitHub: kn007/silk-v3-decoder）。
 */
@Service
public class SilkEncoderService {

    private static final Logger log = LoggerFactory.getLogger(SilkEncoderService.class);

    @Value("${silk.encoder-path:bin/silk-encoder-x64.exe}")
    private String encoderPath;

    @Value("${silk.timeout-seconds:20}")
    private int timeoutSeconds;

    private Path resolvedEncoder;

    @PostConstruct
    void init() {
        Path candidate = Path.of(encoderPath.trim());
        if (Files.isRegularFile(candidate)) {
            this.resolvedEncoder = candidate.toAbsolutePath().normalize();
            log.info("SILK encoder ready: {}", resolvedEncoder);
        } else {
            log.warn(
                "SILK encoder not found at '{}'; voice replies will fall back to text",
                encoderPath);
        }
    }

    public boolean isAvailable() {
        return resolvedEncoder != null;
    }

    /**
     * 将 16bit 小端单声道 PCM 编码为微信可播放的 SILK 音频。
     *
     * @param pcm        PCM 音频字节
     * @param sampleRate 采样率（Hz），需与 PCM 实际采样率一致
     * @return SILK 音频字节（Tencent 变体）
     */
    public byte[] encodePcmToSilk(byte[] pcm, int sampleRate) {
        if (pcm == null || pcm.length == 0) {
            throw new IllegalArgumentException("pcm is required");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (resolvedEncoder == null) {
            throw new VoiceException("SILK encoder is not configured: " + encoderPath);
        }

        Path pcmFile = null;
        Path silkFile = null;
        try {
            pcmFile = Files.createTempFile("tts-", ".pcm");
            silkFile = Files.createTempFile("tts-", ".silk");
            Files.write(pcmFile, pcm);
            Files.deleteIfExists(silkFile);

            List<String> command = new ArrayList<>();
            command.add(resolvedEncoder.toString());
            command.add(pcmFile.toString());
            command.add(silkFile.toString());
            command.add("-tencent");
            command.add("-Fs_API");
            command.add(String.valueOf(sampleRate));

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new VoiceException("SILK encoding timed out after " + timeoutSeconds + "s");
            }
            if (process.exitValue() != 0) {
                throw new VoiceException(
                    "SILK encoder exited with " + process.exitValue() + ": " + output.trim());
            }
            if (!Files.isRegularFile(silkFile)) {
                throw new VoiceException("SILK encoder produced no output: " + output.trim());
            }
            byte[] silk = Files.readAllBytes(silkFile);
            if (silk.length == 0) {
                throw new VoiceException("SILK encoder produced an empty file");
            }
            log.info(
                "PCM encoded to SILK: pcmBytes={}, silkBytes={}, sampleRate={}",
                pcm.length,
                silk.length,
                sampleRate);
            return silk;
        } catch (IOException e) {
            throw new VoiceException("SILK encoding failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VoiceException("SILK encoding interrupted", e);
        } finally {
            deleteQuietly(pcmFile);
            deleteQuietly(silkFile);
        }
    }

    private void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 临时文件清理失败可忽略
        }
    }
}
