package com.thaivideo.app.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Tham số yêu cầu chuyển đổi ảnh + audio → video.
 *
 * <ul>
 *     <li>{@code imagePath}: đường d�n tuyệt đối tới file ảnh (jpg, png, ...)</li>
 *     <li>{@code audioPath}: đường dẫn tuyệt đối tới file audio (mp3, wav, m4a, ...)</li>
 *     <li>{@code outputPath}: đường dẫn file video đầu ra (thường .mp4)</li>
 *     <li>{@code aspect}: tỷ lệ khung hình — mặc định 16:9 nếu null</li>
 *     <li>Các tham số còn lại là tùy chọn; null/missing → dùng giá trị mặc định ở application.properties</li>
 * </ul>
 */
public class ConversionRequest {

    @NotBlank(message = "imagePath is required")
    private String imagePath;

    @NotBlank(message = "audioPath is required")
    private String audioPath;

    @NotBlank(message = "outputPath is required")
    private String outputPath;

    private String aspect;        // "16:9" hoặc "9:16" — null → default từ config
    private Integer width;        // override độ phân giải
    private Integer height;
    private Integer crf;          // 0–51, mặc định 23
    private String preset;        // ultrafast, fast, medium, slow, veryslow
    private String audioBitrate;  // ví dụ "192k"
    private Boolean overwrite;    // true → -y, false → -n

    public ConversionRequest() {}

    public String getImagePath() { return imagePath; }
    public void setImagePath(String v) { this.imagePath = v; }

    public String getAudioPath() { return audioPath; }
    public void setAudioPath(String v) { this.audioPath = v; }

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

    @Positive
    public String getAudioBitrate() { return audioBitrate; }
    public void setAudioBitrate(String v) { this.audioBitrate = v; }

    public Boolean getOverwrite() { return overwrite; }
    public void setOverwrite(Boolean v) { this.overwrite = v; }
}
