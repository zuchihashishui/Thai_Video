package com.thaivideo.app;

import com.thaivideo.app.exception.ConversionException;
import com.thaivideo.app.model.AspectRatio;
import com.thaivideo.app.model.ConversionRequest;
import com.thaivideo.app.model.ConversionResult;
import com.thaivideo.app.service.FfmpegService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfSystemProperty(named = "runE2E", matches = "true")
class EndToEndConvertTest {

    @Autowired FfmpegService ffmpegService;

    @Test
    void convertsImagePlusAudioToMp4_16x9(@TempDir Path tmp) throws Exception {
        // Generate a simple test image (PNG, 800x600).
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        File imageFile = tmp.resolve("sample.png").toFile();
        ImageIO.write(img, "png", imageFile);

        // Generate a short WAV audio (1s, 44.1kHz mono sine) using Java Sound API.
        File audioFile = tmp.resolve("sample.wav").toFile();
        writeSineWav(audioFile, 440.0, 1.0);

        File outFile = tmp.resolve("out_16x9.mp4").toFile();

        ConversionRequest req = new ConversionRequest();
        req.setImagePath(imageFile.getAbsolutePath());
        req.setAudioPath(audioFile.getAbsolutePath());
        req.setOutputPath(outFile.getAbsolutePath());
        req.setAspect(AspectRatio.LANDSCAPE_16_9.getLabel());

        ConversionResult result = ffmpegService.convert(req);
        assertEquals(0, result.getExitCode(), "ffmpeg must succeed");
        assertTrue(outFile.exists(), "output file must exist");
        assertTrue(outFile.length() > 1024, "output file must be non-trivial in size");
        assertEquals("16:9", result.getAspect());
        assertEquals("1280x720", result.getResolution());
    }

    @Test
    void convertsImagePlusAudioToMp4_9x16(@TempDir Path tmp) throws Exception {
        BufferedImage img = new BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB);
        File imageFile = tmp.resolve("portrait.png").toFile();
        ImageIO.write(img, "png", imageFile);
        File audioFile = tmp.resolve("audio2.wav").toFile();
        writeSineWav(audioFile, 220.0, 1.0);
        File outFile = tmp.resolve("out_9x16.mp4").toFile();

        ConversionRequest req = new ConversionRequest();
        req.setImagePath(imageFile.getAbsolutePath());
        req.setAudioPath(audioFile.getAbsolutePath());
        req.setOutputPath(outFile.getAbsolutePath());
        req.setAspect(AspectRatio.PORTRAIT_9_16.getLabel());

        ConversionResult r = ffmpegService.convert(req);
        assertEquals(0, r.getExitCode());
        assertEquals("9:16", r.getAspect());
        assertEquals("720x1280", r.getResolution());
    }

    @Test
    void failsWhenImageMissing(@TempDir Path tmp) throws Exception {
        File audioFile = tmp.resolve("a.wav").toFile();
        writeSineWav(audioFile, 440.0, 0.5);
        ConversionRequest req = new ConversionRequest();
        req.setImagePath(tmp.resolve("missing.png").toString());
        req.setAudioPath(audioFile.getAbsolutePath());
        req.setOutputPath(tmp.resolve("o.mp4").toString());
        assertThrows(ConversionException.class, () -> ffmpegService.convert(req));
    }

    // ---- helper: generate a PCM sine WAV ----
    private static void writeSineWav(File out, double freqHz, double seconds) throws Exception {
        int sampleRate = 44100;
        int samples = (int) (sampleRate * seconds);
        byte[] data = new byte[samples * 2]; // 16-bit mono
        for (int i = 0; i < samples; i++) {
            double t = i / (double) sampleRate;
            short v = (short) (Math.sin(2 * Math.PI * freqHz * t) * 16383);
            data[2 * i]     = (byte) (v & 0xff);
            data[2 * i + 1] = (byte) ((v >> 8) & 0xff);
        }
        try (var fos = new java.io.FileOutputStream(out)) {
            writeWavHeader(fos, samples, sampleRate);
            fos.write(data);
        }
    }

    private static void writeWavHeader(java.io.OutputStream out, int samples, int sampleRate) throws Exception {
        int byteRate = sampleRate * 2;
        int dataSize = samples * 2;
        int chunkSize = 36 + dataSize;
        java.io.DataOutputStream dos = new java.io.DataOutputStream(out);
        dos.writeBytes("RIFF");
        dos.writeInt(Integer.reverseBytes(chunkSize));
        dos.writeBytes("WAVE");
        dos.writeBytes("fmt ");
        dos.writeInt(Integer.reverseBytes(16));
        dos.writeShort(Short.reverseBytes((short) 1));   // PCM
        dos.writeShort(Short.reverseBytes((short) 1));   // mono
        dos.writeInt(Integer.reverseBytes(sampleRate));
        dos.writeInt(Integer.reverseBytes(byteRate));
        dos.writeShort(Short.reverseBytes((short) 2));   // block align
        dos.writeShort(Short.reverseBytes((short) 16));  // bits per sample
        dos.writeBytes("data");
        dos.writeInt(Integer.reverseBytes(dataSize));
        dos.flush();
    }
}
