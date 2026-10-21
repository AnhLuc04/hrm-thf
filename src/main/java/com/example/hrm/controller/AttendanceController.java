package com.example.hrm.controller;

import com.example.hrm.dto.AttendanceAlertDTO;
import com.example.hrm.dto.AttendanceReportRow;
import com.example.hrm.dto.ManagerDetailRequestDTO;
import com.example.hrm.entity.AttendanceConfirmation;
import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.entity.LeaveBalance;
import com.example.hrm.entity.User;
import com.example.hrm.repository.AttendanceConfirmationRepository;
import com.example.hrm.repository.LeaveBalanceRepository;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.service.AttendanceReportService;
import com.example.hrm.service.AttendanceService;
import com.example.hrm.service.UnitScope;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.ByteArrayInputStream;
import java.security.Principal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Controller
@RequiredArgsConstructor
@RequestMapping("/attendance")
public class AttendanceController {

    private final AttendanceService attendanceService;
    private final AttendanceReportService attendanceReportService;
    private final AttendanceConfirmationRepository confirmationRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final UserRepository userRepository;
    private final UnitScope unitScope;   // ĐƠN VỊ: chỉ thấy / duyệt người cùng đơn vị
    private final com.example.hrm.service.ConfirmDeadlineService confirmDeadlineService;   // hạn xác nhận N ngày

    // ──────────────────────────────────────────────────────────────────────────
    // KHÓA SỔ
    // Công tháng trước chỉ còn mở đến hết ngày CUTOFF của tháng sau.
    //   1 = chỉ ngày 1 còn tạo/duyệt được công tháng trước, từ ngày 2 là khóa.
    //   0 = sang tháng mới là khóa ngay.
    // (Giá trị cũ: tạo đơn = 3, phê duyệt = 5)
    // ──────────────────────────────────────────────────────────────────────────
    private static final int CREATE_CUTOFF_DAY  = 1;
    private static final int APPROVE_CUTOFF_DAY = 1;

    // NGÀY DỪNG theo ĐƠN VỊ (HCNS cấu hình ở Lịch làm việc & giờ chấm). Chưa cấu hình = 2 hằng số trên như bản cũ.
    private int createCutoff(String employeeNo) {
        return CompanyUnit.confirmCutoffOf(employeeNo == null ? unitScope.legacyUnit()
                : userRepository.findByEmployeeNo(employeeNo).map(unitScope::unitOf).orElse(unitScope.legacyUnit()));
    }

    private int approveCutoff(String employeeNo) {
        return CompanyUnit.reportCutoffOf(employeeNo == null ? unitScope.legacyUnit()
                : userRepository.findByEmployeeNo(employeeNo).map(unitScope::unitOf).orElse(unitScope.legacyUnit()));
    }

    /** Ngày dừng duyệt / xem tháng trước của người đang đăng nhập (đơn vị đang làm việc) */
    private int myApproveCutoff() {
        CompanyUnit u = unitScope.scopeUnit();
        if (u == null) {
            u = unitScope.unitOf(unitScope.currentUser());
        }
        return CompanyUnit.reportCutoffOf(u);
    }

    /** true = đơn không tồn tại hoặc ngày công đã qua ngày dừng của đơn vị nhân viên (approve: dừng duyệt, ngược lại: dừng gửi) */
    private boolean isLocked(Long id, boolean approve) {
        return confirmationRepository.findById(id)
                .map(c -> !isOpen(c.getAttendanceDate(), approve ? approveCutoff(c.getEmployeeNo()) : createCutoff(c.getEmployeeNo())))
                .orElse(true);
    }

    /** true = ngày công này còn được tạo/duyệt đơn */
    private boolean isOpen(LocalDate date, int cutoffDay) {
        if (date == null) return false;
        LocalDate today = LocalDate.now();
        YearMonth cur   = YearMonth.from(today);
        YearMonth ym    = YearMonth.from(date);
        if (date.isAfter(today)) return false;          // tương lai
        if (ym.equals(cur))      return true;           // tháng hiện tại
        return ym.equals(cur.minusMonths(1)) && today.getDayOfMonth() <= cutoffDay;
    }

