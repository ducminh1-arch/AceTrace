# AceTrace - Shot Tracker (Native Android & iOS)

Ứng dụng vẽ và theo dõi đường bay thể thao (Golf, Disc Golf, Baseball, Tennis, Bóng đá) chuyên nghiệp trên nền tảng **Android (Kotlin)** và **iOS (Swift)**.

---

## Cấu trúc Repository

```
AceTrace/
├── aceTrace_spec/               # Đặc tả kỹ thuật & Golden test vectors dùng chung
│   ├── schema.json              # Project JSON Schema v1
│   ├── SPECIFICATION.md         # Chuẩn toán học (Bezier, Catmull-Rom, Time mapping, VFX)
│   ├── generate_golden_vectors.py
│   └── golden/                  # Golden test vectors JSON cho Unit Tests
├── aceTrace_android/            # Ứng dụng Android Native (Kotlin + Jetpack Compose + Media3)
│   ├── app/
│   │   ├── src/main/java/com/acetrace/app/
│   │   │   ├── core/model/      # Project, Trajectory, Keypoint, Style (@Serializable)
│   │   │   ├── core/curve/      # BezierPath, CatmullRomPath, TimeMapping
│   │   │   ├── core/render/     # OverlayRenderer (hàm render dùng chung preview & export)
│   │   │   ├── core/video/      # FrameIndex, PlayerController, VideoExporter
│   │   │   └── ui/              # Compose screens (Import, Editor, StylePanel, Export)
│   │   └── src/test/java/       # Golden Vector Tests
├── aceTrace_ios/                # Ứng dụng iOS Native (Swift + SwiftUI + AVFoundation)
│   ├── AceTrace/
│   │   ├── Core/Model/          # Project, Trajectory, Keypoint, Style (Codable)
│   │   ├── Core/Curve/          # BezierPath, CatmullRomPath, TimeMapping
│   │   ├── Core/Render/         # OverlayRenderer (hàm render chung)
│   │   ├── Core/Video/          # FrameIndex, PlayerController, VideoExporter
│   │   └── Features/            # SwiftUI screens
│   └── AceTraceTests/           # Golden Vector Tests
└── README.md
```

---

## Nguyên tắc Phát triển Bất biến
1. **Hệ tọa độ**: Chuẩn hóa $[0.0, 1.0]$ theo góc nhìn người dùng (đã qua `rotationDegrees`).
2. **OverlayRenderer**: Hàm thuần túy `render(frameIndex, project) -> overlay` là nguồn duy nhất cho cả Preview thời gian thực và Video Export.
3. **Golden Vectors**: Mọi thuật toán đường cong và ánh xạ thời gian phải vượt qua bộ test tại `aceTrace_spec/golden/` với sai số $\le 10^{-4}$.
