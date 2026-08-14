package com.thaivideo.app.service;

import com.thaivideo.app.exception.ConversionException;
import com.thaivideo.app.model.AspectRatio;
import com.thaivideo.app.model.ConversionRequest;
import com.thaivideo.app.model.ConversionResult;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Lõi xử lý — gọi ffmpeg để nối ảnh + audio thành video MP4 (H.264 + AAC).
 *
 * <p>Cách hoạt động:</p>
 * <ol>
 *     <li>Ảnh được loop vô hạn với {@code -loop 1}.</li>
 *     <li>Filter {@code scale + pad} resize ảnh sao cho vừa khung hình
 *         mà vẫn giữ nguyên tỷ lệ ảnh gốc (không crop).</li>
 *     <li>Audio đầu vào được encode AAC (lib_aac) hoặc copy nếu đã AAC.</li>
 *     <li>{@code -shortest} để video kết thúc theo audio.</li>
 *     <li>Tiến trình được stream từ stderr và đẩy qua callback (log).</li>
 * </ol>
 */
@Service
public class FfmpegService {

    private static final Logger log = LoggerFactory.getLogger(FfmpegService.class);

    @Value("${ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${ffprobe.path:ffprobe}")
    private String ffprobePath;

    @Value("${ffmpeg.threads:0}")
    private int threads;

    @Value("${video.default-aspect:16:9}")
    private String defaultAspect;

    @Value("${video.default-resolution-landscape:1280x720}")
    private String defaultResLandscape;

    @Value("${video.default-resolution-portrait:720x1280}")
    private String defaultResPortrait;

    @Value("${video.default-crf:23}")
    private int defaultCrf;

    @Value("${video.default-preset:medium}")
    private String defaultPreset;

    @Value("${video.default-audio-bitrate:192k}")
    private String defaultAudioBitrate;

    /** Callback nhận log/progress từ ffmpeg. */
    public interface ProgressListener {
        /** Một dòng log bất kỳ từ ffmpeg (stderr/stdout). */
        void onLogLine(String line);
        /**
         * Progress cập nhật — {@code fraction} trong khoảng [0, 1] hoặc -1 nếu không xác định.
         * {@code speed} là tốc độ encode (ví dụ "1.23x"); có thể null nếu không có.
         */
        void onProgress(double fraction, String speed);
        void onComplete(int exitCode);
    }

    public static final ProgressListener NOOP_LISTENER = new ProgressListener() {
        @Override public void onLogLine(String line) {}
        @Override public void onProgress(double fraction, String speed) {}
        @Override public void onComplete(int exitCode) {}
    };

    @PostConstruct
    public void verifyFfmpeg() {
        try {
            String version = runAndCapture(List.of(ffmpegPath, "-version"), null);
            log.info("Detected ffmpeg:\n{}", version.lines().findFirst().orElse(""));
        } catch (IOException | InterruptedException e) {
            log.warn("Cannot execute '{} -version' right now: {}", ffmpegPath, e.getMessage());
        }
    }

