# HRM nhiều đơn vị — dự án Gradle đầy đủ

Mã nguồn gốc của bạn (`src.rar`) + toàn bộ phần đã làm: 4 đơn vị, thẻ lương theo đơn vị, 2 phiếu dự kiến theo ngày chi lương,
bảng tin có ảnh/file, hạn xác nhận công 7 ngày, lịch làm việc HCNS, và **tách quyền quản trị hệ thống / HCNS đơn vị**.
Logic cũ giữ nguyên (máy chấm công cũ, định tuyến 99999/88888/77777 cho đơn vị gốc, cách tính công, tính lương).

## 1. Chạy

Cần JDK 17+ và MySQL. Lần đầu Gradle tự tải thư viện (cần Internet).

```bash
# Windows
gradlew.bat bootRun
# Linux / macOS
./gradlew bootRun

# đóng gói 1 file chạy
gradlew.bat bootJar        # -> build/libs/hrm-2.0.0.jar
java -jar build/libs/hrm-2.0.0.jar
```

Mở bằng IntelliJ: File → Open → chọn thư mục này (nhận `build.gradle`). Bật *Annotation processing* cho Lombok.

### Trước khi chạy: điền lại mật khẩu

Bản gửi đi **đã xoá mật khẩu thật**. Trong `src/main/resources/application.properties` điền lại 3 chỗ
(hoặc đặt biến môi trường `DB_PASSWORD`, `DEVICE_PASSWORD`, `SSL_PASSWORD`):

- `spring.datasource.password`
- `device.api.password`
- `server.ssl.key-store-password`

và **chép `keystore.p12` cũ** vào `src/main/resources/` (bản này không kèm file khoá HTTPS).
Không có keystore thì tạm đặt `server.ssl.enabled=false`.

Không cần chạy SQL: `ddl-auto=update` tự thêm bảng/cột mới. Lần chạy đầu tự tạo đơn vị gốc TH và gán mọi nhân viên cũ vào đó.

## 2. Ai làm gì

| Tài khoản | Làm được |
|---|---|
| **Quản trị hệ thống** (`admin`, hoặc role `SUPER_ADMIN`) | Chỉ 3 việc: **Đơn vị & máy chấm công** (`/units`: thêm đơn vị, nhập API Hikvision, kiểm tra máy, kéo nhân viên, kéo chấm công), **Tài khoản & chia đơn vị** (`/system/accounts`: tạo tài khoản admin/user, đổi loại, chuyển người sang đơn vị khác, đặt lại mật khẩu), đăng **Bảng tin** toàn hệ thống. Mở trang khác sẽ bị đưa về trang Tài khoản. |
| **HCNS đơn vị** (phòng HCNS / Hành chính / Nhân sự) hoặc **Quản trị đơn vị** (role `ADMIN`) | **Chức vụ & phòng ban** (`/hr/roles`): chọn Giám đốc, GĐ dịch vụ, Kế toán trưởng; chia phòng ban, Trưởng phòng / Nhân viên. **Lịch làm việc & giờ chấm** (`/attendance/schedule`), **Hạn xác nhận công** (`/attendance/deadline`), Ngày nghỉ lễ, Nhân viên, Bảng tin đơn vị. Chỉ thấy người đơn vị mình. |
| Giám đốc / trưởng phòng | Duyệt đơn như cũ, trong đơn vị mình |
| Kế toán | Lương, nhập bảng lương, phiếu lương — đơn vị mình |
| Nhân viên | Như cũ + bảng tin + 2 phiếu dự kiến |

Đổi Giám đốc / trưởng phòng / phòng ban: đơn xác nhận công **đang chờ** tự chuyển sang người duyệt mới.
Muốn admin vào được mọi trang như bản trước: `hrm.system-admin.restricted=false`.

### Thêm 1 đơn vị mới (VD Ford Sầm Sơn)
1. `admin` → Đơn vị & máy chấm công → **Thêm đơn vị**: mã `SS`, tên, thẻ lương (tên công ty, người lập, nơi ký, ngày chi lương), API máy (địa chỉ, tài khoản, mật khẩu, kéo từ ngày) → Lưu.
2. Trên thẻ đơn vị bấm **Kiểm tra máy → Kéo nhân viên → Kéo chấm công** (mã NV = `SS` + mã máy).
3. **Tài khoản & chia đơn vị** → Thêm tài khoản cho HCNS của SS (loại *Quản trị đơn vị* hoặc *Người dùng* thuộc phòng HCNS).
4. HCNS SS đăng nhập → **Chức vụ & phòng ban**: chọn Giám đốc, chia phòng, trưởng phòng → **Lịch làm việc & giờ chấm**: ngày làm trong tuần, giờ muộn/về sớm, hạn xác nhận.

## 3. Cấu trúc thêm so với bản gốc

```
build.gradle, settings.gradle, gradlew, gradlew.bat, gradle/wrapper/
src/main/java/com/example/hrm/
  config/  SystemAdminGuard (mới), UnitBootstrap, UploadLimitAdvice
  controller/ SystemAccountController (mới), UnitRoleController (mới), CompanyUnitController,
              WorkScheduleController, ConfirmDeadlineController, NewsController
  entity/  CompanyUnit, Announcement, AnnouncementFile, ConfirmExtension
  service/ UnitScope, ConfirmDeadlineService, NewsFileStorage, AttendanceService (approverFor, rerouteOpenConfirmations)...
src/main/resources/templates/
  system-accounts.html, hr-roles.html (mới), units/*, news.html, attendance-schedule.html,
  attendance-deadline.html, fragments/the-luong.html, fragments/shell.html ...
```

Cổng điện thoại `/m` và gói PWA gửi trước đây là gói riêng, chưa ghép vào bản này.
Nhớ sao lưu thư mục `data/uploads` (ảnh/file bảng tin) cùng database.
