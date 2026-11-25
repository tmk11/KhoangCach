# Eye Distance Guard 📱👁️

Ứng dụng Android dùng camera trước để ước lượng khoảng cách từ mặt đến điện thoại và nhắc khi bạn giữ máy quá gần. Hình ảnh được phân tích bằng ML Kit ngay trên thiết bị.

## Cách dùng

1. Mở ứng dụng và cấp quyền camera. Nếu đã từ chối, dùng nút cấp quyền hoặc mở cài đặt ngay trên màn hình.
2. Nhấn **Hiệu chuẩn ở 40 cm**. Dùng thước đo từ mắt đến màn hình, nhìn thẳng và giữ yên một khuôn mặt trong khung hình rồi nhấn **Lưu**. Ứng dụng ghi nhớ kết quả. Hãy hiệu chuẩn lại khi đổi người sử dụng.
3. Chọn ngưỡng từ **20 đến 60 cm**. Mặc định là **30 cm**. Màu vàng xuất hiện khi khoảng cách từ ngưỡng đến dưới ngưỡng + 10 cm. Khi ở dưới ngưỡng liên tục 2 giây, ứng dụng phát tiếng bíp và rung. Khoảng thời gian này được tính lại khi mất khuôn mặt hoặc khi trở về ngưỡng.
4. Để tiếp tục đo khi mở ứng dụng khác, nhấn **Theo dõi khi dùng ứng dụng khác** trước khi rời màn hình. Trên Android 13 trở lên, cho phép thông báo để có nút dừng trên thanh thông báo. Có thể dừng chế độ nền ngay trong ứng dụng. Khi chưa bật chế độ này, rời ứng dụng sẽ giải phóng camera.

## Giới hạn

Trước khi hiệu chuẩn, ứng dụng ước tính theo chiều rộng mặt 14,5 cm và góc nhìn 60°. Kết quả phụ thuộc vào người dùng, tư thế và camera. Khi có nhiều khuôn mặt, ứng dụng đo khuôn mặt có khung rộng nhất. Ứng dụng khác dùng camera có thể làm gián đoạn việc đo. Chưa có kết quả kiểm định sai số trên các mẫu máy. Số đo chỉ dùng để nhắc thói quen.

## Build APK

Workflow **Build APK** chạy unit test và tạo artifact **EyeDistanceGuard-debug-apk**. Để build cục bộ, dùng JDK 17, Gradle 8.7 và Android SDK 34, rồi chạy `gradle testDebugUnitTest assembleDebug`. APK nằm trong `app/build/outputs/apk/debug/`. Đây là bản debug.
