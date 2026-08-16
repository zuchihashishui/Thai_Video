package com.thaivideo.app.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Tham số yêu cầu ghép audio + video intro + video loop thành 1 video duy nhất.
 *
 * <ul>
 *     <li>{@code audioPath}: file audio làm soundtrack xuyên suốt video đầu ra.</li>
 *     <li>{@code introPath}: video phát 1 lần ở đầu (không lặp).</li>
 *     <li>{@code loopPath}: video được lặp cho tới khi tổng thời lượng
 *         (intro + loop&times;K) &ge; duration của audio.</li>
 *     <li>{@code outputPath}: file MP4 đầu ra.</li>
 *     <li>Các field còn lại là tùy chọn — null/missing → dùng giá trị
 *         mặc định trong {@code application.properties}.</li>
 * </ul>
 */
public class AudioVideoRequest {

    @NotBlank(message = "audioPath is required")
    private String audioPath;

    @NotBlank(message = "introPath is required")
    private String introPath;

    @NotBlank(message = "loopPath is required")
    private String loopPath;

    @NotBlank(message = "outputPath is required")
    private String outputPath;

    private String aspect;        // "16:9" hoặc "9:16" — null → default từ config
    private Integer width;        // override độ phân giải
    private Integer height;
    private Integer crf;          // 0–51, mặc định 23
    private String preset;        // ultrafast, fast, medium, slow, veryslow
    private String audioBitrate;  // ví dụ "192k"
    private Boolean overwrite;    // true → -y, false → -n

    /**
     * Tổng thời lượng output mong muốn (giây). Nếu {@code null} hoặc {@code <= 0}
     * → dùng ffprobe để lấy duration thật của file audio. Nếu &gt; 0 → đây là
     * tổng thời lượng của video đầu ra, audio sẽ được lặp để khớp.
     *
     * <p>Đồng bộ với {@code ConversionRequest.duration} của tab "Audio & Image":
     * cùng là "Durations" — để trống = audio length, nhập = override.</p>
     */
    private Integer duration;

    public AudioVideoRequest() {}

    public String getAudioPath() { return audioPath; }
    public void setAudioPath(String v) { this.audioPath = v; }

    public String getIntroPath() { return introPath; }
    public void setIntroPath(String v) { this.introPath = v; }

    public String getLoopPath() { return loopPath; }
    public void setLoopPath(String v) { this.loopPath = v; }

    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String v) { this.outputPath = v; }

    public String getAspect() { return aspect; }
    public void setAspect(String v) { this.aspect = v; }

    public Integer getWidth() { return width; }
    public void setWidth(Integer v) { this.width = v; }

    public Integer getHeight() { return height; }
    public void setHeight(Integer v) { this.height = v; }

    public Integer getCrf() { return crf; }
    public void setCrf(Integer v) { this.crf = v; }

    public String getPreset() { return preset; }
    public void setPreset(String v) { this.preset = v; }

    public String getAudioBitrate() { return audioBitrate; }
    public void setAudioBitrate(String v) { this.audioBitrate = v; }

    public Boolean getOverwrite() { return overwrite; }
    public void setOverwrite(Boolean v) { this.overwrite = v; }

    public Integer getDuration() { return duration; }
    public void setDuration(Integer v) { this.duration = v; }
}