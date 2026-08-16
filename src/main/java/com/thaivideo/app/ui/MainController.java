package com.thaivideo.app.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Shell controller — chỉ quản lý {@link TabPane} và inject các controller con
 * của từng tab. Logic chi tiết của từng chức năng nằm ở controller riêng
 * (ví dụ {@link ImageAudioController} và {@link AudioVideoController}).
 *
 * <p>Để Spring autowire chính xác, controller con phải có annotation
 * {@code @Component} — FXMLLoader khi load file con cần được cấu hình
 * dùng cùng {@code controllerFactory} với Spring context.</p>
 */
@Component
public class MainController {

    @FXML private TabPane tabPane;

    private final ImageAudioController imageAudioController;
    private final AudioVideoController audioVideoController;

    @Autowired
    public MainController(ImageAudioController imageAudioController,
                          AudioVideoController audioVideoController) {
        this.imageAudioController = imageAudioController;
        this.audioVideoController = audioVideoController;
    }

    @FXML
    public void initialize() {
        // Tab 1: Image + Audio → Video
        Tab imageAudioTab = tabPane.getTabs().get(0);
        imageAudioTab.setContent(loadTabContent("tab_image_audio.fxml", imageAudioController));

        // Tab 2: Audio + Intro video + Loop video → 1 video duy nhất
        if (tabPane.getTabs().size() > 1) {
            Tab audioVideoTab = tabPane.getTabs().get(1);
            audioVideoTab.setContent(loadTabContent("tab_audio_video.fxml", audioVideoController));
        }
    }

    /**
     * Load FXML của một tab, gắn controller đã được Spring inject.
     * Controller cho FXML con sẽ được set thủ công — không cần khai báo
     * {@code fx:controller} trong file FXML.
     */
    private Node loadTabContent(String resourcePath, Object controller) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/fxml/" + resourcePath));
            loader.setController(controller);
            return loader.load();
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Failed to load /fxml/" + resourcePath, ex);
        }
    }
}