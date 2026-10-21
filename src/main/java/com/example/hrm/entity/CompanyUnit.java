package com.example.hrm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * ĐƠN VỊ (công ty con). Mỗi đơn vị có:
 *   - nhân viên riêng (users.unit_id), không ai thuộc 2 đơn vị
 *   - giờ làm riêng (mốc đi muộn, mốc tính vi phạm, mốc chia ca, mốc về sớm)
 *   - người duyệt riêng: Giám đốc (thay mã cố định 99999), Giám đốc dịch vụ (88888), Kế toán trưởng (77777)
 *   - máy chấm công Hikvision riêng
 *
 * "Đơn vị gốc" (legacy = true, chỉ 1): công ty đang dùng hệ thống từ trước. Nhân viên cũ giữ nguyên mã,
 * máy cũ đọc mã như cũ. Đơn vị thêm sau: mã nhân viên = mã đơn vị + mã trên máy (VD SG + 41 = SG41).
 */
@Entity
@Table(name = "company_unit", uniqueConstraints = @UniqueConstraint(name = "uk_company_unit_code", columnNames = "code"))
public class CompanyUnit {

    public static final LocalTime DEFAULT_LATE_AFTER = LocalTime.of(7, 35);
    public static final LocalTime DEFAULT_VIOLATION_AFTER = LocalTime.of(8, 5);
    public static final LocalTime DEFAULT_NOON = LocalTime.of(12, 0);
    public static final LocalTime DEFAULT_EARLY_BEFORE = LocalTime.of(17, 0);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã ngắn, chữ in hoa + số (VD TH, SG). Là tiền tố mã nhân viên của đơn vị thêm sau. */
    @Column(nullable = false, length = 10)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    /** Đơn vị gốc: nhân viên cũ giữ mã không tiền tố */
    @Column(name = "legacy_unit")
    private Boolean legacy = false;

    private Boolean active = true;

    // ------------------------------------------------------------- giờ làm
    /** Vào sau giờ này = báo "Đi muộn" ở trang xác nhận công (bản cũ: 07:35) */
    @Column(name = "late_after")
    private LocalTime lateAfter;

    /** Vào sau giờ này = tính 1 lần vi phạm trên bảng công tổng (bản cũ: 08:05) */
    @Column(name = "violation_after")
    private LocalTime violationAfter;

    /** Mốc chia ca sáng / chiều (bản cũ: 12:00) */
    @Column(name = "noon_split")
    private LocalTime noonSplit;

    /** Ra trước giờ này = báo "Về sớm" (bản cũ: 17:00) */
    @Column(name = "early_before")
    private LocalTime earlyBefore;

    // ------------------------------------------------------------- người duyệt của đơn vị
    /** Mã NV Giám đốc: duyệt đơn của trưởng phòng Kinh doanh, quản trị, phòng chưa có trưởng (bản cũ: 99999) */
    @Column(name = "director_no", length = 50)
    private String directorNo;

    /** Mã NV Giám đốc dịch vụ: duyệt đơn phòng Dịch vụ (bản cũ: 88888). Trống = Giám đốc duyệt */
    @Column(name = "service_director_no", length = 50)
    private String serviceDirectorNo;

    /** Mã NV Kế toán trưởng: duyệt đơn phòng Kế toán (bản cũ: 77777). Trống = Giám đốc duyệt */
    @Column(name = "chief_accountant_no", length = 50)
    private String chiefAccountantNo;

    // ------------------------------------------------------------- thẻ lương
    /** Tên công ty in trên đầu thẻ lương (VD CÔNG TY CP TM Ô TÔ THANH HÓA). Trống = tên đơn vị */
    @Column(name = "slip_company", length = 200)
    private String slipCompany;

    /** Người lập phiếu (Kế toán) in dưới mục ký tên */
    @Column(name = "slip_preparer", length = 100)
    private String slipPreparer;

    /** Nơi ký: "Thanh Hóa, Ngày...Tháng...Năm". Trống = tên đơn vị */
    @Column(name = "slip_place", length = 100)
    private String slipPlace;