    /** true = đơn không tồn tại hoặc ngày công của đơn đã khóa sổ */
    private boolean isLocked(Long id, int cutoffDay) {
        return confirmationRepository.findById(id)
                .map(c -> !isOpen(c.getAttendanceDate(), cutoffDay))
                .orElse(true);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // EXPORT
    // ──────────────────────────────────────────────────────────────────────────

    @GetMapping("/export")
    public ResponseEntity<InputStreamResource> exportExcel(
            @RequestParam String month,
            @RequestParam(required = false) String employeeNo,
            Principal principal) throws Exception {

        String currentUsername = principal.getName();
        User currentUser = userRepository.findByEmployeeNo(currentUsername)
                .orElseGet(() -> userRepository.findByUsername(currentUsername).orElse(null));

        boolean isManager = seesWholeReport(currentUser);

        // ← THÊM: Áp dụng logic phân quyền giống hệt hàm report()
        String filterEmployeeNo;
        if (isManager) {
            // Trưởng phòng: được export toàn bộ (employeeNo=null),
            // hoặc export riêng 1 nhân viên nếu chủ động truyền employeeNo lên URL
            filterEmployeeNo = employeeNo;
        } else {
            // Nhân viên thường: LUÔN ép về chính mình,
            // bỏ qua hoàn toàn tham số employeeNo trên URL để tránh export data người khác
            filterEmployeeNo = currentUsername;
        }

        // 🔒 ĐƠN VỊ: xuất riêng 1 người thì người đó phải cùng đơn vị
        if (isManager && filterEmployeeNo != null && !filterEmployeeNo.isBlank()) {
            User target = userRepository.findByEmployeeNo(filterEmployeeNo).orElse(null);
            if (target == null || !unitScope.inScope(target)) {
                filterEmployeeNo = currentUsername;
            }
        }
        ByteArrayInputStream in = attendanceReportService.exportExcel(month, filterEmployeeNo,
                isManager ? unitScope.employeeNos() : null);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Disposition", "attachment; filename=attendance_" + month + ".xlsx");

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(new InputStreamResource(in));
    }
    // ──────────────────────────────────────────────────────────────────────────
    // SYNC
    // ──────────────────────────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/sync")
    public String sync() {
        attendanceService.syncAttendance();
        return "SYNC OK";
    }
    // ──────────────────────────────────────────────────────────────────────────
    // REPORT
    // ──────────────────────────────────────────────────────────────────────────
    @GetMapping("/report")
    public String report(@RequestParam(required = false) String month,
                         Principal principal, Model model) {
        if (month == null || month.isEmpty()) {
            month = YearMonth.now().toString();
        }

        String currentUsername = principal.getName();
        User currentUser = userRepository.findByEmployeeNo(currentUsername)
                .orElseGet(() -> userRepository.findByUsername(currentUsername).orElse(null));

        List<AttendanceReportRow> report;
        boolean isManager = false;

        if (seesWholeReport(currentUser)) {
            isManager = true;
            // 🔒 ĐƠN VỊ: bảng công tổng chỉ gồm nhân viên của đơn vị mình
            report = attendanceReportService.buildMonthlyReport(month, null, unitScope.employeeNos());
        } else {
            report = attendanceReportService.buildMonthlyReport(month, currentUsername);
        }

        // Bọc vào ArrayList mới (mutable) để tránh UnsupportedOperationException
        // khi list trả về từ service là immutable/unmodifiable
        report = new ArrayList<>(report);

        // Sắp xếp theo phòng ban (lấy từ DB), cùng phòng ban thì theo tên A-Z
        report.sort(Comparator
                .comparing(AttendanceReportRow::getDepartment,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(AttendanceReportRow::getFullName, String.CASE_INSENSITIVE_ORDER));

        // 🌟 THÊM MỚI: bổ sung dữ liệu Phép Năm (Tháng này / Tích lũy / Còn lại) cho từng dòng
        enrichLeaveData(report, month);

        YearMonth ym = YearMonth.parse(month);
        model.addAttribute("report", report);
        model.addAttribute("month", month);
        model.addAttribute("days", ym.lengthOfMonth());
        model.addAttribute("isManager", isManager);
        // NGÀY DỪNG (HCNS cấu hình): hiện trên bảng công tổng để mọi người biết hạn
        model.addAttribute("cutoffConfirm", createCutoff(currentUser != null ? currentUser.getEmployeeNo() : null));
        model.addAttribute("cutoffReport", myApproveCutoff());

        // 🌟 Phép năm khả dụng cho trang lịch cá nhân — CÙNG công thức với trang giải trình
        //    = total_annual_leave − (ca phép năm chờ duyệt / trả lại × 0.5)
        if (!isManager) {
            String empNo = !report.isEmpty() && report.get(0).getEmployeeNo() != null
                    ? report.get(0).getEmployeeNo()
                    : (currentUser != null ? currentUser.getEmployeeNo() : currentUsername);
            empNo = empNo == null ? "" : empNo.trim();
            LeaveBalance lb = leaveBalanceRepository.findByEmployeeNo(empNo);
            Double usedAnnual = (lb != null) ? toDouble(lb.getUsedAnnualLeave()) : null;
            model.addAttribute("usedAnnualLeave", usedAnnual != null ? usedAnnual : 0.0);   // cột used_annual_leave
            model.addAttribute("totalLeave",     getTotalAnnualLeave(empNo));
            model.addAttribute("pendingLeave",   countAnnualLeaveSessions(empNo) * LEAVE_PER_SESSION);
            model.addAttribute("availableLeave", calcAvailableLeave(empNo));
        }

        return isManager ? "attendance-report-admin" : "attendance-report-user";
    }

    // 🌟 SỬA: thêm tham số "month", và tra thêm employeeType để loại trừ Thử việc/Học việc khỏi cột "Tháng này"
    private void enrichLeaveData(List<AttendanceReportRow> report, String month) {
        List<String> empNos = report.stream()
                .map(AttendanceReportRow::getEmployeeNo)
                .filter(no -> no != null && !no.isBlank())
                .distinct()
                .toList();

        if (empNos.isEmpty()) return;

        Map<String, LeaveBalance> balanceMap = leaveBalanceRepository.findByEmployeeNoIn(empNos).stream()
                .collect(Collectors.toMap(LeaveBalance::getEmployeeNo, b -> b, (a, b) -> a));

        // 🌟 THÊM MỚI: tra loại nhân viên (CHINH_THUC / THU_VIEC / HOC_VIEC) theo employeeNo
        Map<String, String> employeeTypeMap = userRepository.findByEmployeeNoIn(empNos).stream()
                .collect(Collectors.toMap(User::getEmployeeNo, User::getEmployeeType, (a, b) -> a));

        // Tính số tháng đã trôi qua giữa tháng đang xem và tháng hiện tại thực
        YearMonth viewedMonth = YearMonth.parse(month);
        YearMonth currentMonth = YearMonth.now();
        long monthsAhead = java.time.temporal.ChronoUnit.MONTHS.between(viewedMonth, currentMonth);

        for (AttendanceReportRow row : report) {
            LeaveBalance balance = balanceMap.get(row.getEmployeeNo());
            String empType = employeeTypeMap.get(row.getEmployeeNo());

            // 🌟 THÊM MỚI: Thử việc / Học việc không được cộng phép hàng tháng -> "Tháng này" luôn = 0
            boolean isProbationOrTrainee = "THU_VIEC".equals(empType) || "HOC_VIEC".equals(empType);

            if (balance != null) {
                double accrual = balance.getAccrualPerMonth();
                double adjust = (monthsAhead > 0) ? monthsAhead * accrual : 0.0;

                double adjustedTotal = Math.max(0, balance.getTotalAnnualLeave() - adjust);
                double adjustedRemaining = balance.getRemaining() - adjust;

                row.setLeaveAccruedThisMonth(isProbationOrTrainee ? 0.0 : accrual); // 🌟 SỬA
                row.setLeaveAccruedTotal(adjustedTotal);
                row.setLeaveRemaining(adjustedRemaining);
            } else {
                row.setLeaveAccruedThisMonth(isProbationOrTrainee ? 0.0 : 1.0); // 🌟 SỬA
                row.setLeaveAccruedTotal(null);
                row.setLeaveRemaining(null);
            }
            // Cột "Sử dụng" (totalLeaveDays) KHÔNG đổi — giữ nguyên giá trị buildMonthlyReport() đã tính
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // NHÂN VIÊN: trang giải trình
    // ──────────────────────────────────────────────────────────────────────────

    @GetMapping("/confirm/{employeeNo}")
    public String confirmPage(@PathVariable String employeeNo, Model model, Principal principal) {
        if (!canActFor(principal, employeeNo)) {
            return "redirect:/attendance/confirm/" + ownNo(principal);
        }
        LocalDate today = LocalDate.now();
        LocalDate to = today;

        // 🔒 Đến hết ngày CREATE_CUTOFF_DAY: còn được giải trình công tháng trước (from = đầu tháng trước)
        //    Sau đó: chỉ còn công tháng hiện tại (from = đầu tháng này)
        LocalDate from = (today.getDayOfMonth() <= createCutoff(employeeNo))
                ? today.minusMonths(1).withDayOfMonth(1)
                : today.withDayOfMonth(1);

        List<AttendanceAlertDTO> alerts = attendanceService.getAttendanceIssues(employeeNo, from, to);

        List<AttendanceConfirmation> pending  = confirmationRepository.findByEmployeeNoAndStatusAndMonth(employeeNo, "PENDING",  from, to);
        List<AttendanceConfirmation> approved = confirmationRepository.findByEmployeeNoAndStatusAndMonth(employeeNo, "APPROVED", from, to);
        List<AttendanceConfirmation> rejected = confirmationRepository.findByEmployeeNoAndStatusAndMonth(employeeNo, "REJECTED", from, to);
        List<AttendanceConfirmation> returned = confirmationRepository.findByEmployeeNoAndStatusAndMonth(employeeNo, "RETURNED", from, to);

        // 🌟 Một nguồn duy nhất: availableLeave (hiển thị + kiểm tra đều dùng số này)
        double availableLeave = calcAvailableLeave(employeeNo);

        model.addAttribute("totalLeave",      getTotalAnnualLeave(employeeNo));                       // cột total_annual_leave
        model.addAttribute("usedLeave",       countAnnualLeaveSessions(employeeNo) * LEAVE_PER_SESSION); // đã tạo đơn
        model.addAttribute("availableLeave",  availableLeave);
        model.addAttribute("hasAnnualLeave",  hasEnoughLeave(availableLeave, 1)); // giữ để tương thích
        model.addAttribute("employeeNo",      employeeNo);
        model.addAttribute("alerts",          alerts);
        model.addAttribute("pendingList",     pending);
        model.addAttribute("approvedList",    approved);
        model.addAttribute("rejectedList",    rejected);
        model.addAttribute("returnedList",    returned);
        model.addAttribute("confirmDays",     confirmDeadlineService.days(
                userRepository.findByEmployeeNo(employeeNo).map(unitScope::unitOf).orElse(unitScope.legacyUnit())));

        return "attendance-confirm";
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PHÉP NĂM: 1 công thức duy nhất cho hiển thị + kiểm tra
    // availableLeave = total_annual_leave − (ca phép năm CHỜ DUYỆT / TRẢ LẠI × 0.5)
    // ──────────────────────────────────────────────────────────────────────────
    private static final String ANNUAL = "ANNUAL_LEAVE";
    private static final org.slf4j.Logger LEAVE_LOG = org.slf4j.LoggerFactory.getLogger("PHEP_NAM");
    private static final double LEAVE_PER_SESSION = 0.5;
    private static final double EPS = 1e-6;

    /**
     * Phép khả dụng = leave_balance.total_annual_leave
     *                 − (số ca phép năm đã tạo đơn nhưng CHƯA DUYỆT × 0.5)
     * Đơn chờ duyệt / bị trả lại giữ phép ngay khi tạo (chưa duyệt cũng coi như đã dùng).
     * Đơn đã duyệt KHÔNG trừ nữa vì total_annual_leave đã tính phần này.
     * Đơn bị từ chối hoặc bị hủy → trả phép lại.
     * Ví dụ: phép năm 1 → tạo 2 ca phép năm (2 × 0.5) → còn 0, không tạo thêm được.
     */
    private double calcAvailableLeave(String employeeNo) {
        double total = getTotalAnnualLeave(employeeNo);
        long usedSessions = countAnnualLeaveSessions(employeeNo);
        double v = total - usedSessions * LEAVE_PER_SESSION;
        LEAVE_LOG.info("[PHEP] {} -> total_annual_leave={}, da tao don={} ca, kha dung={}", employeeNo, total, usedSessions, v);
        // không hiển thị số âm; làm tròn 2 chữ số để tránh lỗi số thực kiểu 0.4999999
        return Math.max(0.0, Math.round(v * 100.0) / 100.0);
    }

    /** Cột total_annual_leave của leave_balance (chưa có dòng → 0) */
    private double getTotalAnnualLeave(String employeeNo) {
        LeaveBalance balance = leaveBalanceRepository.findByEmployeeNo(employeeNo);
        Double base = (balance != null) ? toDouble(balance.getTotalAnnualLeave()) : null;
        return base != null ? base : 0.0;
    }

    /** Số ca phép năm đã tạo đơn nhưng CHƯA DUYỆT (chờ duyệt + bị trả lại) */
    private long countAnnualLeaveSessions(String employeeNo) {
        LocalDate yFrom = LocalDate.now().withDayOfYear(1);
        LocalDate yTo   = yFrom.plusYears(1).minusDays(1);

        // Chờ duyệt: dùng đúng câu SQL đã có (approval_status='PENDING' AND confirm_status='ANNUAL_LEAVE')
        long pending = confirmationRepository.countPendingAnnualLeaveRequests(employeeNo);

        // Bị trả lại (chờ sửa & gửi lại) trong năm — vẫn giữ phép.
        // KHÔNG trừ đơn ĐÃ DUYỆT: total_annual_leave đã phản ánh phần phép đã duyệt.
        long others = Stream.of("RETURNED")
                .flatMap(st -> confirmationRepository
                        .findByEmployeeNoAndStatusAndMonth(employeeNo, st, yFrom, yTo).stream())
                .filter(c -> ANNUAL.equals(c.getConfirmStatus()))
                .count();

        LEAVE_LOG.info("[PHEP] {} -> cho duyet={} ca, tra lai={} ca", employeeNo, pending, others);
        return pending + others;
    }

    /** Chấp nhận cả double lẫn Double (null → null) */
    private static Double toDouble(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : null;
    }

    private boolean hasEnoughLeave(double available, int sessions) {
        return available + EPS >= sessions * LEAVE_PER_SESSION;
    }

    /**
     * 🌟 Bảo đảm đơn vừa tạo giữ ĐÚNG phương án nhân viên chọn.
     * AttendanceService.saveConfirm đang tự đổi ANNUAL_LEAVE → HALF_DAY khi nó
     * kiểm tra phép theo trường khác (leave_balance.remaining). Controller đã kiểm tra
     * phép theo total_annual_leave rồi, nên ghi đè lại phương án đã chọn.
     */
    private void enforceConfirmStatus(String employeeNo, LocalDate day, String sessionType, String confirmStatus) {
        confirmationRepository.findByEmployeeNoAndStatusAndMonth(employeeNo, "PENDING", day, day).stream()
                .filter(c -> sessionType.equals(c.getSessionType()))
                .filter(c -> !confirmStatus.equals(c.getConfirmStatus()))
                .forEach(c -> {
                    LEAVE_LOG.warn("[PHEP] Service da doi phuong an don id={} tu {} -> sua lai {}",
                            c.getId(), c.getConfirmStatus(), confirmStatus);
                    c.setConfirmStatus(confirmStatus);
                    confirmationRepository.save(c);
                });
    }

    @PostMapping("/confirm/save")
    public String saveConfirm(@RequestParam String employeeNo,
                              @RequestParam String date,
                              @RequestParam String confirmStatus,
                              @RequestParam String issueType,
                              @RequestParam String sessionType,
                              @RequestParam(required = false) String note,
                              RedirectAttributes ra, Principal principal) {
        String redirect = "redirect:/attendance/confirm/" + employeeNo;
        if (!canActFor(principal, employeeNo)) {
            ra.addFlashAttribute("error", "Bạn chỉ gửi giải trình được cho chính mình.");
            return "redirect:/attendance/confirm/" + ownNo(principal);
        }

        LocalDate d;
        try {
            d = LocalDate.parse(date);
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("error", "Ngày không hợp lệ: " + date);
            return redirect;
        }

        // 🔒 Khóa sổ: không cho tạo đơn cho ngày công đã khóa
        if (!isOpen(d, createCutoff(employeeNo))) {
            ra.addFlashAttribute("error", "Đã khóa sổ, không thể tạo đơn giải trình cho ngày " + d + ".");
            return redirect;
        }

        // 🌟 Phép năm: kiểm tra theo availableLeave (1 ca = 0.5 ngày)
        if (ANNUAL.equals(confirmStatus)) {
            double available = calcAvailableLeave(employeeNo);
            if (!hasEnoughLeave(available, 1)) {
                ra.addFlashAttribute("error", String.format(
                        "Không đủ phép năm: cần 0.5 ngày, chỉ còn %.1f ngày phép năm.", available));
                return redirect;
            }
        }

        try {
            attendanceService.saveConfirm(employeeNo, d, confirmStatus, note, issueType, sessionType);
            enforceConfirmStatus(employeeNo, d, sessionType, confirmStatus);
            ra.addFlashAttribute("message", "Đã gửi đơn giải trình.");
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("error", "Không tạo được đơn: " + ex.getMessage());
        }
        return redirect;
    }

    /**
     * Giải trình CẢ NGÀY: tạo 1 đơn cho mỗi ca lỗi trong ngày,
     * dùng chung phương án (confirmStatus) và lý do (note).
     * Frontend gửi các cặp sessionTypes[i] / issueTypes[i] theo đúng thứ tự.
     */
    @PostMapping("/confirm/save-day")
    public String saveConfirmDay(@RequestParam String employeeNo,
                                 @RequestParam String date,
                                 @RequestParam String confirmStatus,
                                 @RequestParam List<String> sessionTypes,
                                 @RequestParam List<String> issueTypes,
                                 @RequestParam(required = false) String note,
                                 RedirectAttributes ra, Principal principal) {

        String redirect = "redirect:/attendance/confirm/" + employeeNo;
        if (!canActFor(principal, employeeNo)) {
            ra.addFlashAttribute("error", "Bạn chỉ gửi giải trình được cho chính mình.");
            return "redirect:/attendance/confirm/" + ownNo(principal);
        }

        if (sessionTypes.isEmpty() || sessionTypes.size() != issueTypes.size()) {
            ra.addFlashAttribute("error", "Dữ liệu ca giải trình không hợp lệ.");
            return redirect;
        }

        LocalDate day;
        try {
            day = LocalDate.parse(date);
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("error", "Ngày không hợp lệ: " + date);
            return redirect;
        }

        // 🔒 Khóa sổ: không cho tạo đơn cho ngày công đã khóa
        if (!isOpen(day, createCutoff(employeeNo))) {
            ra.addFlashAttribute("error", "Đã khóa sổ, không thể tạo đơn giải trình cho ngày " + day + ".");
            return redirect;
        }

        // Chặn gửi trùng: bỏ qua ca đã có đơn (chờ duyệt / trả lại / đã duyệt / từ chối)
        Set<String> usedSessions = Stream.of("PENDING", "RETURNED", "APPROVED", "REJECTED")
                .flatMap(st -> confirmationRepository
                        .findByEmployeeNoAndStatusAndMonth(employeeNo, st, day, day).stream())
                .map(AttendanceConfirmation::getSessionType)
                .collect(Collectors.toSet());

        List<Integer> toCreate = new ArrayList<>();
        for (int i = 0; i < sessionTypes.size(); i++) {
            if (!usedSessions.contains(sessionTypes.get(i))) toCreate.add(i);
        }

        if (toCreate.isEmpty()) {
            ra.addFlashAttribute("error", "Các ca của ngày " + day + " đều đã có đơn giải trình.");
            return redirect;
        }

        // Phép năm: mỗi ca trừ 0.5 ngày → kiểm tra đủ quỹ cho tất cả các ca
        if (ANNUAL.equals(confirmStatus)) {
            double available = calcAvailableLeave(employeeNo);
            double need = toCreate.size() * LEAVE_PER_SESSION;
            if (!hasEnoughLeave(available, toCreate.size())) {
                ra.addFlashAttribute("error", String.format(
                        "Không đủ phép năm: cần %.1f ngày, chỉ còn %.1f ngày phép năm.", need, available));
                return redirect;
            }
        }

        try {
            for (int i : toCreate) {
                attendanceService.saveConfirm(employeeNo, day, confirmStatus, note,
                        issueTypes.get(i), sessionTypes.get(i));
                enforceConfirmStatus(employeeNo, day, sessionTypes.get(i), confirmStatus);
            }
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("error", "Không tạo được đơn: " + ex.getMessage());
            return redirect;
        }

        ra.addFlashAttribute("message", String.format(
                "Đã gửi %d đơn giải trình cho ngày %02d/%02d/%d.",
                toCreate.size(), day.getDayOfMonth(), day.getMonthValue(), day.getYear()));
        return redirect;
    }

    /** Nhân viên sửa lại đơn bị trả về và gửi lại để duyệt */
    @PostMapping("/confirm/resubmit")
    public String resubmit(@RequestParam Long id,
                           @RequestParam String employeeNo,
                           @RequestParam String confirmStatus,
                           @RequestParam String issueType,
                           @RequestParam String sessionType,
                           @RequestParam(required = false) String note,
                           RedirectAttributes ra, Principal principal) {
        String redirect = "redirect:/attendance/confirm/" + employeeNo;
        if (!canActFor(principal, employeeNo)) {
            ra.addFlashAttribute("error", "Bạn chỉ gửi lại được đơn của chính mình.");
            return "redirect:/attendance/confirm/" + ownNo(principal);
        }

        // 🔒 Khóa sổ: không cho gửi lại đơn của ngày công đã khóa
        if (isLocked(id, false)) {
            ra.addFlashAttribute("error", "Đã khóa sổ, không thể gửi lại đơn này.");
            return redirect;
        }

        // 🌟 Đổi sang phép năm khi gửi lại → cũng kiểm tra theo availableLeave
        //    (đơn cũ vốn đã là phép năm thì giữ nguyên, không trừ thêm)
        if (ANNUAL.equals(confirmStatus)) {
            boolean wasAnnual = confirmationRepository.findById(id)
                    .map(c -> ANNUAL.equals(c.getConfirmStatus()))
                    .orElse(false);
            double available = calcAvailableLeave(employeeNo);
            if (!wasAnnual && !hasEnoughLeave(available, 1)) {
                ra.addFlashAttribute("error", String.format(
                        "Không đủ phép năm: cần 0.5 ngày, chỉ còn %.1f ngày phép năm.", available));
                return redirect;
            }
        }

        try {
            attendanceService.resubmitConfirm(id, employeeNo, confirmStatus, note, issueType, sessionType);
            confirmationRepository.findById(id).ifPresent(c -> {
                if (!confirmStatus.equals(c.getConfirmStatus())) {
                    c.setConfirmStatus(confirmStatus);
                    confirmationRepository.save(c);
                }
            });
            ra.addFlashAttribute("message", "Đã gửi lại đơn giải trình.");
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("error", "Không gửi lại được đơn: " + ex.getMessage());
        }
        return redirect;
    }

    @GetMapping("/confirm/delete/{id}")
    public String deleteConfirm(@PathVariable Long id, @RequestParam String employeeNo, Principal principal) {
        if (!canActFor(principal, employeeNo)) {
            return "redirect:/attendance/confirm/" + ownNo(principal);
        }
        attendanceService.deletePendingConfirm(id, employeeNo);
        return "redirect:/attendance/confirm/" + employeeNo;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // MANAGER: trang phê duyệt
    // ──────────────────────────────────────────────────────────────────────────

    @GetMapping("/manager/attendance-confirms")
    public String managerPage(@RequestParam(required = false) String month,
                              Principal principal, Model model) {
        String loginName = principal.getName();
        User manager = userRepository.findByEmployeeNo(loginName)
                .orElseGet(() -> userRepository.findByUsername(loginName).orElse(null));
        if (manager == null) return "redirect:/login";

        if (!hasManagerAccess(manager)) {
            model.addAttribute("list",  new ArrayList<>());
            model.addAttribute("month", YearMonth.now().toString());
            return "manager-attendance-confirm";
        }

        YearMonth  ym    = resolveMonth(month);
        LocalDate[] range = toDateRange(ym);
        String managerNo  = manager.getEmployeeNo();

        List<AttendanceConfirmation> list = attendanceService.getManagerPendingRequests(loginName, range[0], range[1]);

        List<ManagerDetailRequestDTO> dtoList = list.stream()
                .filter(c -> !c.getEmployeeNo().equalsIgnoreCase(managerNo))
                .map(c -> {
                    String empName = attendanceService.getEmployeeNameByNo(c.getEmployeeNo());
                    if (empName == null || empName.isEmpty()) empName = "Mã NV: " + c.getEmployeeNo();
                    return new ManagerDetailRequestDTO(c, empName);
                }).toList();

        model.addAttribute("list",  dtoList);
        model.addAttribute("month", ym.toString());
        return "manager-attendance-confirm";
    }

    // ── Duyệt / từ chối đơn lẻ ────────────────────────────────────────────────

    /**
     * Duyệt đơn lẻ.
     * Nhận thêm ?month= để redirect về đúng tháng đang xem.
     */
    @GetMapping("/manager/approve/{id}")
    public String approveRequest(@PathVariable Long id,
                                 @RequestParam(required = false) String month,
                                 Principal principal,
                                 RedirectAttributes ra) {
        String loginName  = principal.getName();
        User   manager    = userRepository.findByEmployeeNo(loginName)
                .orElseGet(() -> userRepository.findByUsername(loginName).orElse(null));
        String approverNo = (manager != null) ? manager.getEmployeeNo() : loginName;

        // 🔒 Khóa sổ
        if (isLocked(id, true)) {
            ra.addFlashAttribute("error", "Đã khóa sổ, không thể duyệt đơn này.");
        } else if (!attendanceService.canDecide(loginName, id)) {
            ra.addFlashAttribute("error", "Đơn này không thuộc quyền duyệt của bạn (khác đơn vị hoặc khác bộ phận).");
        } else {
            attendanceService.approveConfirm(id, approverNo);
        }
        return "redirect:/attendance/manager/attendance-confirms?month=" + resolveMonth(month);
    }

    /**
     * Từ chối đơn lẻ.
     * Nhận thêm ?month= để redirect về đúng tháng đang xem.
     */
    @GetMapping("/manager/reject/{id}")
    public String rejectRequest(@PathVariable Long id,
                                @RequestParam(required = false) String month,
                                Principal principal,
                                RedirectAttributes ra) {
        String loginName  = principal.getName();
        User   manager    = userRepository.findByEmployeeNo(loginName)
                .orElseGet(() -> userRepository.findByUsername(loginName).orElse(null));
        String approverNo = (manager != null) ? manager.getEmployeeNo() : loginName;

        // 🔒 Khóa sổ
        if (isLocked(id, true)) {
            ra.addFlashAttribute("error", "Đã khóa sổ, không thể từ chối đơn này.");
        } else if (!attendanceService.canDecide(loginName, id)) {
            ra.addFlashAttribute("error", "Đơn này không thuộc quyền từ chối của bạn (khác đơn vị hoặc khác bộ phận).");
        } else {
            attendanceService.rejectConfirm(id, approverNo);
        }
        return "redirect:/attendance/manager/attendance-confirms?month=" + resolveMonth(month);
    }

    /**
     * Trả đơn lẻ về cho nhân viên bổ sung thông tin.
     * Nhận thêm ?month= để redirect về đúng tháng đang xem.
     */
    @PostMapping("/manager/return/{id}")
    public String returnRequest(@PathVariable Long id,
                                @RequestParam String managerComment,
                                @RequestParam(required = false) String month,
                                Principal principal,
                                RedirectAttributes ra) {
        String loginName  = principal.getName();
        User   manager    = userRepository.findByEmployeeNo(loginName)
                .orElseGet(() -> userRepository.findByUsername(loginName).orElse(null));
        String approverNo = (manager != null) ? manager.getEmployeeNo() : loginName;

        // 🔒 Khóa sổ
        if (isLocked(id, true)) {
            ra.addFlashAttribute("error", "Đã khóa sổ, không thể trả lại đơn này.");
        } else if (!attendanceService.canDecide(loginName, id)) {
            ra.addFlashAttribute("error", "Đơn này không thuộc quyền xử lý của bạn (khác đơn vị hoặc khác bộ phận).");
        } else {
            attendanceService.returnForMoreInfo(id, approverNo, managerComment);
        }
        return "redirect:/attendance/manager/attendance-confirms?month=" + resolveMonth(month);
    }

    // ── Bulk actions ───────────────────────────────────────────────────────────

    @GetMapping("/manager/approve-all")
    public String approveAll(@RequestParam(required = false) String month,
                             Principal principal) {
        String loginName = principal.getName();
        User   manager   = userRepository.findByEmployeeNo(loginName)
                .orElseGet(() -> userRepository.findByUsername(loginName).orElse(null));
        if (manager == null) return "redirect:/login";
        if (!hasManagerAccess(manager)) return "redirect:/attendance/manager/attendance-confirms";   // 🔒

        String      approverNo = manager.getEmployeeNo();
        YearMonth   ym         = resolveMonth(month);   // 🔒 tháng đã khóa bị ép về tháng hiện tại
        LocalDate[] range      = toDateRange(ym);

        attendanceService.getManagerPendingRequests(loginName, range[0], range[1]).stream()
                .filter(c -> !c.getEmployeeNo().equalsIgnoreCase(approverNo))
                .forEach(c -> attendanceService.approveConfirm(c.getId(), approverNo));

        return "redirect:/attendance/manager/attendance-confirms?month=" + ym;
    }

    @GetMapping("/manager/reject-all")
    public String rejectAll(@RequestParam(required = false) String month,
                            Principal principal) {
        String loginName = principal.getName();
        User   manager   = userRepository.findByEmployeeNo(loginName)
                .orElseGet(() -> userRepository.findByUsername(loginName).orElse(null));
        if (manager == null) return "redirect:/login";
        if (!hasManagerAccess(manager)) return "redirect:/attendance/manager/attendance-confirms";   // 🔒

        String      approverNo = manager.getEmployeeNo();
        YearMonth   ym         = resolveMonth(month);   // 🔒 tháng đã khóa bị ép về tháng hiện tại
        LocalDate[] range      = toDateRange(ym);

        attendanceService.getManagerPendingRequests(loginName, range[0], range[1]).stream()
                .filter(c -> !c.getEmployeeNo().equalsIgnoreCase(approverNo))
                .forEach(c -> attendanceService.rejectConfirm(c.getId(), approverNo));

        return "redirect:/attendance/manager/attendance-confirms?month=" + ym;
    }

    @PostMapping("/manager/bulk-action")
    public String bulkAction(@RequestParam List<Long> ids,
                             @RequestParam String action,
                             @RequestParam(required = false) String month,
                             Principal principal,
                             RedirectAttributes ra) {
        String loginName = principal.getName();
        User   manager   = userRepository.findByEmployeeNo(loginName)
                .orElseGet(() -> userRepository.findByUsername(loginName).orElse(null));
        if (manager == null) return "redirect:/login";

        String    approverNo = manager.getEmployeeNo();
        YearMonth ym         = resolveMonth(month);

        // 🔒 Khóa sổ: bỏ qua các đơn thuộc ngày công đã khóa
        List<Long> openIds = ids.stream()
                .filter(id -> !isLocked(id, true))
                .filter(id -> attendanceService.canDecide(loginName, id))   // 🔒 chỉ đơn trong quyền + cùng đơn vị
                .toList();

        openIds.forEach(id -> {
            if ("APPROVE".equals(action)) attendanceService.approveConfirm(id, approverNo);
            else                          attendanceService.rejectConfirm(id, approverNo);
        });

        int skipped = ids.size() - openIds.size();
        if (skipped > 0) {
            ra.addFlashAttribute("error", "Bỏ qua " + skipped + " đơn đã khóa sổ hoặc không thuộc quyền duyệt của bạn.");
        }

        return "redirect:/attendance/manager/attendance-confirms?month=" + ym;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Kiểm tra quyền truy cập trang manager.
     */
    private boolean hasManagerAccess(User manager) {
        String dept     = manager.getDepartment()        != null ? manager.getDepartment().trim()                                : "";
        String role     = manager.getRole()              != null ? manager.getRole().trim()                                      : "";
        String username = manager.getUsername()          != null ? manager.getUsername().trim()                                  : "";
        String position = manager.getDepartmentManager() != null ? manager.getDepartmentManager().toString().trim().toLowerCase() : "";

        boolean isDeptManager    = position.contains("trưởng phòng") || position.equals("1") || position.equals("true");
        boolean isTopBossOrAdmin = "ADMIN".equalsIgnoreCase(role)
                || "ADMIN".equalsIgnoreCase(dept)
                || "admin".equalsIgnoreCase(username)
                || "99999".equals(manager.getEmployeeNo())
                || "gdkd".equalsIgnoreCase(username);

        // ĐƠN VỊ: Giám đốc / GĐ dịch vụ / Kế toán trưởng của từng đơn vị (thay các mã cố định 99999...)
        return isDeptManager || isTopBossOrAdmin || unitScope.isUnitApprover(manager) || unitScope.isGlobalAdmin(manager);
    }

    /** Ai thấy BẢNG CÔNG TỔNG (của đơn vị mình): trưởng phòng như bản cũ, thêm người duyệt cấp đơn vị và quản trị */
    private boolean seesWholeReport(User u) {
        return u != null && (Boolean.TRUE.equals(u.getDepartmentManager())
                || unitScope.isUnitApprover(u) || UnitScope.isAdmin(u) || unitScope.isGlobalAdmin(u)
                || NewsController.isHr(u));   // HCNS xem bảng công tổng của đơn vị mình
    }

    /** Chỉ chính chủ (hoặc quản trị cùng đơn vị / quản trị hệ thống) mới xem và gửi giải trình cho mã NV này */
    private boolean canActFor(Principal principal, String employeeNo) {
        if (principal == null || employeeNo == null) return false;
        User me = userRepository.findByEmployeeNo(principal.getName())
                .orElseGet(() -> userRepository.findByUsername(principal.getName()).orElse(null));
        if (me == null) return false;
        if (employeeNo.equalsIgnoreCase(me.getEmployeeNo()) || employeeNo.equalsIgnoreCase(me.getUsername())) return true;
        User target = userRepository.findByEmployeeNo(employeeNo).orElse(null);
        if (target == null) return false;
        if (unitScope.isGlobalAdmin(me)) return true;
        return UnitScope.isAdmin(me) && unitScope.sameUnit(me, target);
    }

    private String ownNo(Principal principal) {
        if (principal == null) return "";
        User u = userRepository.findByEmployeeNo(principal.getName())
                .orElseGet(() -> userRepository.findByUsername(principal.getName()).orElse(null));
        return u != null && u.getEmployeeNo() != null ? u.getEmployeeNo() : principal.getName();
    }

    /**
     * Parse và validate tháng từ request param.
     * Quy tắc khóa:
     *   - Chỉ cho xem tháng trước đến hết ngày APPROVE_CUTOFF_DAY của tháng mới.
     *   - Không cho xem xa hơn tháng trước hoặc tháng tương lai.
     */
    private YearMonth resolveMonth(String month) {
        YearMonth currentYm = YearMonth.now();
        YearMonth prevYm    = currentYm.minusMonths(1);
        boolean   canViewPrev = LocalDate.now().getDayOfMonth() <= myApproveCutoff();   // ngày dừng theo đơn vị

        if (month == null || month.isEmpty()) return currentYm;

        YearMonth requested;
        try {
            requested = YearMonth.parse(month);
        } catch (Exception e) {
            return currentYm;
        }

        if (requested.isAfter(currentYm))               return currentYm; // tương lai
        if (requested.isBefore(prevYm))                 return currentYm; // quá xa
        if (requested.equals(prevYm) && !canViewPrev)   return currentYm; // hết hạn chốt sổ

        return requested;
    }

    /**
     * Tính [from, to] cho tháng đã chọn.
     *   - Tháng hiện tại → to = hôm nay
     *   - Tháng quá khứ  → to = cuối tháng
     */
    private LocalDate[] toDateRange(YearMonth ym) {
        LocalDate from = ym.atDay(1);
        LocalDate to   = ym.equals(YearMonth.now()) ? LocalDate.now() : ym.atEndOfMonth();
        return new LocalDate[]{from, to};
    }
}