package com.thaivideo.app;

import com.thaivideo.app.model.AspectRatio;
import com.thaivideo.app.model.ConversionRequest;
import com.thaivideo.app.model.ConversionResult;
import com.thaivideo.app.service.FfmpegService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfSystemProperty(named = "runE2E", matches = "true")
class OutputFormatInspectionTest {

    @Autowired FfmpegService ffmpegService;

    @Test
    void outputIsH264AacMp4WithCorrectDimensions() throws Exception {
        // Build temp paths manually (no @TempDir so files survive past test)
        File tmp = Files.createTempDirectory("thai-video-inspect-").toFile();
        tmp.deleteOnExit();
        File image = new File(tmp, "img.png");
        File audio = new File(tmp, "audio.wav");
        File out   = new File(tmp, "out.mp4");
        image.deleteOnExit(); audio.deleteOnExit(); out.deleteOnExit();

        BufferedImage img = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", image);
        writeSineWav(audio, 440.0, 2.0);

        ConversionRequest req = new ConversionRequest();
        req.setImagePath(image.getAbsolutePath());
        req.setAudioPath(audio.getAbsolutePath());
        req.setOutputPath(out.getAbsolutePath());
        req.setAspect(AspectRatio.LANDSCAPE_16_9.getLabel());

        ConversionResult r = ffmpegService.convert(req);
        assertEquals(0, r.getExitCode());

        // ffprobe it (since we have full ffmpeg essentials, ffprobe should also be in PATH).
        Process p = new ProcessBuilder("ffprobe", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=codec_name,width,height",
                "-of", "default=noprint_wrappers=1",
                out.getAbsolutePath())
                .redirectErrorStream(true).start();
        String probe = new String(p.getInputStream().readAllBytes()).trim();
        int rc = p.waitFor();
        assertEquals(0, rc, "ffprobe must succeed. Output: " + probe);

        assertTrue(probe.contains("codec_name=h264"), "Video codec must be H.264. Got: " + probe);
        assertTrue(probe.contains("width=1280"),    "Width must be 1280. Got: " + probe);
        assertTrue(probe.contains("height=720"),    "Height must be 720. Got: " + probe);

        // Also check audio stream
        Process p2 = new ProcessBuilder("ffprobe", "-v", "error",
                "-select_streams", "a:0",
                "-show_entries", "stream=codec_name",
                "-of", "default=noprint_wrappers=1",
                out.getAbsolutePath())
                .redirectErrorStream(true).start();
        String audioProbe = new String(p2.getInputStream().readAllBytes()).trim();
        p2.waitFor();
        assertTrue(audioProbe.contains("codec_name=aac"),
                "Audio codec must be AAC. Got: " + audioProbe);

        System.out.println("=== ffprobe video stream ===");
        System.out.println(probe);
        System.out.println("=== ffprobe audio stream ===");
        System.out.println(audioProbe);
    }

    // Same helper as in EndToEndConvertTest, duplicated here to keep tests independent.
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