    /**
     * Đồng bộ — đợi convert xong rồi trả {@link ConversionResult}.
     *
     * <p>Cơ chế progress:</p>
     * <ul>
     *     <li>ffmpeg được gọi với {@code -progress pipe:1 -nostats} —
     *         block key=value in ra stdout; log chi tiết đi qua stderr.</li>
     *     <li>Hai thread reader chạy song song: stderr đẩy log cho listener,
     *         stdout parse block {@code out_time_us=.../speed=.../progress=...}.</li>
     *     <li>Duration audio được probe trước qua ffprobe (nếu có) để có total
     *         chính xác ngay từ đầu — không phụ thuộc dòng {@code Duration:}
     *         mà ffmpeg có thể không in ra khi dùng {@code -loop 1}.</li>
     * </ul>
     */
    public ConversionResult convert(ConversionRequest req, ProgressListener listener) {
        ProgressListener safeListener = (listener == null) ? NOOP_LISTENER : listener;
        Path outputPath = Paths.get(req.getOutputPath()).toAbsolutePath();

        try {
            // Validation: file input phải tồn tại.
            assertReadable(req.getImagePath(), "image");
            assertReadable(req.getAudioPath(), "audio");
            // Tạo thư mục cha của output nếu chưa có.
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }

            // Probe duration audio trước (nếu ffprobe có sẵn).
            double audioSeconds = probeAudioDuration(req.getAudioPath());
            if (audioSeconds > 0) {
                log.info("Probed audio duration: {}s", String.format("%.3f", audioSeconds));
            }

            // Tổng thời lượng dùng để tính progress:
            // - Nếu request có duration cố định → dùng requestedDuration (đơn vị: giây)
            // - Ngược lại → dùng audioSeconds (probe được từ ffprobe)
            double totalSeconds;
            if (req.getDuration() != null && req.getDuration() > 0) {
                totalSeconds = req.getDuration();
                log.info("Using requested duration: {}s", totalSeconds);
            } else {
                totalSeconds = audioSeconds;
            }

            List<String> cmd = buildCommand(req, outputPath);
            log.info("Running ffmpeg: {}", String.join(" ", cmd));

            Process proc = new ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .start();

            long start = System.currentTimeMillis();
            long lastReportedPct = -1;
            String lastSpeed = null;

            // ---- Thread stderr: đẩy log chi tiết từ ffmpeg sang listener ----
            Thread stderrThread = new Thread(() -> {
                try (var br = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        safeListener.onLogLine(line);
                    }
                } catch (IOException ignore) {
                    // stream đóng khi ffmpeg kết thúc — bỏ qua
                }
            }, "ffmpeg-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            // ---- Thread stdout: parse progress key=value ----
            try (var br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                Map<String, String> block = new HashMap<>();
                String line;
                while ((line = br.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    block.put(key, value);
                    if ("progress".equals(key)) {
                        // End of block — commit progress.
                        String outTimeUs = block.get("out_time_us");
                        String speedRaw = block.get("speed");
                        if (outTimeUs != null && totalSeconds > 0) {
                            try {
                                double current = Long.parseLong(outTimeUs) / 1_000_000.0;
                                double frac = Math.min(1.0, Math.max(0.0, current / audioSeconds));
                                long pct = Math.round(frac * 100.0);
                                if (pct != lastReportedPct) {
                                    lastReportedPct = pct;
                                    String speed = formatSpeed(speedRaw);
                                    lastSpeed = speed;
                                    safeListener.onProgress(frac, speed);
                                }
                            } catch (NumberFormatException ignore) {}
                        } else if (outTimeUs != null && speedRaw != null) {
                            // Không biết total nhưng vẫn đẩy speed để UI hiển thị.
                            String speed = formatSpeed(speedRaw);
                            if (!Objects.equals(speed, lastSpeed)) {
                                lastSpeed = speed;
                                safeListener.onProgress(-1.0, speed);
                            }
                        }
                        block.clear();
                    }
                }
            }

            int exitCode = proc.waitFor();
            stderrThread.join(2000); // đợi log dội hết

            long elapsed = System.currentTimeMillis() - start;
            safeListener.onComplete(exitCode);

            if (exitCode != 0) {
                throw new ConversionException(
                        "ffmpeg exited with code " + exitCode + ". See logs for details.");
            }

            AspectRatio ar = (req.getAspect() != null)
                    ? AspectRatio.fromLabel(req.getAspect())
                    : AspectRatio.fromLabel(defaultAspect);
            int w = (req.getWidth() != null) ? req.getWidth() : ar.getDefaultWidth();
            int h = (req.getHeight() != null) ? req.getHeight() : ar.getDefaultHeight();

            return new ConversionResult(
                    outputPath,
                    elapsed,
                    String.join(" ", cmd),
                    exitCode,
                    ar.getLabel(),
                    w + "x" + h);
        } catch (IOException e) {
            throw new ConversionException("I/O error while running ffmpeg: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConversionException("Interrupted while running ffmpeg", e);
        }
    }

    /**
     * Probe thời lượng audio bằng ffprobe (không bắt buộc — nếu ffprobe thiếu hoặc
     * audio không đọc được thì trả 0, lúc đó progress bar vẫn chạy nhưng không có %).
     */
    public double probeAudioDuration(String audioPath) {
        try {
            List<String> cmd = List.of(
                    ffprobePath,
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    audioPath);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out;
            try (var br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = br.readLine();
            }
            int code = p.waitFor();
            if (code != 0 || out == null) return 0.0;
            return Double.parseDouble(out.trim());
        } catch (Exception e) {
            log.debug("ffprobe failed for {}: {}", audioPath, e.getMessage());
            return 0.0;
        }
    }

    private static String formatSpeed(String raw) {
        if (raw == null || raw.isBlank() || "N/A".equalsIgnoreCase(raw)) return null;
        if (raw.endsWith("x")) raw = raw.substring(0, raw.length() - 1);
        if (raw.isBlank()) return null;
        return raw + "x";
    }

    /** Overload tiện cho UI: không cần truyền listener. */
    public ConversionResult convert(ConversionRequest req) {
        return convert(req, NOOP_LISTENER);
    }

    // -------------------- command builder --------------------

    /**
     * Build command ffmpeg. Chiến lược filter:
     * <pre>
     *   -i image -loop 1 -i audio
     *   -vf "scale=W:H:force_original_aspect_ratio=decrease,
     *        pad=W:H:(ow-iw)/2:(oh-ih)/2:color=black,
     *        format=yuv420p"
     *   -c:v libx264 -preset X -crf Y -pix_fmt yuv420p
     *   -c:a aac -b:a 192k
     *   -shortest -movflags +faststart
     * </pre>
     * Scale = "decrease" để ảnh không bị crop — luôn nằm gọn trong khung,
     * phần thừa lấp đầy bằng đệm đen (hoặc đổi color nếu bạn muốn).
     */
    private List<String> buildCommand(ConversionRequest req, Path outputPath) {
        AspectRatio ar = (req.getAspect() != null)
                ? AspectRatio.fromLabel(req.getAspect())
                : AspectRatio.fromLabel(defaultAspect);

        int w = (req.getWidth() != null) ? req.getWidth() : ar.getDefaultWidth();
        int h = (req.getHeight() != null) ? req.getHeight() : ar.getDefaultHeight();

        // Resolution mặc định theo aspect — nếu width/height không khớp thì vẫn dùng đúng.
        if (req.getAspect() != null && req.getWidth() == null && req.getHeight() == null) {
            String[] dims = resolveDims(req.getAspect()).split("x");
            w = Integer.parseInt(dims[0]);
            h = Integer.parseInt(dims[1]);
        }

        int crf = (req.getCrf() != null) ? req.getCrf() : defaultCrf;
        String preset = (req.getPreset() != null && !req.getPreset().isBlank()) ? req.getPreset() : defaultPreset;
        String audioBitrate = (req.getAudioBitrate() != null && !req.getAudioBitrate().isBlank())
                ? req.getAudioBitrate() : defaultAudioBitrate;
        boolean overwrite = (req.getOverwrite() == null) ? true : req.getOverwrite();
        Integer requestedDuration = req.getDuration();

        // Khi có duration cố định: audio loop vô hạn, video giới hạn bởi -t.
        // Khi không có: ảnh loop vô hạn, video kết thúc theo audio (-shortest).
        boolean hasFixedDuration = (requestedDuration != null && requestedDuration > 0);

        String vf = String.format(
                "scale=%d:%d:force_original_aspect_ratio=decrease,"
                        + "pad=%d:%d:(ow-iw)/2:(oh-ih)/2:color=black,"
                        + "format=yuv420p",
                w, h, w, h);

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        if (overwrite) cmd.add("-y"); else cmd.add("-n");
        if (threads > 0) {
            cmd.add("-threads"); cmd.add(String.valueOf(threads));
        }
        cmd.add("-progress"); cmd.add("pipe:1");
        cmd.add("-nostats");
        cmd.add("-loglevel"); cmd.add("info");

        if (hasFixedDuration) {
            // Audio loop vô hạn — ảnh chỉ cần 1 frame (không cần -loop)
            cmd.add("-stream_loop"); cmd.add("-1");
            cmd.add("-i"); cmd.add(req.getAudioPath());
            cmd.add("-framerate"); cmd.add("2");
            cmd.add("-i"); cmd.add(req.getImagePath());
            // Giới hạn video theo thời gian cố định
            cmd.add("-t"); cmd.add(String.valueOf(requestedDuration));
        } else {
            // Ảnh loop vô hạn — video kết thúc khi audio hết
            cmd.add("-loop"); cmd.add("1");
            cmd.add("-framerate"); cmd.add("2");
            cmd.add("-i"); cmd.add(req.getImagePath());
            cmd.add("-i"); cmd.add(req.getAudioPath());
        }

        // Video encode
        cmd.add("-vf"); cmd.add(vf);
        cmd.add("-c:v"); cmd.add("libx264");
        cmd.add("-preset"); cmd.add(preset);
        cmd.add("-crf"); cmd.add(String.valueOf(crf));
        cmd.add("-pix_fmt"); cmd.add("yuv420p");

        // Audio encode (aac) — luôn re-encode để tương thích tối đa với mp4.
        cmd.add("-c:a"); cmd.add("aac");
        cmd.add("-b:a"); cmd.add(audioBitrate);

        if (!hasFixedDuration) {
            // Chỉ dùng -shortest khi không có duration cố định
            cmd.add("-shortest");
        }
        cmd.add("-movflags"); cmd.add("+faststart");

        cmd.add(outputPath.toString());
        return cmd;
    }

    private String resolveDims(String aspectLabel) {
        try {
            AspectRatio ar = AspectRatio.fromLabel(aspectLabel);
            return ar.getDefaultWidth() + "x" + ar.getDefaultHeight();
        } catch (Exception e) {
            // Fallback: parse WxH literal nếu không phải aspect chuẩn
            return aspectLabel.contains("x") ? aspectLabel : defaultResLandscape;
        }
    }

    // -------------------- helpers --------------------

    private void assertReadable(String path, String kind) {
        if (path == null || path.isBlank()) {
            throw new ConversionException(kind + " path is empty");
        }
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            throw new ConversionException(kind + " file not found: " + path);
        }
        if (!Files.isReadable(p)) {
            throw new ConversionException(kind + " file is not readable: " + path);
        }
    }

    /**
     * Bắt chuỗi {@code speed=1.23x} từ log ffmpeg.
     * @deprecated giữ lại cho source compat — implementation đã chuyển sang parse
     *             block {@code -progress pipe:1} trong {@link #convert}.
     */
    @Deprecated
    private void parseSpeed(String line, String[] speedHolder) {
        int idx = line.indexOf("speed=");
        if (idx < 0) return;
        try {
            String tail = line.substring(idx + "speed=".length()).trim().split(" ")[0];
            // tail có thể kết thúc bằng 'x', 'N/A', v.v. Lấy phần hợp lệ.
            if (tail.endsWith("x")) tail = tail.substring(0, tail.length() - 1);
            if (tail.isBlank() || "N/A".equalsIgnoreCase(tail)) return;
            speedHolder[0] = tail + "x";
        } catch (Exception ignore) {}
    }

    /** Dùng cho {@link #verifyFfmpeg()}: chạy và trả toàn bộ stdout. */
    private String runAndCapture(List<String> cmd, ProgressListener listener)
            throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (var br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
                if (listener != null) listener.onLogLine(line);
            }
        }
        p.waitFor(10, TimeUnit.SECONDS);
        return sb.toString();
    }
}
