# Đặc tả Kỹ thuật Hệ thống AceTrace (Shared Specification v1.0)

Đặc tả này là tiêu chuẩn kỹ thuật bất biến dùng chung giữa hai nền tảng:
- **Android**: Kotlin, Jetpack Compose, Media3
- **iOS**: Swift, SwiftUI, AVFoundation

---

## 1. Hệ tọa độ chuẩn (Normalized Coordinate Space)

1. Tọa độ của tất cả các điểm (`keypoint`, `handleIn`, `handleOut`, `sample`) đều được chuẩn hóa trong khoảng $[0.0, 1.0]$:
   - Gốc tọa độ $(0.0, 0.0)$ ở **góc trên bên trái** (Top-Left) của khung hình hiển thị.
   - Góc dưới bên phải (Bottom-Right) là $(1.0, 1.0)$.
2. Không gian tọa độ được định nghĩa **sau khi đã áp dụng góc quay của video (`rotationDegrees`)**. Tức là: Người dùng nhìn thấy hình ảnh theo chiều nào trên màn hình thì tọa độ lưu đúng theo chiều đó.
3. Chuyển đổi sang pixel thực tế khi vẽ (Canvas Pixel Space):
   $$X_{px} = X_{norm} \times Width_{canvas}$$
   $$Y_{px} = Y_{norm} \times Height_{canvas}$$

---

## 2. Thuật toán Đường cong (Curve Algorithms)

### 2.1. Cubic Bezier với Tiếp tuyến Điều khiển (Handles)
Đối với đoạn cong giữa điểm $P_0$ (keypoint trước) và $P_3$ (keypoint sau):
- $P_1 = P_0.handleOut$
- $P_2 = P_3.handleIn$
- Tham số $u \in [0.0, 1.0]$:
  $$B(u) = (1-u)^3 P_0 + 3(1-u)^2 u P_1 + 3(1-u) u^2 P_2 + u^3 P_3$$

#### Quy tắc khởi tạo Handle mặc định cho cú đánh 3 điểm (Start → Apex → Landing):
Khi người dùng mới chấm 3 điểm mà chưa kéo handle tùy chỉnh:
1. **Tại Start ($P_{start}$)**:
   - HandleOut hướng về phía Apex với độ dài bằng $1/3$ khoảng cách vector giữa Start và Apex:
     $$P_{start}.handleOut = P_{start} + \frac{1}{3}(P_{apex} - P_{start})$$
2. **Tại Apex ($P_{apex}$)**:
   - Tiếp tuyến tại đỉnh nằm ngang ($\Delta y = 0$):
     $$P_{apex}.handleIn = (P_{apex}.x - \frac{1}{3}(P_{apex}.x - P_{start}.x), \; P_{apex}.y)$$
     $$P_{apex}.handleOut = (P_{apex}.x + \frac{1}{3}(P_{landing}.x - P_{apex}.x), \; P_{apex}.y)$$
3. **Tại Landing ($P_{landing}$)**:
   - HandleIn hướng về phía Apex:
     $$P_{landing}.handleIn = P_{landing} - \frac{1}{3}(P_{landing} - P_{apex})$$

### 2.2. Centripetal Catmull-Rom Spline ($\alpha = 0.5$)
Dùng cho chế độ nhiều điểm (Free curve / Disc golf curve).
Với chuỗi điểm $P_0, P_1, P_2, P_3$:
- Khoảng cách thời gian knot:
  $$t_0 = 0$$
  $$t_{i+1} = t_i + ||P_{i+1} - P_i||^{0.5}$$
- Nội suy vị trí giữa $P_1$ và $P_2$ với $t \in [t_1, t_2]$:
  $$A_1 = \frac{t_1 - t}{t_1 - t_0} P_0 + \frac{t - t_0}{t_1 - t_0} P_1$$
  $$A_2 = \frac{t_2 - t}{t_2 - t_1} P_1 + \frac{t - t_1}{t_2 - t_1} P_2$$
  $$A_3 = \frac{t_3 - t}{t_3 - t_2} P_2 + \frac{t - t_2}{t_3 - t_2} P_3$$
  $$B_1 = \frac{t_2 - t}{t_2 - t_0} A_1 + \frac{t - t_0}{t_2 - t_0} A_2$$
  $$B_2 = \frac{t_3 - t}{t_3 - t_1} A_2 + \frac{t - t_1}{t_3 - t_1} A_3$$
  $$C(t) = \frac{t_2 - t}{t_2 - t_1} B_1 + \frac{t - t_1}{t_2 - t_1} B_2$$

