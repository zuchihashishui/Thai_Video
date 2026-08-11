package com.thaivideo.app;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

/**
 * CLI mini-tool: tạo sample image (PNG) + audio (WAV sine) trong thư mục do user chỉ định,
 * để người dùng thử app mà không cần chuẩn bị file.
 *
 * Sử dụng: {@code java -cp target/image-audio-to-video.jar com.thaivideo.app.GenSamples "C:\out\dir"}
 */
public class GenSamples {
    public static void main(String[] args) throws Exception {
        File outDir = (args.length > 0) ? new File(args[0]) : new File("samples");
        if (!outDir.exists() && !outDir.mkdirs()) {
            System.err.println("Cannot create " + outDir);
            System.exit(2);
        }
        File img = new File(outDir, "sample-image.png");
        File aud = new File(outDir, "sample-audio.wav");
        Files.deleteIfExists(img.toPath());
        Files.deleteIfExists(aud.toPath());

        BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 720; y++) {
            for (int x = 0; x < 1280; x++) {
                int r = (int) (Math.sin(x * 0.01) * 127 + 128);
                int g = (int) (Math.sin(y * 0.02) * 127 + 128);
                int b = (int) (Math.cos((x + y) * 0.005) * 127 + 128);
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(image, "png", img);
        writeSineWav(aud, 440.0, 5.0);
        System.out.println("Created: " + img.getAbsolutePath());
        System.out.println("Created: " + aud.getAbsolutePath());
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
        try (var fos = new FileOutputStream(out)) {
            int byteRate = sampleRate * 2;
            int dataSize = samples * 2;
            int chunkSize = 36 + dataSize;
            var dos = new DataOutputStream(fos);
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
