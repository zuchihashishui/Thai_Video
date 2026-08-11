package com.thaivideo.app;

import com.thaivideo.app.model.AspectRatio;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AspectRatioTest {

    @Test
    void fromLabel_handlesColonsAndCasing() {
        assertEquals(AspectRatio.LANDSCAPE_16_9, AspectRatio.fromLabel("16:9"));
        assertEquals(AspectRatio.LANDSCAPE_16_9, AspectRatio.fromLabel("16 : 9"));
        assertEquals(AspectRatio.LANDSCAPE_16_9, AspectRatio.fromLabel("LANDSCAPE_16_9"));
        assertEquals(AspectRatio.PORTRAIT_9_16, AspectRatio.fromLabel("9:16"));
    }

    @Test
    void fromLabel_rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> AspectRatio.fromLabel("21:9"));
        assertThrows(IllegalArgumentException.class, () -> AspectRatio.fromLabel(""));
    }

    @Test
    void defaultsAreSensible() {
        assertEquals(1280, AspectRatio.LANDSCAPE_16_9.getDefaultWidth());
        assertEquals(720,  AspectRatio.LANDSCAPE_16_9.getDefaultHeight());
        assertEquals(720,  AspectRatio.PORTRAIT_9_16.getDefaultWidth());
        assertEquals(1280, AspectRatio.PORTRAIT_9_16.getDefaultHeight());
    }
}
