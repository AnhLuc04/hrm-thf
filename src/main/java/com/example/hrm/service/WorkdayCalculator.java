package com.example.hrm.service;

import com.example.hrm.entity.Attendance;
import com.example.hrm.entity.AttendanceConfirmation;
import com.example.hrm.entity.HolidayConfig;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * =====================================================================
 *  ĐẾM CÔNG 1 NHÂN VIÊN TRONG THÁNG (tính đến ngày asOf)
 * =====================================================================
 *  Công mỗi ngày, ưu tiên:
 *    1. Yêu cầu XÁC NHẬN CÔNG ĐÃ DUYỆT (AttendanceConfirmation.approvalStatus = APPROVED)
 *         PRESENT = 1, HALF_DAY = 0.5, ANNUAL_LEAVE = 1 (phép, hưởng lương), UNPAID_LEAVE = 0
 *         (bảng quy đổi đổi được: payroll.confirm.values)
 *         Phép / nghỉ không lương theo BUỔI (sessionType sáng/chiều) = nửa ngày, cộng với nửa ngày còn lại theo máy chấm.
 *    2. Công đã xác nhận trên bảng chấm công (Attendance.confirmedWork)
 *    3. Công máy chấm (Attendance.workDay)
 *
 *  NGÀY LỄ (HolidayConfig, không trùng Chủ nhật):
 *    - Mọi nhân viên được 1 công lễ hưởng lương (congLe), dù không đi làm.
 *    - Đi làm ngày lễ: toàn bộ giờ làm × hệ số của ngày lễ (wageMultiplier, VD 3.0 = 300%) cộng vào gioLeQuyDoi,
 *      KHÔNG tính thêm giờ thường cho ngày đó.
 *  Chủ nhật: giờ làm tính là thêm giờ Chủ nhật (như trước).
 */
public final class WorkdayCalculator {

    public static final String APPROVED = "APPROVED";

    /** Mặc định quy đổi trạng thái xác nhận công -> số công */
    public static final String DEFAULT_VALUES = "PRESENT=1,HALF_DAY=0.5,ANNUAL_LEAVE=1,UNPAID_LEAVE=0";

    private static final Set<String> WORK_STATUSES = Set.of("PRESENT", "HALF_DAY");
    private static final String PAID_LEAVE = "ANNUAL_LEAVE";
    private static final String UNPAID_LEAVE = "UNPAID_LEAVE";

    private WorkdayCalculator() {
    }

    /** Kết quả đếm công */
    public static final class Result {
        public double congHL;
        public int soNgayDiLam;
        public double gioOtThuong;
        public double gioOtChuNhat;
        public double congLe;
        public double gioLamNgayLe;
        /** Σ giờ làm ngày lễ × hệ số ngày lễ */
        public double gioLeQuyDoi;
        public double soNgayPhep;
        public double soNgayKhongLuong;
        public int soNgayXacNhan;
        /** trạng thái xác nhận chưa có trong bảng quy đổi (bỏ qua, dùng công máy chấm) */
        public final Set<String> unknownStatuses = new TreeSet<>();
    }

    /** "PRESENT=1,HALF_DAY=0.5" -> map (chữ hoa) */
    public static Map<String, Double> parseValues(String csv) {
        Map<String, Double> m = new HashMap<>();
        String s = csv == null || csv.isBlank() ? DEFAULT_VALUES : csv;
        for (String part : s.split(",")) {
            String[] kv = part.split("[=:]");
            if (kv.length == 2) {
                try {
                    m.put(kv[0].trim().toUpperCase(Locale.ROOT), Double.valueOf(kv[1].trim()));
                } catch (NumberFormatException ignored) {
                    // bỏ dòng sai
                }
            }
        }
        return m;
    }

