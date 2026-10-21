package com.example.hrm.controller;

import com.example.hrm.dto.AttendanceAlertDTO;
import com.example.hrm.entity.AttendanceConfirmation;
import com.example.hrm.entity.ConfirmExtension;
import com.example.hrm.entity.User;
import com.example.hrm.repository.AttendanceConfirmationRepository;
import com.example.hrm.repository.ConfirmExtensionRepository;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.service.AttendanceService;
import com.example.hrm.service.ConfirmDeadlineService;
import com.example.hrm.service.UnitScope;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HCNS theo dõi HẠN XÁC NHẬN CÔNG của đơn vị mình:
 *   - ai có ngày lỗi đã QUÁ HẠN (bị khóa), ai SẮP HẾT HẠN (còn ≤ 2 ngày) để nhắc
 *   - MỞ LẠI cho 1 nhân viên (ốm, công tác...): các ngày lỗi trong khoảng được gửi đến hết ngày chọn
 * Quyền: HCNS, quản trị, Giám đốc của đơn vị; chỉ thấy / mở cho nhân viên đơn vị mình.
 */
@Controller
public class ConfirmDeadlineController {

    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd/MM");
    private static final int DUE_SOON_DAYS = 2;

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.service.NotificationService notificationService;   // 🔔 chuông thông báo

    private final UnitScope unitScope;
    private final AttendanceService attendanceService;
    private final ConfirmDeadlineService deadlineService;
    private final ConfirmExtensionRepository extensionRepository;
    private final AttendanceConfirmationRepository confirmationRepository;
    private final UserRepository userRepository;

    public ConfirmDeadlineController(UnitScope unitScope, AttendanceService attendanceService,
                                     ConfirmDeadlineService deadlineService, ConfirmExtensionRepository extensionRepository,
                                     AttendanceConfirmationRepository confirmationRepository, UserRepository userRepository) {
        this.unitScope = unitScope;
        this.attendanceService = attendanceService;
        this.deadlineService = deadlineService;
        this.extensionRepository = extensionRepository;
        this.confirmationRepository = confirmationRepository;
        this.userRepository = userRepository;
    }

    boolean canManage(User u) {
        return u != null && (unitScope.isGlobalAdmin(u) || UnitScope.isAdmin(u) || NewsController.isHr(u) || unitScope.isDirector(u));
    }

    /** 1 dòng trên trang: 1 nhân viên có ngày bị khóa hoặc sắp hết hạn (lớp thường để Thymeleaf đọc getter) */
    public static final class Row {
        private final User user;
        private final List<LocalDate> locked;
        private final List<LocalDate> dueSoon;
        private final LocalDate nearestDeadline;

        Row(User user, List<LocalDate> locked, List<LocalDate> dueSoon, LocalDate nearestDeadline) {
            this.user = user;
            this.locked = locked;
            this.dueSoon = dueSoon;
            this.nearestDeadline = nearestDeadline;
        }

        public User getUser() { return user; }
        public List<LocalDate> getLocked() { return locked; }
        public List<LocalDate> getDueSoon() { return dueSoon; }
        public LocalDate getNearestDeadline() { return nearestDeadline; }
        public LocalDate getFirstLocked() { return locked.isEmpty() ? null : locked.get(0); }
        public LocalDate getLastLocked() { return locked.isEmpty() ? null : locked.get(locked.size() - 1); }
    }

