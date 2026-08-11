package com.thaivideo.app.service;

import com.thaivideo.app.model.ConversionRequest;
import com.thaivideo.app.model.ConversionResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Client gọi REST API của chính ứng dụng — dùng cho JavaFX UI muốn dùng HTTP,
 * hoặc cho người dùng gọi từ xa. JavaFX cũng có thể inject trực tiếp
 * {@link FfmpegService} nếu muốn bỏ qua HTTP.
 *
 * <p>Không phụ thuộc Jackson — encode JSON thủ công và parse tối thiểu.</p>
 */
@Component
public class FfmpegApiClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ConversionResult convert(ConversionRequest req, String baseUrl)
            throws Exception {
        String body = toJson(req);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + "/api/v1/video/convert"))
                .timeout(Duration.ofMinutes(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return parseResult(resp.body());
    }

    // ---- minimal JSON helpers ----

    private String toJson(ConversionRequest r) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"imagePath\":").append(json(r.getImagePath())).append(",");
        sb.append("\"audioPath\":").append(json(r.getAudioPath())).append(",");
        sb.append("\"outputPath\":").append(json(r.getOutputPath())).append(",");
        sb.append("\"aspect\":").append(json(r.getAspect())).append(",");
        sb.append("\"width\":").append(r.getWidth()).append(",");
        sb.append("\"height\":").append(r.getHeight()).append(",");
        sb.append("\"crf\":").append(r.getCrf()).append(",");
        sb.append("\"preset\":").append(json(r.getPreset())).append(",");
        sb.append("\"audioBitrate\":").append(json(r.getAudioBitrate())).append(",");
        sb.append("\"overwrite\":").append(r.getOverwrite());
        sb.append("}");
        return sb.toString();
    }

    private static String json(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append("\"").toString();
    }

    /**
     * Parse rất tối thiểu các trường chính trong response JSON — đủ dùng cho internal call.
     */
    private ConversionResult parseResult(String body) {
        String outputPath = extract(body, "outputPath");
        String command   = extract(body, "commandLine");
        String aspect    = extract(body, "aspect");
        String resolution= extract(body, "resolution");
        int exitCode     = Integer.parseInt(extract(body, "exitCode"));
        long durationMs  = Long.parseLong(extract(body, "durationMs"));
        return new ConversionResult(
                java.nio.file.Paths.get(outputPath),
                durationMs, command, exitCode, aspect, resolution);
    }

    private static String extract(String body, String key) {
        String needle = "\"" + key + "\":";
        int i = body.indexOf(needle);
        if (i < 0) throw new IllegalStateException("Missing JSON field: " + key);
        int start = i + needle.length();
        // skip leading whitespace
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) start++;
        char first = body.charAt(start);
        if (first == '"') {
            int end = body.indexOf('"', start + 1);
            while (body.charAt(end - 1) == '\\') {
                end = body.indexOf('"', end + 1);
            }
            return body.substring(start + 1, end);
        } else {
            int end = start;
            while (end < body.length() && "0123456789-.".indexOf(body.charAt(end)) >= 0) end++;
            return body.substring(start, end);
        }
    }

    private String stripTrailingSlash(String s) {
        if (s == null) return "http://localhost:8080";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
