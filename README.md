# Eye Distance Guard 📱👁️

App Android kiểm tra khoảng cách giữa mắt và điện thoại để giảm nguy cơ cận thị.

## Cách hoạt động
- Dùng camera trước + ML Kit Face Detection để phát hiện khuôn mặt.
- Ước lượng khoảng cách mắt–màn hình dựa trên kích thước khung khuôn mặt trên ảnh
  (giả định mặt người rộng trung bình ~14,5 cm, góc nhìn ngang camera ~60°).
- Màn hình hiển thị khoảng cách theo thời gian thực:
  - 🟢 Xanh: an toàn (≥ ngưỡng + 10 cm)
  - 🟡 Vàng: sắp quá gần
  - 🔴 Đỏ: quá gần — rung + tiếng bíp cảnh báo khi duy trì quá gần trên 2 giây
- Thanh trượt đặt ngưỡng an toàn (20–60 cm, mặc định 30 cm theo khuyến nghị).

## Build APK
Vào tab **Actions** của repo, chạy workflow **Build APK**, sau đó tải artifact
`EyeDistanceGuard-debug-apk` về và cài (bật "Cài đặt từ nguồn không xác định").

Lưu ý: đây là APK debug. Muốn APK release đã ký, cần tạo keystore và cấu hình signing.

## Giới hạn
- Khoảng cách là ước lượng (± vài cm), phục vụ cảnh báo thói quen sử dụng,
  không phải thiết bị đo y tế.
- Kết quả phụ thuộc FOV camera thực tế của từng máy.
