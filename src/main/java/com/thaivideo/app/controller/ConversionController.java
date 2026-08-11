package com.thaivideo.app.controller;

import com.thaivideo.app.exception.ConversionException;
import com.thaivideo.app.model.ConversionRequest;
import com.thaivideo.app.model.ConversionResult;
import com.thaivideo.app.service.FfmpegService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API tiện cho ai muốn chạy headless (CLI / script / frontend web).
 * JavaFX UI cũng gọi qua endpoint này thông qua FfmpegApiClient.
 */
@RestController
@RequestMapping("/v1/video")
public class ConversionController {

    private final FfmpegService ffmpegService;

    @Autowired
    public ConversionController(FfmpegService ffmpegService) {
        this.ffmpegService = ffmpegService;
    }

    @PostMapping("/convert")
    public ResponseEntity<ConversionResult> convert(@Valid @RequestBody ConversionRequest req) {
        ConversionResult result = ffmpegService.convert(req);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "image-audio-to-video");
    }

    // ----- Error handlers -----

    @ExceptionHandler(ConversionException.class)
    public ResponseEntity<Map<String, Object>> handleConversion(ConversionException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                Map.of("error", "conversion_failed", "message", ex.getMessage())
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "validation_failed");
        Map<String, String> fields = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> fields.put(fe.getField(), fe.getDefaultMessage()));
        body.put("fields", fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegal(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(
                Map.of("error", "bad_request", "message", ex.getMessage())
        );
    }
}