    /** Ngày chi lương THÁNG TRƯỚC hằng tháng (VD 10). Trống = payroll.pay-day */
    @Column(name = "pay_day")
    private Integer payDay;

    // ------------------------------------------------------------- hạn xác nhận công + lịch làm việc
    /** HẠN XÁC NHẬN CÔNG: số ngày được gửi giải trình kể từ ngày lỗi. Trống = 7, 0 = không giới hạn */
    @Column(name = "confirm_days")
    private Integer confirmDays;

    /**
     * NGÀY LÀM TRONG TUẦN (HCNS cấu hình): 7 ký tự Thứ Hai → Chủ nhật
     *   F = làm cả ngày, M = chỉ làm buổi sáng, O = nghỉ
     * VD "FFFFFMO" = T2–T6 cả ngày, T7 làm sáng, CN nghỉ. Trống = "FFFFFFO" (nghỉ Chủ nhật như bản cũ).
     */
    @Column(name = "work_days", length = 7)
    private String workDays;

    /** NGÀY DỪNG XÁC NHẬN CÔNG: công tháng trước còn được GỬI giải trình đến hết ngày này của tháng sau (bản cũ: 1). 0 = sang tháng là khoá */
    @Column(name = "confirm_cutoff_day")
    private Integer confirmCutoffDay;

    /** NGÀY DỪNG DUYỆT / CHỐT BẢNG CÔNG TỔNG: quản lý còn duyệt công tháng trước đến hết ngày này (bản cũ: 1) */
    @Column(name = "report_cutoff_day")
    private Integer reportCutoffDay;

    /** NGÀY CỘNG PHÉP NĂM hằng tháng (1-28, bản cũ: ngày 1) */
    @Column(name = "leave_accrual_day")
    private Integer leaveAccrualDay;

    /** Ai sửa lịch làm việc / giờ chấm công lần cuối (HCNS) */
    @Column(name = "schedule_updated_by", length = 150)
    private String scheduleUpdatedBy;

    @Column(name = "schedule_updated_at")
    private LocalDateTime scheduleUpdatedAt;

    // ------------------------------------------------------------- máy chấm công
    /** VD http://172.16.3.251 */
    @Column(name = "device_url", length = 200)
    private String deviceUrl;

    @Column(name = "device_username", length = 100)
    private String deviceUsername;

    @Column(name = "device_password", length = 200)
    private String devicePassword;

    /** GIỜ TỰ KÉO CÔNG từ máy mỗi ngày, VD "09:00,12:30,20:00". Trống = 09:00 và 20:00 như bản cũ */
    @Column(name = "sync_times", length = 100)
    private String syncTimes;

    /** Kiểu lịch kéo công: TIMES = các giờ cố định trong ngày (syncTimes); INTERVAL = lặp từ syncFrom đến syncTo mỗi syncEvery phút */
    @Column(name = "sync_mode", length = 10)
    private String syncMode;

    @Column(name = "sync_from")
    private LocalTime syncFrom;

    @Column(name = "sync_to")
    private LocalTime syncTo;

    /** Bước lặp (phút) khi kéo theo khung giờ */
    @Column(name = "sync_every")
    private Integer syncEvery;

    /** Ngày trong tuần có kéo công: 7 ký tự T2..CN, 1 = có, 0 = không. Trống = mọi ngày */
    @Column(name = "sync_days", length = 7)
    private String syncDays;

    /** Kéo chấm công từ ngày này (bản cũ cố định 25/08/2026) */
    @Column(name = "sync_from_date")
    private LocalDate syncFromDate;

