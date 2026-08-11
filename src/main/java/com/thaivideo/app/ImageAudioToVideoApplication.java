package com.thaivideo.app;

import javafx.application.Platform;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Spring Boot entry point — chạy Spring container, sau đó launch JavaFX UI trên cùng
 * context để UI có thể gọi các bean (FfmpegService, ...) trực tiếp qua Spring DI.
 *
 * <p>Class JavaFX thực sự là {@link FxApplication} (tránh đụng tên với
 * {@code javafx.application.Application}).</p>
 */
@SpringBootApplication
public class ImageAudioToVideoApplication {

    private static ConfigurableApplicationContext springContext;

    public static void main(String[] args) {
        boolean uiOnly = hasFlag(args, "--fx");
        boolean serverOnly = hasFlag(args, "--server-only");

        startSpring(args);

        if (serverOnly) {
            // Không launch JavaFX — chỉ giữ Spring + REST.
            installShutdownHook();
            return;
        }

        FxApplication.setSpringContext(springContext);
        // Suppress nếu cần — chạy UI mặc định
        if (!uiOnly) {
            Platform.startup(() -> { /* no-op, ensure toolkit */ });
        }
        FxApplication.launchStandalone(args);
        shutdownSpring();
    }

    private static void startSpring(String[] args) {
        springContext = SpringApplication.run(ImageAudioToVideoApplication.class, args);
        installShutdownHook();
    }

    private static void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdownSpring(),
                "spring-shutdown"));
    }

    private static void shutdownSpring() {
        if (springContext != null && springContext.isActive()) {
            springContext.close();
        }
    }

    private static boolean hasFlag(String[] args, String flag) {
        if (args == null) return false;
        for (String a : args) {
            if (flag.equalsIgnoreCase(a)) return true;
        }
        return false;
    }

    public static ConfigurableApplicationContext getSpringContext() {
        return springContext;
    }
}