    public static Result compute(List<Attendance> attendances,
                                 List<AttendanceConfirmation> confirmations,
                                 Map<LocalDate, HolidayConfig> holidays,
                                 Map<String, Double> statusValues,
                                 LocalDate from,
                                 LocalDate asOf,
                                 double gioChuanNgay,
                                 double gioNghiTrua,
                                 double otToiThieuPhut) {

        Result r = new Result();

        Map<LocalDate, List<Attendance>> attByDay = new TreeMap<>();
        for (Attendance a : attendances) {
            if (a.getDate() != null) {
                attByDay.computeIfAbsent(a.getDate(), k -> new ArrayList<>()).add(a);
            }
        }
        Map<LocalDate, List<AttendanceConfirmation>> confByDay = new TreeMap<>();
        for (AttendanceConfirmation cf : confirmations) {
            if (cf.getAttendanceDate() != null && APPROVED.equalsIgnoreCase(trim(cf.getApprovalStatus()))) {
                confByDay.computeIfAbsent(cf.getAttendanceDate(), k -> new ArrayList<>()).add(cf);
            }
        }

        SortedSet<LocalDate> days = new TreeSet<>();
        days.addAll(attByDay.keySet());
        days.addAll(confByDay.keySet());
        days.addAll(holidays.keySet());

        for (LocalDate day : days) {
            if (day.isBefore(from) || day.isAfter(asOf)) {
                continue;
            }
            boolean chuNhat = day.getDayOfWeek() == DayOfWeek.SUNDAY;
            List<Attendance> atts = attByDay.getOrDefault(day, List.of());
            HolidayConfig hol = chuNhat ? null : holidays.get(day);

            // ----- ngày lễ -----
            if (hol != null) {
                r.congLe += 1;
                r.congHL += 1;
                double gio = 0;
                for (Attendance a : atts) {
                    gio += PayrollCalculationService.overtimeHours(a, true, gioChuanNgay, gioNghiTrua, otToiThieuPhut);
                }
                if (gio > 0) {
                    double heSo = hol.getWageMultiplier() == null || hol.getWageMultiplier() <= 0 ? 3.0 : hol.getWageMultiplier();
                    r.gioLamNgayLe += gio;
                    r.gioLeQuyDoi += gio * heSo;
                    r.soNgayDiLam++;
                }
                continue;
            }

            // ----- công máy chấm / công đã xác nhận trên bảng chấm công -----
            double cong = 0;
            for (Attendance a : atts) {
                cong += a.getConfirmedWork() != null ? a.getConfirmedWork()
                        : a.getWorkDay() != null ? a.getWorkDay() : 0;
            }

            // ----- yêu cầu xác nhận công đã duyệt (theo thứ tự thời gian duyệt) -----
            List<AttendanceConfirmation> cfs = new ArrayList<>(confByDay.getOrDefault(day, List.of()));
            cfs.sort(Comparator.comparing(WorkdayCalculator::approvedAt));
            boolean confirmed = false;
            for (AttendanceConfirmation cf : cfs) {
                String status = trim(cf.getConfirmStatus()).toUpperCase(Locale.ROOT);
                Double val = statusValues.get(status);
                if (val == null) {
                    if (!status.isEmpty()) {
                        r.unknownStatuses.add(status);
                    }
                    continue;
                }
                confirmed = true;
                boolean half = isHalfSession(cf.getSessionType());
                if (WORK_STATUSES.contains(status) || !half) {
                    cong = val;                                     // cả ngày: thay hẳn công máy chấm
                    count(r, status, half ? 0.5 : 1);
                } else {
                    // nghỉ theo buổi: nửa ngày nghỉ + nửa ngày còn lại theo máy chấm
                    cong = Math.min(1, Math.min(cong, 0.5) + val * 0.5);
                    count(r, status, 0.5);
                }
            }
            if (confirmed) {
                r.soNgayXacNhan++;
            }

            r.congHL += cong;
            boolean diLam = cong > 0 && !(confirmed && onlyLeave(cfs, statusValues));
            if (diLam) {
                r.soNgayDiLam++;
            }

            // ----- giờ thêm -----
            for (Attendance a : atts) {
                double ot = PayrollCalculationService.overtimeHours(a, chuNhat, gioChuanNgay, gioNghiTrua, otToiThieuPhut);
                if (chuNhat) {
                    r.gioOtChuNhat += ot;
                } else {
                    r.gioOtThuong += ot;
                }
            }
        }
        return r;
    }

    private static void count(Result r, String status, double days) {
        if (PAID_LEAVE.equals(status)) {
            r.soNgayPhep += days;
        } else if (UNPAID_LEAVE.equals(status)) {
            r.soNgayKhongLuong += days;
        }
    }

    /** Ngày chỉ có phép cả ngày (không đi làm) */
    private static boolean onlyLeave(List<AttendanceConfirmation> cfs, Map<String, Double> values) {
        for (AttendanceConfirmation cf : cfs) {
            String st = trim(cf.getConfirmStatus()).toUpperCase(Locale.ROOT);
            if (values.containsKey(st) && (WORK_STATUSES.contains(st) || isHalfSession(cf.getSessionType()))) {
                return false;
            }
        }
        return true;
    }

    /** sessionType là 1 buổi (sáng / chiều / nửa ngày) */
    static boolean isHalfSession(String sessionType) {
        String s = PayrollAccessNorm.norm(sessionType);
        if (s.isEmpty()) {
            return false;
        }
        return s.contains("sang") || s.contains("chieu") || s.contains("morning") || s.contains("afternoon")
                || s.contains("half") || s.contains("nua") || s.equals("am") || s.equals("pm");
    }

    private static LocalDateTime approvedAt(AttendanceConfirmation cf) {
        if (cf.getApprovedAt() != null) {
            return cf.getApprovedAt();
        }
        return cf.getCreatedAt() != null ? cf.getCreatedAt() : LocalDateTime.MIN;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** Chuẩn hoá chuỗi: bỏ dấu, chữ thường */
    static final class PayrollAccessNorm {
        private PayrollAccessNorm() {
        }

        static String norm(String s) {
            if (s == null) {
                return "";
            }
            String n = java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
            return n.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
        }
    }
}