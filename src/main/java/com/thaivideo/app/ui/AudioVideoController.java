package com.thaivideo.app.ui;

import com.thaivideo.app.model.AspectRatio;
import com.thaivideo.app.model.AudioVideoRequest;
import com.thaivideo.app.model.ConversionResult;
import com.thaivideo.app.service.FfmpegService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Controller cho tab "Audio & Video" — ghép 1 audio + 1 video intro (phát 1 lần)
 * + 1 video loop (lặp cho đến khi tổng thời lượng = audio duration).
 *
 * <p>Được Spring quản lý nhờ controllerFactory của FxApplication — inject thủ công
 * vào FXML con bởi {@link MainController}.</p>
 */
@Component
public class AudioVideoController {

    // ---- Inputs ----
    @FXML private TextField audioPathField;
    @FXML private TextField introPathField;
    @FXML private TextField loopPathField;
    @FXML private TextField outputPathField;

    @FXML private ComboBox<String> aspectCombo;
    @FXML private ComboBox<String> presetCombo;

    @FXML private TextField widthField;
    @FXML private TextField heightField;
    @FXML private TextField crfField;
    @FXML private TextField audioBitrateField;
    @FXML private TextField durationField;

    @FXML private CheckBox overwriteCheck;

    // ---- Action buttons ----
    @FXML private Button browseAudioBtn;
    @FXML private Button browseIntroBtn;
    @FXML private Button browseLoopBtn;
    @FXML private Button browseOutputBtn;
    @FXML private Button convertBtn;
    @FXML private Button cancelBtn;

    // ---- Progress / log ----
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
    @FXML private Label statusLabel;
    @FXML private TextArea logArea;
    @FXML private HBox progressBox;

    @Autowired
    private FfmpegService ffmpegService;

    @Value("${video.default-aspect:16:9}")
    private String defaultAspect;

    @Value("${video.default-preset:medium}")
    private String defaultPreset;

    private volatile Task<ConversionResult> runningTask;

    @FXML
    public void initialize() {
        // Aspect ratio
        aspectCombo.setItems(FXCollections.observableArrayList(
                AspectRatio.LANDSCAPE_16_9.getLabel(),
                AspectRatio.PORTRAIT_9_16.getLabel()
        ));
        aspectCombo.setValue(safeAspect(defaultAspect));
        aspectCombo.setOnAction(e -> applyResolutionForSelectedAspect());

        // Preset
        presetCombo.setItems(FXCollections.observableArrayList(
                "ultrafast", "superfast", "veryfast", "faster",
                "fast", "medium", "slow", "slower", "veryslow"
        ));
        presetCombo.setValue(defaultPreset);

        // Defaults
        crfField.setText("23");
        audioBitrateField.setText("192k");
        overwriteCheck.setSelected(true);
        durationField.setText(""); // trống = dùng ffprobe

        // Apply default aspect resolution on first load
        applyResolutionForSelectedAspect();

        // Gợi ý tên file output theo thời gian
        outputPathField.setText(defaultOutputName());

        progressBar.setProgress(0);
        progressLabel.setText("0%");
        statusLabel.setText("Ready.");
        progressBox.setVisible(false);
        cancelBtn.setDisable(true);
    }

    // ---------------- Handlers ----------------

    @FXML
    public void onBrowseAudio() {
        File f = chooseFile("Choose audio", FileChooserExt.audioFilter());
        if (f != null) audioPathField.setText(f.getAbsolutePath());
    }

    @FXML
    public void onBrowseIntro() {
        File f = chooseFile("Choose intro video", FileChooserExt.videoFilter());
        if (f != null) {
            introPathField.setText(f.getAbsolutePath());
            suggestOutputFromVideo(f);
        }
    }

    @FXML
    public void onBrowseLoop() {
        File f = chooseFile("Choose loop video", FileChooserExt.videoFilter());
        if (f != null) loopPathField.setText(f.getAbsolutePath());
    }

    private void suggestOutputFromVideo(File videoFile) {
        File parent = videoFile.getParentFile();
        if (parent == null) return;
        outputPathField.setText(new File(parent, defaultOutputName(videoFile)).getAbsolutePath());
    }

