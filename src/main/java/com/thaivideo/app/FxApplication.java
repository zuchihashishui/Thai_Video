package com.thaivideo.app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;

/**
 * JavaFX Application — tách riêng khỏi {@link ImageAudioToVideoApplication} để
 * tránh đụng tên với {@code javafx.application.Application}.
 *
 * <p>Spring Boot context được inject từ bên ngoài (qua
 * {@link #setSpringContext(ConfigurableApplicationContext)}) trước khi
 * {@link #launchStandalone(String[])} chạy, để FXML controller có thể nhận
 * bean Spring qua {@code loader.setControllerFactory(ctx::getBean)}.</p>
 */
public class FxApplication extends Application {

    private static ConfigurableApplicationContext springContext;
    private static String[] launchArgs = new String[0];

    public static void setSpringContext(ConfigurableApplicationContext ctx) {
        springContext = ctx;
    }

    /**
     * Tiện cho entry point Java chính — gọi {@link Application#launch(String...)}
     * mà không phải reference trực tiếp class con từ command-line.
     */
    public static void launchStandalone(String[] args) {
        launchArgs = (args == null) ? new String[0] : args;
        Application.launch(FxApplication.class, launchArgs);
    }

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(FxApplication.class.getResource("/fxml/main.fxml"));
        loader.setControllerFactory(springContext::getBean);

        Parent root = loader.load();
        Scene scene = new Scene(root, 980, 760);

        var css = FxApplication.class.getResource("/css/app.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }

        stage.setTitle("Image + Audio → Video (FFmpeg)");
        stage.setScene(scene);
        stage.setMinWidth(820);
        stage.setMinHeight(620);
        stage.show();

        stage.setOnCloseRequest(e -> {
            // user đóng cửa sổ → thoát JavaFX thread
            Platform.exit();
        });
    }

    @Override
    public void stop() {
        // Khi JavaFX đã thoát, đảm bảo Spring cũng được close.
        if (springContext != null && springContext.isActive()) {
            springContext.close();
        }
    }
}
