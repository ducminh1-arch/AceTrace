# Tài liệu Kỹ thuật Thuật toán (Algorithm Specifications)

Tài liệu này đặc tả chi tiết các thuật toán toán học và hình học được sử dụng trong hệ thống **AceTrace Shot Tracker** (Web Simulator, Android Kotlin, iOS Swift).

---

## 1. Hệ tọa độ Video Content Rect (Normalized Coordinate Space)

Để đảm bảo các điểm neo (`keypoint`), tiếp tuyến (`handleIn`, `handleOut`) và vệt vẽ luôn bám đúng vào hình ảnh video thực tế (kể cả khi video có viền đen Letterbox/Pillarbox):

### 1.1. Xác định Video Content Rect
Cho kích thước khung chứa hiển thị (Container/Viewport) là $W_{container}, H_{container}$ và kích thước video gốc (đã qua góc xoay `rotationDegrees`) là $W_{video}, H_{video}$:

$$\text{Aspect}_{video} = \frac{W_{video}}{H_{video}}, \quad \text{Aspect}_{container} = \frac{W_{container}}{H_{container}}$$

- **Trường hợp 1**: $\text{Aspect}_{container} > \text{Aspect}_{video}$ (Khung rộng hơn video $\implies$ Viền đen hai bên trái/phải - Pillarbox):
  $$H_{rect} = H_{container}$$
  $$W_{rect} = H_{container} \times \text{Aspect}_{video}$$
  $$X_{rect} = \frac{W_{container} - W_{rect}}{2}, \quad Y_{rect} = 0$$

- **Trường hợp 2**: $\text{Aspect}_{container} \le \text{Aspect}_{video}$ (Khung hẹp hơn video $\implies$ Viền đen trên/dưới - Letterbox):
  $$W_{rect} = W_{container}$$
  $$H_{rect} = \frac{W_{container}}{\text{Aspect}_{video}}$$
  $$X_{rect} = 0, \quad Y_{rect} = \frac{H_{container} - H_{rect}}{2}$$

### 1.2. Công thức chuyển đổi tọa độ
Mọi điểm $P(x, y)$ được lưu dưới dạng chuẩn hóa $[0.0, 1.0]$ theo `videoContentRect`:
- **Từ tọa độ chuẩn hóa sang tọa độ màn hình (Vẽ/Render)**:
  $$X_{screen} = X_{rect} + x_{norm} \times W_{rect}$$
  $$Y_{screen} = Y_{rect} + y_{norm} \times H_{rect}$$

- **Từ tọa độ click/chạm sang tọa độ chuẩn hóa (Tương tác)**:
  $$x_{norm} = \frac{X_{client} - X_{rect}}{W_{rect}}$$
  $$y_{norm} = \frac{Y_{client} - Y_{rect}}{H_{rect}}$$

- **Ràng buộc kẹp (Clamping)**:
  Nếu click ngoài `videoContentRect` ($x_{norm} < 0$, $x_{norm} > 1$, $y_{norm} < 0$, hoặc $y_{norm} > 1$), thao tác click bị loại bỏ để không đặt điểm vào vùng viền đen. Khi kéo thả (drag), tọa độ được kẹp chặt trong đoạn $[0.0, 1.0]$:
  $$x_{clamped} = \min(\max(x_{norm}, 0.0), 1.0)$$
  $$y_{clamped} = \min(\max(y_{norm}, 0.0), 1.0)$$

---

## 2. Thuật toán Đường cong (Trajectory Curve Interpolation)

### 2.1. Đa đoạn Cubic Bezier có Handle điều khiển
Đường bay 3 điểm chính gồm 2 đoạn cong nối tiếp nhau:
- **Đoạn 1 ($u \in [0.0, 0.5]$)**: Từ $P_{start}$ đến $P_{apex}$ với 2 điểm điều khiển $P_{1} = P_{start}.handleOut$ và $P_{2} = P_{apex}.handleIn$.
- **Đoạn 2 ($u \in [0.5, 1.0]$)**: Từ $P_{apex}$ đến $P_{landing}$ với 2 điểm điều khiển $P_{1} = P_{apex}.handleOut$ và $P_{2} = P_{landing}.handleIn$.

Phương trình Cubic Bezier cho tham số cục bộ $t \in [0, 1]$:
$$B(t) = (1-t)^3 P_0 + 3(1-t)^2 t P_1 + 3(1-t) t^2 P_2 + t^3 P_3$$

#### Quy tắc Handle mặc định (Default Handles):
- **Tại Start**: $P_{start}.handleOut = P_{start} + \frac{1}{3}(P_{apex} - P_{start})$
- **Tại Apex**: Tiếp tuyến nằm ngang ($\Delta y = 0$):
  $$P_{apex}.handleIn = \left(P_{apex}.x - \frac{P_{apex}.x - P_{start}.x}{3}, \; P_{apex}.y\right)$$
  $$P_{apex}.handleOut = \left(P_{apex}.x + \frac{P_{landing}.x - P_{apex}.x}{3}, \; P_{apex}.y\right)$$
- **Tại Landing**: $P_{landing}.handleIn = P_{landing} - \frac{1}{3}(P_{landing} - P_{apex})$

Tất cả tọa độ của `handleIn` và `handleOut` đều chuẩn hóa $0..1$ theo `videoContentRect`.

