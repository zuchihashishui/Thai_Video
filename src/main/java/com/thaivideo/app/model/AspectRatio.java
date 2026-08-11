package com.thaivideo.app.model;

/**
 * Tỷ lệ khung hình cho video đầu ra. Mỗi enum value đã đính kèm độ phân giải mặc định
 * phù hợp với tỷ lệ — có thể override qua {@link com.thaivideo.app.service.ConversionRequest}.
 */
public enum AspectRatio {
    LANDSCAPE_16_9("16:9", 1280, 720, "Landscape — YouTube / desktop"),
    PORTRAIT_9_16("9:16", 720, 1280, "Portrait — TikTok / Reels / Shorts");

    private final String label;
    private final int defaultWidth;
    private final int defaultHeight;
    private final String description;

    AspectRatio(String label, int w, int h, String description) {
        this.label = label;
        this.defaultWidth = w;
        this.defaultHeight = h;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public int getDefaultWidth() {
        return defaultWidth;
    }

    public int getDefaultHeight() {
        return defaultHeight;
    }

    public String getDescription() {
        return description;
    }

    public static AspectRatio fromLabel(String label) {
        if (label == null) return LANDSCAPE_16_9;
        String cleaned = label.trim().replace(" ", "");
        for (AspectRatio r : values()) {
            if (r.label.equalsIgnoreCase(cleaned) || r.name().equalsIgnoreCase(cleaned)) {
                return r;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported aspect ratio '" + label + "'. Allowed: 16:9, 9:16");
    }
}