    // ------------------------------------------------------------- trạng thái đồng bộ
    @Column(name = "last_sync_date")
    private LocalDate lastSyncDate;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "last_status", length = 20)
    private String lastStatus;

    @Column(name = "last_message", length = 1000)
    private String lastMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // ================================================================== tiện ích

    public boolean isLegacyUnit() {
        return Boolean.TRUE.equals(legacy);
    }

    public boolean isEnabled() {
        return !Boolean.FALSE.equals(active);
    }

    public boolean isHasDevice() {
        return deviceUrl != null && !deviceUrl.isBlank();
    }

    public LocalTime lateAfterOrDefault() {
        return lateAfter != null ? lateAfter : DEFAULT_LATE_AFTER;
    }

    public LocalTime violationAfterOrDefault() {
        return violationAfter != null ? violationAfter : DEFAULT_VIOLATION_AFTER;
    }

    public LocalTime noonOrDefault() {
        return noonSplit != null ? noonSplit : DEFAULT_NOON;
    }

    public LocalTime earlyBeforeOrDefault() {
        return earlyBefore != null ? earlyBefore : DEFAULT_EARLY_BEFORE;
    }

    /** Mã nhân viên trong HRM từ mã trên máy chấm công */
    public String employeeNoFor(String deviceCode) {
        String c = deviceCode == null ? "" : deviceCode.trim();
        return isLegacyUnit() ? c : code + c;
    }

    /** Tên công ty trên thẻ lương (đã có giá trị mặc định) */
    public String getSlipCompanyOrDefault() {
        if (slipCompany != null && !slipCompany.isBlank()) {
            return slipCompany.trim();
        }
        return isLegacyUnit() ? "CÔNG TY CP TM Ô TÔ THANH HÓA" : (name == null ? "" : name.toUpperCase());
    }

    public String getSlipPreparerOrDefault() {
        if (slipPreparer != null && !slipPreparer.isBlank()) {
            return slipPreparer.trim();
        }
        return isLegacyUnit() ? "Lê Thị Lan Anh" : "";
    }

    public String getSlipPlaceOrDefault() {
        if (slipPlace != null && !slipPlace.isBlank()) {
            return slipPlace.trim();
        }
        return isLegacyUnit() ? "Thanh Hóa" : (name == null ? "" : name);
    }

    public int payDayOr(int fallback) {
        return payDay != null && payDay >= 1 && payDay <= 28 ? payDay : fallback;
    }

    public static final int DEFAULT_CONFIRM_DAYS = 7;

    public int confirmDaysOrDefault() {
        return confirmDays != null && confirmDays >= 0 ? confirmDays : DEFAULT_CONFIRM_DAYS;
    }

    public static final String DEFAULT_SYNC_TIMES = "09:00,20:00";

    /** Chuẩn hoá "9h, 12:30 ,20:00" -> "09:00,12:30,20:00" (sắp xếp, bỏ trùng); null nếu có giờ sai */
    public static String normalizeSyncTimes(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        for (String part : raw.split("[,;\\s]+")) {
            String t = part.trim().toLowerCase().replace("h", ":").replace(".", ":");
            if (t.isEmpty()) {
                continue;
            }
            if (t.endsWith(":")) {
                t = t + "00";
            }
            if (!t.matches("\\d{1,2}:\\d{2}") && !t.matches("\\d{1,2}")) {
                return null;
            }
            String[] hm = t.split(":");
            int h = Integer.parseInt(hm[0]);
            int m = hm.length > 1 ? Integer.parseInt(hm[1]) : 0;
            if (h > 23 || m > 59) {
                return null;
            }
            out.add(String.format("%02d:%02d", h, m));
        }
        if (out.size() > 12) {
            return null;
        }
        return String.join(",", out);
    }

    /** Danh sách giờ kéo công (luôn có ít nhất 1 giờ) */
    public java.util.List<String> syncTimeList() {
        String n = normalizeSyncTimes(syncTimes);
        return java.util.List.of((n == null || n.isEmpty() ? DEFAULT_SYNC_TIMES : n).split(","));
    }

    public String getSyncTimesLabel() {
        return String.join(", ", syncTimeList());
    }

    public static final String MODE_TIMES = "TIMES";
    public static final String MODE_INTERVAL = "INTERVAL";

    public boolean isIntervalMode() {
        return MODE_INTERVAL.equals(syncMode) && syncFrom != null && syncTo != null && syncEvery != null && syncEvery >= 5;
    }

    public String syncDaysOrDefault() {
        return syncDays != null && syncDays.matches("[01]{7}") && syncDays.contains("1") ? syncDays : "1111111";
    }

    /** Có kéo công vào thứ này không (1 = Thứ Hai ... 7 = Chủ nhật) */
    public boolean syncOnDay(int isoDayOfWeek) {
        return syncDaysOrDefault().charAt(isoDayOfWeek - 1) == '1';
    }

    /** Phút này (giờ VN) có phải lúc kéo công không */
    public boolean isSyncSlot(LocalDateTime t) {
        if (!syncOnDay(t.getDayOfWeek().getValue())) {
            return false;
        }
        LocalTime now = t.toLocalTime().withSecond(0).withNano(0);
        if (isIntervalMode()) {
            if (now.isBefore(syncFrom) || now.isAfter(syncTo)) {
                return false;
            }
            int mins = (now.toSecondOfDay() - syncFrom.withSecond(0).toSecondOfDay()) / 60;
            return mins % syncEvery == 0;
        }
        return syncTimeList().contains(String.format("%02d:%02d", now.getHour(), now.getMinute()));
    }

    /** Lần kéo tiếp theo sau thời điểm t (tối đa xét 8 ngày), null nếu không có */
    public LocalDateTime nextSyncAfter(LocalDateTime t) {
        LocalDateTime x = t.withSecond(0).withNano(0).plusMinutes(1);
        for (int i = 0; i < 8 * 24 * 60; i++, x = x.plusMinutes(1)) {
            if (isSyncSlot(x)) {
                return x;
            }
        }
        return null;
    }

    /** VD "T2–T7 · 09:00, 20:00" hoặc "Mọi ngày · 07:00 → 18:00, mỗi 30 phút" */
    public String getSyncScheduleLabel() {
        String d = syncDaysOrDefault();
        String days;
        if (d.equals("1111111")) {
            days = "Mọi ngày";
        } else {
            String w = d.replace('1', FULL).replace('0', OFF);
            days = ranges(w, FULL);
        }
        if (isIntervalMode()) {
            return days + " · " + syncFrom + " → " + syncTo + ", mỗi " + (syncEvery % 60 == 0 ? syncEvery / 60 + " giờ" : syncEvery + " phút");
        }
        return days + " · " + getSyncTimesLabel();
    }

    public static final int DEFAULT_CUTOFF_DAY = 1;
    public static final int DEFAULT_LEAVE_ACCRUAL_DAY = 1;

    public int confirmCutoffDayOrDefault() {
        return confirmCutoffDay != null && confirmCutoffDay >= 0 && confirmCutoffDay <= 28 ? confirmCutoffDay : DEFAULT_CUTOFF_DAY;
    }

    public int reportCutoffDayOrDefault() {
        return reportCutoffDay != null && reportCutoffDay >= 0 && reportCutoffDay <= 28 ? reportCutoffDay : DEFAULT_CUTOFF_DAY;
    }

    public int leaveAccrualDayOrDefault() {
        return leaveAccrualDay != null && leaveAccrualDay >= 1 && leaveAccrualDay <= 28 ? leaveAccrualDay : DEFAULT_LEAVE_ACCRUAL_DAY;
    }

    /** Đơn vị null (dữ liệu cũ) = mặc định bản cũ */
    public static int confirmCutoffOf(CompanyUnit u) {
        return u == null ? DEFAULT_CUTOFF_DAY : u.confirmCutoffDayOrDefault();
    }

    public static int reportCutoffOf(CompanyUnit u) {
        return u == null ? DEFAULT_CUTOFF_DAY : u.reportCutoffDayOrDefault();
    }

    public static int leaveAccrualDayOf(CompanyUnit u) {
        return u == null ? DEFAULT_LEAVE_ACCRUAL_DAY : u.leaveAccrualDayOrDefault();
    }

    // ------------------------------------------------------------- lịch làm việc trong tuần
    public static final String DEFAULT_WORK_DAYS = "FFFFFFO";
    public static final char FULL = 'F';
    public static final char MORNING = 'M';
    public static final char OFF = 'O';

    public static boolean validWorkDays(String s) {
        return s != null && s.matches("[FMO]{7}") && s.chars().anyMatch(c -> c != OFF);
    }

    public String workDaysOrDefault() {
        return validWorkDays(workDays) ? workDays : DEFAULT_WORK_DAYS;
    }

    /** F / M / O của 1 thứ trong tuần */
    public char dayMode(DayOfWeek d) {
        return workDaysOrDefault().charAt(d.getValue() - 1);
    }

    /** Đơn vị null (dữ liệu cũ) = nghỉ Chủ nhật như bản cũ */
    public static char modeOf(CompanyUnit u, DayOfWeek d) {
        return u == null ? DEFAULT_WORK_DAYS.charAt(d.getValue() - 1) : u.dayMode(d);
    }

    public static boolean isOffDay(CompanyUnit u, DayOfWeek d) {
        return modeOf(u, d) == OFF;
    }

    public static boolean isMorningOnly(CompanyUnit u, DayOfWeek d) {
        return modeOf(u, d) == MORNING;
    }

    /** Số công chuẩn của 1 ngày: cả ngày 1, buổi sáng 0,5, nghỉ 0 */
    public static double dayWeight(CompanyUnit u, DayOfWeek d) {
        char m = modeOf(u, d);
        return m == FULL ? 1.0 : m == MORNING ? 0.5 : 0.0;
    }

    /** VD "T2–T6 cả ngày · T7 làm sáng · CN nghỉ" (ngày liền nhau gộp thành khoảng) */
    public String getWorkDaysLabel() {
        String w = workDaysOrDefault();
        StringBuilder out = new StringBuilder();
        for (char mode : new char[]{FULL, MORNING, OFF}) {
            String days = ranges(w, mode);
            if (!days.isEmpty()) {
                out.append(out.length() > 0 ? " · " : "").append(days)
                        .append(mode == FULL ? " cả ngày" : mode == MORNING ? " làm sáng" : " nghỉ");
            }
        }
        return out.toString();
    }

    private static String ranges(String w, char mode) {
        String[] names = {"T2", "T3", "T4", "T5", "T6", "T7", "CN"};
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < 7) {
            if (w.charAt(i) != mode) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < 7 && w.charAt(j + 1) == mode) {
                j++;
            }
            b.append(b.length() > 0 ? ", " : "").append(names[i]);
            if (j > i) {
                b.append(j == i + 1 ? ", " : "–").append(names[j]);
            }
            i = j + 1;
        }
        return b.toString();
    }

    public String getLabel() {
        return code + " · " + name;
    }

    // ================================================================== getter / setter

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Boolean getLegacy() { return legacy; }
    public void setLegacy(Boolean legacy) { this.legacy = legacy; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public LocalTime getLateAfter() { return lateAfter; }
    public void setLateAfter(LocalTime lateAfter) { this.lateAfter = lateAfter; }
    public LocalTime getViolationAfter() { return violationAfter; }
    public void setViolationAfter(LocalTime violationAfter) { this.violationAfter = violationAfter; }
    public LocalTime getNoonSplit() { return noonSplit; }
    public void setNoonSplit(LocalTime noonSplit) { this.noonSplit = noonSplit; }
    public LocalTime getEarlyBefore() { return earlyBefore; }
    public void setEarlyBefore(LocalTime earlyBefore) { this.earlyBefore = earlyBefore; }
    public String getDirectorNo() { return directorNo; }
    public void setDirectorNo(String directorNo) { this.directorNo = directorNo; }
    public String getServiceDirectorNo() { return serviceDirectorNo; }
    public void setServiceDirectorNo(String serviceDirectorNo) { this.serviceDirectorNo = serviceDirectorNo; }
    public String getChiefAccountantNo() { return chiefAccountantNo; }
    public void setChiefAccountantNo(String chiefAccountantNo) { this.chiefAccountantNo = chiefAccountantNo; }
    public String getSlipCompany() { return slipCompany; }
    public void setSlipCompany(String slipCompany) { this.slipCompany = slipCompany; }
    public String getSlipPreparer() { return slipPreparer; }
    public void setSlipPreparer(String slipPreparer) { this.slipPreparer = slipPreparer; }
    public String getSlipPlace() { return slipPlace; }
    public void setSlipPlace(String slipPlace) { this.slipPlace = slipPlace; }
    public Integer getPayDay() { return payDay; }
    public void setPayDay(Integer payDay) { this.payDay = payDay; }
    public Integer getConfirmDays() { return confirmDays; }
    public void setConfirmDays(Integer confirmDays) { this.confirmDays = confirmDays; }
    public String getSyncMode() { return syncMode; }
    public void setSyncMode(String syncMode) { this.syncMode = syncMode; }
    public LocalTime getSyncFrom() { return syncFrom; }
    public void setSyncFrom(LocalTime syncFrom) { this.syncFrom = syncFrom; }
    public LocalTime getSyncTo() { return syncTo; }
    public void setSyncTo(LocalTime syncTo) { this.syncTo = syncTo; }
    public Integer getSyncEvery() { return syncEvery; }
    public void setSyncEvery(Integer syncEvery) { this.syncEvery = syncEvery; }
    public String getSyncDays() { return syncDays; }
    public void setSyncDays(String syncDays) { this.syncDays = syncDays; }
    public String getSyncTimes() { return syncTimes; }
    public void setSyncTimes(String syncTimes) { this.syncTimes = syncTimes; }
    public Integer getConfirmCutoffDay() { return confirmCutoffDay; }
    public void setConfirmCutoffDay(Integer confirmCutoffDay) { this.confirmCutoffDay = confirmCutoffDay; }
    public Integer getReportCutoffDay() { return reportCutoffDay; }
    public void setReportCutoffDay(Integer reportCutoffDay) { this.reportCutoffDay = reportCutoffDay; }
    public Integer getLeaveAccrualDay() { return leaveAccrualDay; }
    public void setLeaveAccrualDay(Integer leaveAccrualDay) { this.leaveAccrualDay = leaveAccrualDay; }
    public String getWorkDays() { return workDays; }
    public void setWorkDays(String workDays) { this.workDays = workDays; }
    public String getScheduleUpdatedBy() { return scheduleUpdatedBy; }
    public void setScheduleUpdatedBy(String scheduleUpdatedBy) { this.scheduleUpdatedBy = scheduleUpdatedBy; }
    public LocalDateTime getScheduleUpdatedAt() { return scheduleUpdatedAt; }
    public void setScheduleUpdatedAt(LocalDateTime scheduleUpdatedAt) { this.scheduleUpdatedAt = scheduleUpdatedAt; }
    public String getDeviceUrl() { return deviceUrl; }
    public void setDeviceUrl(String deviceUrl) { this.deviceUrl = deviceUrl; }
    public String getDeviceUsername() { return deviceUsername; }
    public void setDeviceUsername(String deviceUsername) { this.deviceUsername = deviceUsername; }
    public String getDevicePassword() { return devicePassword; }
    public void setDevicePassword(String devicePassword) { this.devicePassword = devicePassword; }
    public LocalDate getSyncFromDate() { return syncFromDate; }
    public void setSyncFromDate(LocalDate syncFromDate) { this.syncFromDate = syncFromDate; }
    public LocalDate getLastSyncDate() { return lastSyncDate; }
    public void setLastSyncDate(LocalDate lastSyncDate) { this.lastSyncDate = lastSyncDate; }
    public LocalDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(LocalDateTime lastSyncAt) { this.lastSyncAt = lastSyncAt; }
    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }
    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