    @FXML
    public void onBrowseOutput() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Choose output folder");
        File initial = guessInitialDir(outputPathField.getText());
        if (initial != null) dc.setInitialDirectory(initial);
        File dir = dc.showDialog(browseOutputBtn.getScene().getWindow());
        if (dir != null) {
            outputPathField.setText(new File(dir, defaultOutputName()).getAbsolutePath());
        }
    }

    @FXML
    public void onClearLog() {
        logArea.clear();
    }

    @FXML
    public void onCopyLog() {
        javafx.scene.input.Clipboard cb = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(logArea.getText());
        cb.setContent(content);
        statusLabel.setText("Log copied to clipboard.");
    }

    @FXML
    public void onConvert() {
        try {
            AudioVideoRequest req = buildRequestFromForm();
            validateRequest(req);

            logArea.clear();
            progressBar.setProgress(0);
            progressLabel.setText("0%");
            progressBox.setVisible(true);
            convertBtn.setDisable(true);
            cancelBtn.setDisable(false);
            statusLabel.setText("Composing...");
            appendLog("=== START " + java.time.LocalDateTime.now() + " ===");
            appendLog("Output: " + req.getOutputPath());

            double[] lastLoggedPct = new double[]{ -1.0 };

            FfmpegService.ProgressListener listener = new FfmpegService.ProgressListener() {
                @Override public void onLogLine(String line) {
                    Platform.runLater(() -> appendLog(line));
                }
                @Override public void onProgress(double fraction, String speed) {
                    Platform.runLater(() -> {
                        if (fraction >= 0) {
                            progressBar.setProgress(fraction);
                            int pct = (int) Math.round(fraction * 100);
                            String label = (speed != null)
                                    ? pct + "%  (" + speed + ")"
                                    : pct + "%";
                            progressLabel.setText(label);
                            int step = (speed != null) ? 5 : 1;
                            if (pct / step != (int) lastLoggedPct[0] / step) {
                                lastLoggedPct[0] = pct;
                                appendLog("[progress] " + label);
                            }
                        }
                    });
                }
                @Override public void onComplete(int exitCode) {
                    Platform.runLater(() -> {
                        if (exitCode == 0) {
                            progressBar.setProgress(1.0);
                            progressLabel.setText("100%");
                            statusLabel.setText("Done! Saved to: " + req.getOutputPath());
                        } else {
                            statusLabel.setText("ffmpeg exited with code " + exitCode);
                        }
                        appendLog("=== END (exit=" + exitCode + ") ===");
                    });
                }
            };

            runningTask = new Task<>() {
                @Override
                protected ConversionResult call() throws Exception {
                    return ffmpegService.compose(req, listener);
                }
            };
            runningTask.setOnSucceeded(ev -> {
                ConversionResult r = runningTask.getValue();
                appendLog("[OK] Exit " + r.getExitCode()
                        + " | Aspect " + r.getAspect()
                        + " | Resolution " + r.getResolution()
                        + " | Took " + r.getDurationMs() + " ms");
                finishUi();
            });
            runningTask.setOnFailed(ev -> {
                Throwable t = runningTask.getException();
                appendLog("[ERROR] " + (t != null ? t.getMessage() : "Unknown"));
                statusLabel.setText("Failed.");
                finishUi();
            });
            runningTask.setOnCancelled(ev -> {
                appendLog("[CANCELLED]");
                statusLabel.setText("Cancelled.");
                finishUi();
            });

            Thread t = new Thread(runningTask, "ffmpeg-compose");
            t.setDaemon(true);
            t.start();

        } catch (Exception ex) {
            showError("Invalid input", ex.getMessage());
        }
    }

    @FXML
    public void onCancel() {
        if (runningTask != null && runningTask.isRunning()) {
            runningTask.cancel(true);
            killFfmpegProcessTree();
        }
    }

    // ---------------- Helpers ----------------

    private File chooseFile(String title, javafx.stage.FileChooser.ExtensionFilter filter) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        File initial = guessInitialDir(audioPathField.getText());
        if (initial != null) fc.setInitialDirectory(initial);
        fc.getExtensionFilters().add(filter);
        fc.getExtensionFilters().add(FileChooserExt.allFilter());
        return fc.showOpenDialog(browseAudioBtn.getScene().getWindow());
    }

    private File guessInitialDir(String path) {
        try {
            if (path != null && !path.isBlank()) {
                File f = new File(path);
                File parent = f.isDirectory() ? f : f.getParentFile();
                if (parent != null && parent.exists()) return parent;
            }
        } catch (Exception ignore) {}
        return new File(System.getProperty("user.home"));
    }

    private void applyResolutionForSelectedAspect() {
        try {
            AspectRatio ar = AspectRatio.fromLabel(aspectCombo.getValue());
            widthField.setText(String.valueOf(ar.getDefaultWidth()));
            heightField.setText(String.valueOf(ar.getDefaultHeight()));
        } catch (Exception ignore) {}
    }

    private AudioVideoRequest buildRequestFromForm() {
        AudioVideoRequest req = new AudioVideoRequest();
        req.setAudioPath(audioPathField.getText());
        req.setIntroPath(introPathField.getText());
        req.setLoopPath(loopPathField.getText());
        req.setOutputPath(outputPathField.getText());
        req.setAspect(aspectCombo.getValue());
        req.setPreset(presetCombo.getValue());
        req.setCrf(parseIntOrNull(crfField.getText()));
        req.setWidth(parseIntOrNull(widthField.getText()));
        req.setHeight(parseIntOrNull(heightField.getText()));
        req.setAudioBitrate(audioBitrateField.getText());
        req.setOverwrite(overwriteCheck.isSelected());
        req.setDuration(parseIntOrNull(durationField.getText()));
        return req;
    }

    private void validateRequest(AudioVideoRequest req) throws Exception {
        requireField(req.getAudioPath(), "Audio path");
        requireField(req.getIntroPath(), "Intro path");
        requireField(req.getLoopPath(), "Loop path");
        requireField(req.getOutputPath(), "Output path");
        File audio = new File(req.getAudioPath());
        File intro = new File(req.getIntroPath());
        File loop  = new File(req.getLoopPath());
        File out   = new File(req.getOutputPath());
        if (!audio.exists()) throw new Exception("Audio not found: " + audio.getAbsolutePath());
        if (!intro.exists()) throw new Exception("Intro video not found: " + intro.getAbsolutePath());
        if (!loop.exists())  throw new Exception("Loop video not found: " + loop.getAbsolutePath());
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new Exception("Cannot create output folder: " + parent.getAbsolutePath());
        }
        if (!req.getOutputPath().toLowerCase().matches(".*\\.(mp4|mov|mkv|webm)$")) {
            throw new Exception("Output file must end in .mp4 / .mov / .mkv / .webm");
        }
        if (req.getDuration() != null && req.getDuration() <= 0) {
            throw new Exception("Durations must be > 0 (leave blank to use audio length).");
        }
    }

    private void requireField(String v, String name) throws Exception {
        if (v == null || v.isBlank()) throw new Exception(name + " is required");
    }

    private Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private void showError(String header, String content) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("Error");
        a.setHeaderText(header);
        a.setContentText(content);
        a.showAndWait();
    }

    private void appendLog(String line) {
        logArea.appendText(line + System.lineSeparator());
    }

    private void finishUi() {
        convertBtn.setDisable(false);
        cancelBtn.setDisable(true);
    }

    private String defaultOutputName() {
        return defaultOutputName(null);
    }

    /**
     * Sinh tên file output mặc định:
     * <ul>
     *     <li>có video reference → {@code videoStem_composed.mp4}</li>
     *     <li>không có → {@code video_yyyyMMdd_HHmmss.mp4}</li>
     * </ul>
     */
    private String defaultOutputName(File videoFile) {
        if (videoFile != null) {
            String stem = stripExtension(videoFile.getName());
            if (stem != null && !stem.isBlank()) {
                return stem + "_composed.mp4";
            }
        }
        return "video_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".mp4";
    }

    private String stripExtension(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        return (dot <= 0) ? filename : filename.substring(0, dot);
    }

    private String safeAspect(String s) {
        try { return AspectRatio.fromLabel(s).getLabel(); }
        catch (Exception e) { return AspectRatio.LANDSCAPE_16_9.getLabel(); }
    }

    private void killFfmpegProcessTree() {
        try {
            ProcessHandle.allProcesses()
                    .filter(p -> p.info().command().map(
                            c -> c.toLowerCase().contains("ffmpeg")).orElse(false))
                    .forEach(ProcessHandle::destroyForcibly);
        } catch (Exception ignore) {}
    }
}