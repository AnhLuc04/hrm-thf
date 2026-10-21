# Chứng chỉ HTTPS cho SunClub (không mở cổng 80)

Giữ nguyên link `https://thfgroup.ddns.net:8081` và cổng 8081. Tự tạo chứng chỉ gốc **SunClub CA**,
dùng nó ký chứng chỉ cho máy chủ, rồi cài SunClub CA lên từng điện thoại / máy tính **một lần**.
Thiết bị đã cài thì không còn báo "Không an toàn", cài được app, nhận được thông báo.

## A. Trên máy chủ (làm 1 lần)

1. Chép thư mục `chung-chi` ra máy chủ, chạy **`tao-chung-chi.bat`**
   (sửa `PASS` = mật khẩu keystore đang dùng, `CAPASS` = mật khẩu riêng cho chứng chỉ gốc).
2. Kết quả:
   - `keystore.p12` → chép đè vào `src/main/resources/`
   - `sunclub-ca.cer` → chép vào `src/main/resources/pwa/`
   - `ca.p12` → **cất kín**, giữ lại để lần sau gia hạn (chứng chỉ máy chủ hạn 825 ngày; chạy lại .bat là xong, điện thoại không phải cài lại).
3. `application.properties`: thêm dòng `server.ssl.key-alias=sunclub` (mật khẩu keystore giữ như `PASS`).
4. Build lại, khởi động lại phần mềm.

## B. iPhone / iPad (mỗi máy 1 lần)

1. Mở **Safari** → `https://thfgroup.ddns.net:8081/sunclub.mobileconfig` (iPhone dùng link này, không dùng .cer)
   (nếu báo cảnh báo → Hiện chi tiết → truy cập trang web này).
2. Báo "Trang web đang cố tải xuống hồ sơ cấu hình" → **Cho phép** → Đóng.
3. **Cài đặt** → dòng **Đã tải về hồ sơ** (ngay dưới tên) → **Cài đặt** → nhập mật mã → **Cài đặt** → Xong.
4. **Cài đặt → Cài đặt chung → Giới thiệu → Cài đặt tin cậy chứng nhận** → bật **SunClub CA** → Tiếp tục.
5. **Cài đặt → Safari → Xoá lịch sử và dữ liệu trang web**, xoá biểu tượng SunClub cũ, mở lại link → thêm vào MH chính.

## C. Android

1. Chrome → `https://thfgroup.ddns.net:8081/sunclub-ca.cer` → tải file về.
2. **Cài đặt → Bảo mật → (Mã hoá & thông tin xác thực / Cài đặt bảo mật khác) → Cài đặt chứng chỉ → Chứng chỉ CA** → Vẫn cài đặt → chọn `sunclub-ca.cer`.
3. Mở lại link trong Chrome → ⋮ → Cài đặt ứng dụng.

## D. Máy tính Windows

Tải `sunclub-ca.cer` → bấm đúp → **Cài đặt chứng chỉ** → **Local Machine** → *Đặt tất cả chứng chỉ vào kho sau* → **Trusted Root Certification Authorities** → Xong. Mở lại Chrome / Edge.

> Chỉ cài chứng chỉ do HCNS / IT công ty phát. Không chia sẻ `ca.p12` cho ai.