---

## 3. Thuật toán Ánh xạ Thời gian sang Vị trí Đường bay (Time Mapping)

Cho frame video hiện tại $f$:
- **Trường hợp 1: $f < f_{start}$**
  - Quỹ đạo chưa xuất hiện (không vẽ).
- **Trường hợp 2: $f > f_{landing}$**
  - Đã chạm đất: Quỹ đạo hiển thị đầy đủ (Full Trail) hoặc giữ nguyên trạng thái kết thúc.
- **Trường hợp 3: $f_{start} \le f \le f_{apex}$ (Giai đoạn bay lên - Bay chậm dần do trọng lực)**
  - Tỉ lệ thời gian: $s = \frac{f - f_{start}}{f_{apex} - f_{start}} \in [0, 1]$
  - Hàm Easing đoạn lên: $s_{eased} = 1 - (1 - s)^2$ (Quadratic Ease-Out)
  - Vị trí trên cung toàn thể (đoạn 1 chiếm nửa đầu cung):
    $$u = 0.5 \times s_{eased}$$
- **Trường hợp 4: $f_{apex} < f \le f_{landing}$ (Giai đoạn rơi xuống - Tăng tốc do trọng lực)**
  - Tỉ lệ thời gian: $s = \frac{f - f_{apex}}{f_{landing} - f_{apex}} \in [0, 1]$
  - Hàm Easing đoạn xuống: $s_{eased} = s^2$ (Quadratic Ease-In)
  - Vị trí trên cung toàn thể (đoạn 2 chiếm nửa sau cung):
    $$u = 0.5 + 0.5 \times s_{eased}$$

---

## 4. Hiển thị Nhãn Khoảng cách (Distance HUD)

1. Khi người dùng bật `distance.visible = true` và nhập `distance.value = D`:
   $$D(f) = D \times \left(1 - (1 - progress(f))^2\right)$$
   Trong đó $progress(f) = u(f)$ là tiến trình bay tại frame $f$.
2. Nhãn hiển thị được định vị tại đầu mũi vệt sáng ($Point(u(f))$) với offset chuẩn:
   - Offset $\Delta y = -24\text{ dp}$ (phía trên đầu bóng để không che bóng).
   - Text format: `"{round(D(f))} {unit}"` (ví dụ: `185 yd`, `320 ft`).

---

## 5. Quy chuẩn Kỹ xảo Đồ họa (Renderer VFX Spec)

### 5.1. 3-Layer Glow (Vầng sáng hào quang)
Được cấu thành từ 3 lớp vẽ đè lên nhau từ ngoài vào trong:
1. **Lớp Outer Glow (Hào quang ngoài cùng)**:
   - Độ dày: $LineWidth \times 3.5$
   - Độ mờ Gaussian Blur: $Radius = LineWidth \times 2.0$
   - Alpha: $0.20 \times Glow$
2. **Lớp Inner Glow (Quầng sáng giữa)**:
   - Độ dày: $LineWidth \times 1.8$
   - Độ mờ Gaussian Blur: $Radius = LineWidth \times 0.8$
   - Alpha: $0.45 \times Glow$
3. **Lớp Core Line (Lõi sáng sắc nét)**:
   - Độ dày: $LineWidth$
   - Độ mờ: Không blur (rõ nét sắc nét)
   - Alpha: $1.0$ (Lõi chuyển gradient mượt từ đầu tới cuối).

### 5.2. Các chế độ vệt (Trail Modes)
- **Full**: Vẽ từ $u = 0.0$ đến $u = 1.0$ ngay khi bóng bắt đầu bay.
- **Tracer**: Vẽ từ $u = 0.0$ đến $u = u(f)$ hiện tại.
- **Comet**: Chỉ vẽ đoạn đuôi từ $u_{tail} = \max(0.0, u(f) - L)$ đến $u(f)$, với $L = 0.25$ (chiều dài đuôi). Alpha giảm dần về 0 tại $u_{tail}$.

---

## 6. Tiêu chí Kiểm định Golden Test Vectors
Mọi triển khai toán học trên Kotlin và Swift phải chạy unit test đối chiếu với `aceTrace_spec/golden/` với sai số tối đa:
$$\epsilon \le 1.0 \times 10^{-4}$$