### 2.2. Centripetal Catmull-Rom Spline ($\alpha = 0.5$)
Dành cho đường bay tự do nhiều điểm (Free curve / Disc golf).
Với 4 điểm điều khiển $P_0, P_1, P_2, P_3$:
$$t_0 = 0$$
$$t_{i+1} = t_i + ||P_{i+1} - P_i||^{0.5}$$
Tham số $t \in [t_1, t_2]$:
$$A_1 = \frac{t_1 - t}{t_1 - t_0} P_0 + \frac{t - t_0}{t_1 - t_0} P_1, \quad A_2 = \frac{t_2 - t}{t_2 - t_1} P_1 + \frac{t - t_1}{t_2 - t_1} P_2, \quad A_3 = \frac{t_3 - t}{t_3 - t_2} P_2 + \frac{t - t_2}{t_3 - t_2} P_3$$
$$B_1 = \frac{t_2 - t}{t_2 - t_0} A_1 + \frac{t - t_0}{t_2 - t_0} A_2, \quad B_2 = \frac{t_3 - t}{t_3 - t_1} A_2 + \frac{t - t_1}{t_3 - t_1} A_3$$
$$C(t) = \frac{t_2 - t}{t_2 - t_1} B_1 + \frac{t - t_1}{t_2 - t_1} B_2$$

---

## 3. Thuật toán Ánh xạ Thời gian & Gia tốc Trọng lực (Time Mapping)

Vận tốc của bóng không đều theo thời gian thực tế:
- Khi bay lên đỉnh ($Start \to Apex$): bóng giảm tốc do lực cản không khí và trọng lực $\implies$ **Ease-Out**.
- Khi rơi từ đỉnh xuống ($Apex \to Landing$): bóng tăng tốc do trọng trường $\implies$ **Ease-In**.

Cho khung hình hiện tại $f$, khung hình xuất phát $f_{start}$, khung hình đạt đỉnh $f_{apex}$, và khung hình chạm đất $f_{landing}$:

1. **Trước cú đánh ($f < f_{start}$)**:
   - $u = 0.0$
   - $\text{progress} = 0.0$
   - Trạng thái: `before_start` (Chưa vẽ vệt sáng).

2. **Giai đoạn bay lên ($f_{start} \le f \le f_{apex}$)**:
   - Tỉ lệ khung hình tuyến tính:
     $$s = \frac{f - f_{start}}{f_{apex} - f_{start}} \in [0.0, 1.0]$$
   - Hàm Easing đoạn lên (Quadratic Ease-Out):
     $$s_{eased} = 1 - (1 - s)^2$$
   - Tham số toàn cục trên đường cong:
     $$u = 0.5 \times s_{eased} \in [0.0, 0.5]$$
   - $\text{progress} = u$
   - Trạng thái: `ascending`.

3. **Giai đoạn rơi xuống ($f_{apex} < f \le f_{landing}$)**:
   - Tỉ lệ khung hình tuyến tính:
     $$s = \frac{f - f_{apex}}{f_{landing} - f_{apex}} \in [0.0, 1.0]$$
   - Hàm Easing đoạn xuống (Quadratic Ease-In):
     $$s_{eased} = s^2$$
   - Tham số toàn cục trên đường cong:
     $$u = 0.5 + 0.5 \times s_{eased} \in [0.5, 1.0]$$
   - $\text{progress} = u$
   - Trạng thái: `descending`.

4. **Sau khi chạm đất ($f > f_{landing}$)**:
   - $u = 1.0$
   - $\text{progress} = 1.0$
   - Trạng thái: `landed` (Giữ nguyên toàn bộ đường hoặc fade).

---

## 4. Công thức Tính Khoảng cách Nhảy số (Distance HUD)

Khoảng cách hiển thị tại khung hình $f$ phụ thuộc vào tổng khoảng cách $D_{total}$ và tiến trình bay $p = \text{progress}(f)$:
$$D_{shown}(f) = \text{round}\left(D_{total} \times \left(1 - (1 - p)^2\right)\right)$$

### Chống chồng lấn nhãn (Collision Avoidance):
- Nhãn hiển thị được neo tại điểm đầu vệt sáng $P_{head} = \text{point}(u(f))$.
- Vị trí mặc định: $\Delta y = -28\text{ px}$ (phía trên đầu vệt).
- Nếu đầu vệt ở gần đỉnh Apex, Start hoặc Landing (khoảng cách Euclid $< 35\text{ px}$), hoặc chạm mép trên của `videoContentRect`, nhãn sẽ tự động dịch chuyển xuống phía đối diện:
  $$\Delta y = +28\text{ px}$$
- Đồng thời nhãn luôn được kẹp trong ranh giới $X_{rect} \le X_{badge} \le X_{rect} + W_{rect}$.

---

## 5. Cấu trúc Golden Test Vectors

Bộ test đối chiếu định dạng JSON gồm:
```json
{
  "input": { /* Toàn bộ Project JSON chuẩn schemaVersion 1 */ },
  "samples": [
    {
      "frameIndex": 0,
      "u": 0.0,
      "x": 0.42,
      "y": 0.78,
      "distanceShown": 0
    }
  ]
}
```
Các mẫu kiểm tra được lấy tại:
- Mỗi chu kỳ 10 frame: $0, 10, 20, 30, \dots$
- Đúng tại 3 frame sự kiện then chốt: $f_{start}$, $f_{apex}$, $f_{landing}$.
