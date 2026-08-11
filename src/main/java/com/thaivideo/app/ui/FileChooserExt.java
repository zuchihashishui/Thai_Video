package com.thaivideo.app.ui;

import javafx.stage.FileChooser;

/**
 * Helper gom các ExtensionFilter dùng nhiều lần.
 */
public final class FileChooserExt {
    private FileChooserExt() {}

    public static FileChooser.ExtensionFilter imageFilter() {
        return new FileChooser.ExtensionFilter(
                "Image files",
                "*.jpg", "*.jpeg", "*.png", "*.bmp", "*.webp", "*.tif", "*.tiff");
    }

    public static FileChooser.ExtensionFilter audioFilter() {
        return new FileChooser.ExtensionFilter(
                "Audio files",
                "*.mp3", "*.wav", "*.m4a", "*.aac", "*.flac", "*.ogg", "*.opus");
    }

    public static FileChooser.ExtensionFilter allFilter() {
        return new FileChooser.ExtensionFilter("All files", "*.*");
    }
}
