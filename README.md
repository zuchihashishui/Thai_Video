# Image + Audio → Video (Spring Boot · JavaFX · FFmpeg)

Desktop app chạy trên **Java 26 + Spring Boot 3.4 + JavaFX 21**, dùng **FFmpeg** để nối
**1 ảnh** với **1 file audio** thành **video MP4** (H.264 + AAC).

Hỗ trợ 2 tỷ lệ khung hình:
- **16:9** (landscape — 1280×720 mặc định)
- **9:16** (portrait — 720×1280 mặc định)

> Mặc định khi mở app là **16:9**.

---

## 1. Yêu cầu môi trường

| Công cụ | Phiên bản đã test |
|---------|-------------------|
| JDK     | **Java 26** (`java -version`) |
| Maven   | 3.9+ |
| FFmpeg  | 8.1 (essentials build) — có trong `PATH` hoặc trỏ biến `FFMPEG_PATH` |
| OS      | Windows 11 (test chính), Linux/macOS cũng OK |

Kiểm tra FFmpeg:
```bash
ffmpeg -version
```
Nếu chưa có, Windows: tải bản *essentials* từ https://www.gyan.dev/ffmpeg/builds/ và thêm `bin\` vào `PATH`.

---

## 2. Cấu trúc project

```
src/main/java/com/thaivideo/app/
├── Application.java           # Spring Boot entry, khởi động Spring + JavaFX
├── FxApplication.java         # JavaFX Application, nối Spring context
├── controller/
│   └── ConversionController.java  # REST API /api/v1/video/convert
├── service/
│   ├── FfmpegService.java     # Build & chạy lệnh ffmpeg
│   └── FfmpegApiClient.java   # Client gọi REST (tuỳ chọn)
├── model/
│   ├── AspectRatio.java       # 16:9 / 9:16
│   ├── ConversionRequest.java # Body request
│   └── ConversionResult.java  # Kết quả
├── ui/
│   ├── MainController.java    # JavaFX controller
│   └── FileChooserExt.java    # Helpers cho FileChooser
└── exception/
    └── ConversionException.java

src/main/resources/
├── application.properties
├── fxml/main.fxml
└── css/app.css
```

---

## 3. Chạy project

### Cách A — qua Maven (khuyến nghị)
```bash
# Cùng lúc khởi động Spring Boot + JavaFX UI
mvn clean javafx:run

# Hoặc chạy qua Spring Boot fat jar sau khi package
mvn clean package
java -jar target/image-audio-to-video.jar
```

### Cách B — chỉ chạy REST API (không UI)
```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server-only
# Server lắng nghe ở http://localhost:8080/api
```

---

## 4. Cách dùng UI

1. **Image**: bấm **Browse…** chọn ảnh (`.jpg .png .webp .bmp …`).
2. **Audio**: bấm **Browse…** chọn nhạc (`.mp3 .wav .m4a .flac .opus .ogg …`).
3. **Output**: bấm **Browse…** chọn thư mục đích — tên file sẽ tự động gợi ý theo
   thời gian, bạn có thể sửa trong ô **Filename** rồi bấm **Apply**.
4. **Aspect**: chọn `16:9` hoặc `9:16` — độ phân giải tự điều chỉnh theo.
5. (Tuỳ chọn) chỉnh **CRF**, **Preset**, **Audio bitrate**, bật/tắt **Overwrite**.
6. Bấm **Convert ▶** — thanh progress + log sẽ chạy realtime.
7. Bấm **Cancel ✖** nếu muốn dừng giữa chừng (sẽ kill tiến trình `ffmpeg.exe`).

### Các preset mặc định

| Trường | Mặc định | Ghi chú |
|--------|----------|---------|
| Aspect | **16:9** | |
| Width × Height | 1280 × 720 | Tự cập nhật khi đổi aspect |
| Preset | `medium` | ultrafast → veryslow |
| CRF | `23` | 18 = near lossless, 28 = nhỏ hơn |
| Audio bitrate | `192k` | AAC |
| Overwrite | bật | `-y` cho ffmpeg |

---

## 5. Cách dùng REST API

```bash
curl -X POST http://localhost:8080/api/v1/video/convert \
  -H "Content-Type: application/json" \
  -d '{
        "imagePath":  "C:/path/to/image.jpg",
        "audioPath":  "C:/path/to/song.mp3",
        "outputPath": "C:/path/to/out.mp4",
        "aspect":     "16:9"
      }'
```

`aspect` chấp nhận `"16:9"` hoặc `"9:16"`. Tất cả các trường khác đều optional.

Response:
```json
{
  "outputPath": "C:\\path\\to\\out.mp4",
  "durationMs": 4321,
  "commandLine": "ffmpeg -y -loop 1 -framerate 2 -i image.jpg -i song.mp3 ...",
  "exitCode": 0,
  "aspect": "16:9",
  "resolution": "1280x720"
}
```

---

## 6. Cấu hình nâng cao

Mọi tham số đều override được trong `src/main/resources/application.properties` hoặc qua biến môi trường:

| Property | Mặc định | Ý nghĩa |
|----------|---------|---------|
| `ffmpeg.path` | `ffmpeg` | Đường dẫn ffmpeg — override bằng env `FFMPEG_PATH` |
| `ffmpeg.threads` | `0` | `0` = tự động |
| `video.default-aspect` | `16:9` | |
| `video.default-resolution-landscape` | `1280x720` | |
| `video.default-resolution-portrait` | `720x1280` | |
| `video.default-crf` | `23` | |
| `video.default-preset` | `medium` | |
| `video.default-audio-bitrate` | `192k` | |

Ví dụ override bằng env (Windows PowerShell):
```powershell
$env:FFMPEG_PATH="C:\ffmpeg\bin\ffmpeg.exe"
mvn clean javafx:run
```

---

## 7. Ghi chú kỹ thuật

- Ảnh được `loop 1` và scale xuống khung hình bằng filter
  `scale=W:H:force_original_aspect_ratio=decrease, pad=W:H:..., format=yuv420p` — tức là
  ảnh **giữ nguyên tỷ lệ gốc**, không bị crop méo, phần thừa được lấp đầy bằng đệm đen.
- Video dừng theo audio nhờ `-shortest`.
- Output encode lại bằng `libx264` (CRF + preset) và `aac` để tương thích tối đa với
  trình phát / mạng xã hội.
- `+faststart` cho phép preview web ngay khi đang upload.
- Trên Windows UI chạy trong cùng process với Spring Boot để controller có thể gọi
  `FfmpegService` trực tiếp qua Spring DI (không cần HTTP loopback).

---

## 8. Troubleshooting

| Lỗi | Cách xử lý |
|-----|-----------|
| `Cannot run program "ffmpeg"` | Thêm `ffmpeg` vào `PATH` hoặc set `FFMPEG_PATH` |
| Output không play được | Đảm bảo ext là `.mp4`; tránh tên output trùng file đang mở |
| Ảnh bị crop/méo | Không nên — filter `force_original_aspect_ratio=decrease` giữ tỷ lệ |
| Log quá dài | Thay `aspect` sang portrait để phù hợp ảnh dọc |
| App không tắt hẳn sau khi đóng cửa sổ | Đã xử lý trong `Application.java` (gọi `springContext.close()`) |