    @GetMapping("/attendance/deadline")
    public String page(@RequestParam(required = false) String month, Model model) {
        User me = unitScope.currentUser();
        if (me == null) {
            return "redirect:/login";
        }
        if (!canManage(me)) {
            return "redirect:/attendance/confirm/" + me.getEmployeeNo();
        }
        LocalDate today = deadlineService.today();
        YearMonth ym;
        try {
            ym = month == null || month.isBlank() ? YearMonth.from(today) : YearMonth.parse(month);
        } catch (RuntimeException e) {
            ym = YearMonth.from(today);
        }
        if (ym.isAfter(YearMonth.from(today))) {
            ym = YearMonth.from(today);
        }
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth().isAfter(today) ? today : ym.atEndOfMonth();

        List<User> staff = unitScope.users().stream()
                .filter(u -> u.getEmployeeNo() != null && !u.getEmployeeNo().isBlank())
                .sorted(Comparator.comparing(u -> u.getEmployeeNo()))
                .toList();

        // ca đã gửi đơn (chờ duyệt / trả lại / từ chối) không còn là "chưa xác nhận"
        Set<String> submitted = new HashSet<>();
        for (String st : List.of("PENDING", "RETURNED", "REJECTED")) {
            for (AttendanceConfirmation c : confirmationRepository.findByAttendanceDateBetweenAndApprovalStatus(from, to, st)) {
                submitted.add(c.getEmployeeNo() + "|" + c.getAttendanceDate() + "|" + c.getSessionType());
            }
        }

        List<Row> rows = new ArrayList<>();
        int lockedTotal = 0;
        int dueTotal = 0;
        for (User u : staff) {
            List<AttendanceAlertDTO> issues;
            try {
                issues = attendanceService.getAttendanceIssues(u.getEmployeeNo(), from, to);
            } catch (RuntimeException e) {
                continue;   // 1 người lỗi dữ liệu không làm hỏng cả trang
            }
            List<LocalDate> locked = new ArrayList<>();
            List<LocalDate> due = new ArrayList<>();
            LocalDate nearest = null;
            for (AttendanceAlertDTO a : issues) {
                if (submitted.contains(u.getEmployeeNo() + "|" + a.getDate() + "|" + a.getSessionType())) {
                    continue;
                }
                if (a.isLocked()) {
                    locked.add(a.getDate());
                } else if (a.getDaysLeft() != null && a.getDaysLeft() <= DUE_SOON_DAYS) {
                    due.add(a.getDate());
                    if (nearest == null || a.getDeadline().isBefore(nearest)) {
                        nearest = a.getDeadline();
                    }
                }
            }
            if (!locked.isEmpty() || !due.isEmpty()) {
                locked.sort(null);
                due.sort(null);
                lockedTotal += locked.size();
                dueTotal += due.size();
                rows.add(new Row(u, locked, due, nearest));
            }
        }

        Set<String> nos = staff.stream().map(User::getEmployeeNo).collect(Collectors.toSet());
        Map<String, User> byNo = new LinkedHashMap<>();
        staff.forEach(u -> byNo.put(u.getEmployeeNo(), u));
        List<ConfirmExtension> active = extensionRepository.findByOpenUntilGreaterThanEqualOrderByOpenUntilAsc(today).stream()
                .filter(e -> nos.contains(e.getEmployeeNo()))
                .toList();

        model.addAttribute("rows", rows);
        model.addAttribute("lockedTotal", lockedTotal);
        model.addAttribute("dueTotal", dueTotal);
        model.addAttribute("extensions", active);
        model.addAttribute("byNo", byNo);
        model.addAttribute("staff", staff);
        model.addAttribute("month", ym.toString());
        model.addAttribute("monthLabel", ym.getMonthValue() + "/" + ym.getYear());
        model.addAttribute("today", today);
        model.addAttribute("defaultUntil", today.plusDays(3));
        model.addAttribute("maxUntil", today.plusDays(31));
        model.addAttribute("confirmDays", deadlineService.days(unitScope.isGlobalAdmin(me) ? unitScope.scopeUnit() : unitScope.unitOf(me)));
        model.addAttribute("scopeLabel", unitScope.scopeUnit() != null ? unitScope.scopeUnit().getLabel() : "Tất cả đơn vị");
        return "attendance-deadline";
    }

    @PostMapping("/attendance/deadline/open")
    public String open(@RequestParam String employeeNo,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate openUntil,
                       @RequestParam(required = false) String reason,
                       @RequestParam(required = false) String month,
                       RedirectAttributes ra) {
        String back = "redirect:/attendance/deadline" + (month != null && month.matches("\\d{4}-\\d{2}") ? "?month=" + month : "");
        User me = unitScope.currentUser();
        User emp = userRepository.findByEmployeeNo(employeeNo).orElse(null);
        if (!canManage(me) || emp == null || !unitScope.inScope(emp)) {
            ra.addFlashAttribute("error", "Bạn chỉ mở lại được cho nhân viên đơn vị mình");
            return back;
        }
        try {
            deadlineService.grant(me, emp, fromDate, toDate, openUntil, reason);
            notificationService.send(emp.getId(), "ATTENDANCE",   // 🔔
                    "HCNS đã mở lại giải trình công",
                    "Ngày lỗi " + fromDate.format(DM) + " – " + toDate.format(DM) + " gửi được đến hết " + openUntil.format(DM)
                            + (reason == null || reason.isBlank() ? "" : ". Lý do: " + reason.trim()),
                    "/attendance/confirm/" + emp.getEmployeeNo(), null);
            ra.addFlashAttribute("message", "Đã mở lại cho " + emp.getFullName() + " (" + emp.getEmployeeNo() + "): ngày lỗi "
                    + fromDate.format(DM) + " – " + toDate.format(DM) + " gửi được đến hết " + openUntil.format(DM));
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return back;
    }

    @PostMapping("/attendance/deadline/{id}/revoke")
    public String revoke(@PathVariable Long id, @RequestParam(required = false) String month, RedirectAttributes ra) {
        String back = "redirect:/attendance/deadline" + (month != null && month.matches("\\d{4}-\\d{2}") ? "?month=" + month : "");
        User me = unitScope.currentUser();
        extensionRepository.findById(id).ifPresent(e -> {
            User emp = userRepository.findByEmployeeNo(e.getEmployeeNo()).orElse(null);
            if (canManage(me) && emp != null && unitScope.inScope(emp)) {
                extensionRepository.delete(e);
                ra.addFlashAttribute("message", "Đã huỷ mở lại của " + emp.getFullName());
            } else {
                ra.addFlashAttribute("error", "Bạn không huỷ được mục này");
            }
        });
        return back;
    }
}
