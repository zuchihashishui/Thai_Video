package com.thaivideo.app.exception;

/**
 * Lỗi khi convert video — wrapper quanh lỗi từ FFmpeg hoặc hệ thống.
 */
public class ConversionException extends RuntimeException {
    public ConversionException(String message) {
        super(message);
    }
    public ConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
