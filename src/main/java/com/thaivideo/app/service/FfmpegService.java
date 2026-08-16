package com.thaivideo.app.service;

import com.thaivideo.app.exception.ConversionException;
import com.thaivideo.app.model.AspectRatio;
import com.thaivideo.app.model.AudioVideoRequest;
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
     *
     * @deprecated dùng {@link #probeMediaDuration(String)} thay — alias này chỉ để
     *             giữ source compat với code cũ.
     */
    @Deprecated
    public double probeAudioDuration(String audioPath) {
        return probeMediaDuration(audioPath);
    }

    /**
     * Probe thời lượng của 1 file media bất kỳ (audio hoặc video) bằng ffprobe.
     * Trả 0 nếu probe thất bại (ffprobe thiếu, file không đọc được, ...).
     */
    public double probeMediaDuration(String mediaPath) {
        try {
            List<String> cmd = List.of(
                    ffprobePath,
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    mediaPath);
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
            log.debug("ffprobe failed for {}: {}", mediaPath, e.getMessage());
            return 0.0;
        }
    }

    private static String formatSpeed(String raw) {
        if (raw == null || raw.isBlank() || "N/A".equalsIgnoreCase(raw)) return null;
        if (raw.endsWith("x")) raw = raw.substring(0, raw.length() - 1);
        if (raw.isBlank()) return null;
        return raw + "x";
    }

    // ====================================================================
    //  COMPOSE — ghép audio + intro + loop video thành 1 video duy nhất
    // ====================================================================

    /**
     * Đồng bộ — ghép audio + intro (1 lần) + loop (lặp K lần) thành 1 video.
     * Tổng thời lượng output = {@code audioSeconds} (trừ khi audio ngắn hơn intro
     * thì cap theo audio).
     *
     * <p>Quy ước:</p>
     * <ul>
     *   <li>{@code K = ceil(max(A - I, 0) / L)} — số lần loop, min = 1.</li>
     *   <li>Nếu audio &lt; intro → bỏ phần loop, video chỉ gồm intro
     *       (audio sẽ tắt trước khi intro kết thúc — đúng theo yêu cầu).</li>
     *   <li>Output được re-encode H.264 + AAC, scale/pad intro &amp; loop về cùng khung hình.</li>
     * </ul>
     */
    public ConversionResult compose(AudioVideoRequest req, ProgressListener listener) {
        ProgressListener safeListener = (listener == null) ? NOOP_LISTENER : listener;
        Path outputPath = Paths.get(req.getOutputPath()).toAbsolutePath();

        try {
            assertReadable(req.getAudioPath(), "audio");
            assertReadable(req.getIntroPath(), "intro");
            assertReadable(req.getLoopPath(), "loop");
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }

            // Probe duration audio (có thể bị override bởi user — durations = tổng thời lượng output)
            Integer requestedDuration = req.getDuration();
            double audioSeconds;
            if (requestedDuration != null && requestedDuration > 0) {
                audioSeconds = requestedDuration;
                log.info("Output duration overridden by user request: {}s", fmt(audioSeconds));
            } else {
                audioSeconds = probeMediaDuration(req.getAudioPath());
            }
            double introSeconds = probeMediaDuration(req.getIntroPath());
            double loopSeconds  = probeMediaDuration(req.getLoopPath());

            if (audioSeconds <= 0) {
                throw new ConversionException(
                        "Cannot probe audio duration. Ensure ffprobe exists and file is valid.");
            }
            if (introSeconds <= 0) {
                throw new ConversionException(
                        "Cannot probe intro video duration. Ensure ffprobe exists and file is valid.");
            }
            if (loopSeconds <= 0) {
                throw new ConversionException(
                        "Cannot probe loop video duration. Ensure ffprobe exists and file is valid.");
            }

            // Tính số lần lặp
            double remaining = Math.max(audioSeconds - introSeconds, 0);
            int K = (int) Math.max(1, Math.ceil(remaining / loopSeconds));

            log.info("Compose: audio={}s, intro={}s, loop={}s, K={}",
                    String.format("%.3f", audioSeconds),
                    String.format("%.3f", introSeconds),
                    String.format("%.3f", loopSeconds),
                    K);

            List<String> cmd = buildCompositeCommand(req, audioSeconds, introSeconds, loopSeconds, K, outputPath);
            log.info("Running ffmpeg compose: {}", String.join(" ", cmd));

            Process proc = new ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .start();

            long start = System.currentTimeMillis();
            long lastReportedPct = -1;
            String lastSpeed = null;

            // stderr → log
            Thread stderrThread = new Thread(() -> {
                try (var br = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        safeListener.onLogLine(line);
                    }
                } catch (IOException ignore) {}
            }, "ffmpeg-compose-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            // stdout → progress
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
                        String outTimeUs = block.get("out_time_us");
                        String speedRaw = block.get("speed");
                        if (outTimeUs != null && audioSeconds > 0) {
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
            stderrThread.join(2000);

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

    /** Overload tiện cho UI: không cần truyền listener. */
    public ConversionResult compose(AudioVideoRequest req) {
        return compose(req, NOOP_LISTENER);
    }

    /**
     * Build command ffmpeg filter_complex. Strategy:
     * <pre>
     *   -i audio  -i intro  -i loop
     *   [1:v] scale+pad+fps+format → [v1]            (intro, 1 lần)
     *   [2:v] scale+pad+fps+format,
     *         loop=K-1:size=1:start=0,
     *         trim=duration=audio-intro, setpts      (loop lặp K lần, cắt theo remaining)
     *       → [v2]
     *   [v1][v2] concat=n=2:v=1:a=0 → [v]
     *   [0:a] aresample=44100,aloop=loop=-1:size=2e9,atrim=0:audio,asetpts=PTS-STARTPTS → [a]
     *   -map [v] -map [a] -c:v libx264 -c:a aac
     *   -t audioSeconds -movflags +faststart
     * </pre>
     */
    private List<String> buildCompositeCommand(AudioVideoRequest req,
                                               double audioSeconds,
                                               double introSeconds,
                                               double loopSeconds,
                                               int K,
                                               Path outputPath) {
        AspectRatio ar = (req.getAspect() != null)
                ? AspectRatio.fromLabel(req.getAspect())
                : AspectRatio.fromLabel(defaultAspect);

        int w = (req.getWidth() != null) ? req.getWidth() : ar.getDefaultWidth();
        int h = (req.getHeight() != null) ? req.getHeight() : ar.getDefaultHeight();

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

        // Duration còn lại để loop lấp đầy
        double remaining = Math.max(audioSeconds - introSeconds, 0);
        boolean skipLoop = (remaining <= 0);

        // FPS mặc định 30 để đồng nhất giữa intro & loop
        int fps = 30;

        // Số frame của video loop sau khi ép fps=30. Dùng cho filter `loop:size=...`
        // FFmpeg filter `loop` không hỗ trợ biến nb_frames (parse error) nên phải truyền số cụ thể.
        int loopFrames = Math.max(1, (int) Math.round(loopSeconds * fps));

        // --- Intro filter chain ---
        String introChain = String.format(
                "[1:v]scale=%d:%d:force_original_aspect_ratio=decrease,"
                        + "pad=%d:%d:(ow-iw)/2:(oh-ih)/2:color=black,"
                        + "fps=%d,format=yuv420p,setpts=PTS-STARTPTS[v1]",
                w, h, w, h, fps);

        String filterComplex;
        if (skipLoop) {
            // Audio ngắn hơn intro → chỉ phát intro, không có loop video
            // Audio vẫn lặp để lấp đầy audioSeconds (nếu video ngắn hơn audio thật, atrim sẽ cắt)
            filterComplex = introChain
                    + ";[0:a]aresample=44100,aloop=loop=-1:size=2e9,atrim=0:" + fmt(audioSeconds)
                    + ",asetpts=PTS-STARTPTS[a]";
        } else {
            // Intro + loop lặp K lần (đã trim theo remaining)
            String loopChain = String.format(
                    "[2:v]scale=%d:%d:force_original_aspect_ratio=decrease,"
                            + "pad=%d:%d:(ow-iw)/2:(oh-ih)/2:color=black,"
                            + "fps=%d,format=yuv420p,"
                            + "loop=loop=%d:size=%d:start=0,"
                            + "trim=duration=%s,setpts=PTS-STARTPTS[v2]",
                    w, h, w, h, fps, Math.max(K - 1, 0), loopFrames, fmt(remaining));

            String concatChain = "[v1][v2]concat=n=2:v=1:a=0[v]";
            // Audio lặp vô hạn rồi cắt theo audioSeconds để lấp đầy tổng thời lượng
            String audioChain = String.format(
                    "[0:a]aresample=44100,aloop=loop=-1:size=2e9,atrim=0:%s,asetpts=PTS-STARTPTS[a]",
                    fmt(audioSeconds));

            filterComplex = introChain + ";" + loopChain + ";" + concatChain + ";" + audioChain;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        if (overwrite) cmd.add("-y"); else cmd.add("-n");
        if (threads > 0) {
            cmd.add("-threads"); cmd.add(String.valueOf(threads));
        }
        cmd.add("-progress"); cmd.add("pipe:1");
        cmd.add("-nostats");
        cmd.add("-loglevel"); cmd.add("info");

        // Inputs
        cmd.add("-i"); cmd.add(req.getAudioPath());
        // Video intro & loop: bỏ audio stream ngay từ demuxer (không decode, không map)
        // → output chỉ chứa audio từ file audio + video từ intro/loop
        cmd.add("-i"); cmd.add(req.getIntroPath()); cmd.add("-an");
        cmd.add("-i"); cmd.add(req.getLoopPath());  cmd.add("-an");

        // Filter graph
        cmd.add("-filter_complex"); cmd.add(filterComplex);

        // Map output streams
        cmd.add("-map"); cmd.add("[v]");
        if (skipLoop) {
            cmd.add("-map"); cmd.add("[a]");
        } else {
            cmd.add("-map"); cmd.add("[a]");
        }

        // Encode video
        cmd.add("-c:v"); cmd.add("libx264");
        cmd.add("-preset"); cmd.add(preset);
        cmd.add("-crf"); cmd.add(String.valueOf(crf));
        cmd.add("-pix_fmt"); cmd.add("yuv420p");

        // Encode audio
        cmd.add("-c:a"); cmd.add("aac");
        cmd.add("-b:a"); cmd.add(audioBitrate);

        // Cap thời lượng theo audio
        cmd.add("-t"); cmd.add(fmt(audioSeconds));
        cmd.add("-movflags"); cmd.add("+faststart");

        cmd.add(outputPath.toString());
        return cmd;
    }

    /** Format số giây cho filter ffmpeg — tránh scientific notation. */
    private static String fmt(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.6f", seconds);
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
