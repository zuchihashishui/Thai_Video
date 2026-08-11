package com.thaivideo.app;

import com.thaivideo.app.service.FfmpegService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.main.web-application-type=servlet",
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.javafx.JavafxApplicationAutoConfiguration"
})
class ApplicationContextLoadTest {

    @Autowired
    private FfmpegService ffmpegService;

    @Test
    void contextLoads_andFfmpegServiceBeanIsPresent() {
        assertNotNull(ffmpegService, "FfmpegService bean must be registered by Spring");
    }
}
