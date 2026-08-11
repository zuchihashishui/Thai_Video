package com.thaivideo.app;

import com.thaivideo.app.model.AspectRatio;
import com.thaivideo.app.model.ConversionRequest;
import com.thaivideo.app.model.ConversionResult;
import com.thaivideo.app.service.FfmpegService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test callback progress sau khi chuyển sang cơ chế {@code -progress pipe:1}.
 * Verify:
 *  - ffprobe ra duration chính xác của audio
 *  - onProgress được gọi nhiều lần với fraction tăng dần (không nhảy 0→100)
 *  - speed được báo kèm theo
 *  - log ffmpeg vẫn đầy đủ (đi qua stderr)
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "runE2E", matches = "true")
class ProgressCallbackTest {

    @Autowired FfmpegService ffmpegService;

    @Test
    void progressListener_reportsFractionAndSpeed(@TempDir Path tmp) throws Exception {
        File image = tmp.resolve("img.png").toFile();
        File audio = tmp.resolve("aud.wav").toFile();
        File out   = tmp.resolve("out.mp4").toFile();

        BufferedImage img = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", image);
        // Audio 30s preset medium: đủ dài để ffmpeg in nhiều block progress=continue.
        writeSineWav(audio, 220.0, 30.0);

        // 1) ffprobe phải đo được duration audio gần 30s.
        double probed = ffmpegService.probeAudioDuration(audio.getAbsolutePath());
        assertTrue(probed > 29.9 && probed < 30.1, "ffprobe duration out of range: " + probed);

        ConversionRequest req = new ConversionRequest();
        req.setImagePath(image.getAbsolutePath());
        req.setAudioPath(audio.getAbsolutePath());
        req.setOutputPath(out.getAbsolutePath());
        req.setAspect(AspectRatio.LANDSCAPE_16_9.getLabel());
        // Preset mặc định (medium) — không override.

        List<Double> fractions = new ArrayList<>();
        List<String> speeds = new ArrayList<>();
        List<String> logLines = new ArrayList<>();
        int[] exitCode = new int[1];

        FfmpegService.ProgressListener listener = new FfmpegService.ProgressListener() {
            @Override public void onLogLine(String line) { logLines.add(line); }
            @Override public void onProgress(double fraction, String speed) {
                fractions.add(fraction);
                speeds.add(speed);
            }
            @Override public void onComplete(int code) { exitCode[0] = code; }
        };

        ConversionResult r = ffmpegService.convert(req, listener);
        assertEquals(0, r.getExitCode());
        assertTrue(out.exists(), "output phải tồn tại");

        // 2) Log ffmpeg đi qua stderr phải có thông tin encode.
        assertTrue(logLines.size() > 5, "ffmpeg phải in >5 dòng log, got " + logLines.size());
        boolean hasStreamMapping = logLines.stream().anyMatch(l -> l.contains("Stream mapping:"));
        assertTrue(hasStreamMapping, "phải có dòng 'Stream mapping:' từ ffmpeg");

        // 3) Progress phải được báo nhiều lần và tăng dần.
        assertTrue(fractions.size() >= 2,
                "progress callback phải được gọi ≥ 2 lần, got " + fractions.size());
        for (int i = 1; i < fractions.size(); i++) {
            assertTrue(fractions.get(i) >= fractions.get(i - 1) - 0.001,
                    "fraction phải tăng dần: " + fractions);
        }
        double last = fractions.get(fractions.size() - 1);
        assertTrue(last > 0.0 && last <= 1.0,
                "fraction cuối phải > 0 (không nhảy thẳng 100%), got " + last);

        // 4) Speed phải có (không null).
        long speedsNonNull = speeds.stream().filter(s -> s != null && !s.isBlank()).count();
        assertTrue(speedsNonNull > 0, "phải có ít nhất 1 speed báo, got " + speeds);

        // 5) Exit code đúng.
        assertEquals(0, exitCode[0]);
    }

    private static void writeSineWav(File out, double freqHz, double seconds) throws Exception {
        int sampleRate = 44100;
        int samples = (int) (sampleRate * seconds);
        byte[] data = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            double t = i / (double) sampleRate;
            short v = (short) (Math.sin(2 * Math.PI * freqHz * t) * 16383);
            data[2 * i]     = (byte) (v & 0xff);
            data[2 * i + 1] = (byte) ((v >> 8) & 0xff);
        }
        try (var fos = new java.io.FileOutputStream(out)) {
            int byteRate = sampleRate * 2;
            int dataSize = samples * 2;
            int chunkSize = 36 + dataSize;
            var dos = new java.io.DataOutputStream(fos);
            dos.writeBytes("RIFF");
            dos.writeInt(Integer.reverseBytes(chunkSize));
            dos.writeBytes("WAVE");
            dos.writeBytes("fmt ");
            dos.writeInt(Integer.reverseBytes(16));
            dos.writeShort(Short.reverseBytes((short) 1));
            dos.writeShort(Short.reverseBytes((short) 1));
            dos.writeInt(Integer.reverseBytes(sampleRate));
            dos.writeInt(Integer.reverseBytes(byteRate));
            dos.writeShort(Short.reverseBytes((short) 2));
            dos.writeShort(Short.reverseBytes((short) 16));
            dos.writeBytes("data");
            dos.writeInt(Integer.reverseBytes(dataSize));
            fos.write(data);
        }
    }
}
