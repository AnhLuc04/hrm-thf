package com.example.hrm.controller;

import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.entity.User;
import com.example.hrm.repository.CompanyUnitRepository;
import com.example.hrm.service.PayrollCalculationService;
import com.example.hrm.service.UnitScope;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * LỊCH LÀM VIỆC & GIỜ CHẤM CÔNG của đơn vị — HCNS tự cấu hình, không cần quản trị hệ thống.
 *   - Ngày làm trong tuần: cả ngày / buổi sáng / nghỉ (VD T7 làm sáng, CN nghỉ)
 *   - Giờ: báo đi muộn sau, tính vi phạm sau, mốc chia ca sáng/chiều, báo về sớm trước
 *   - Hạn gửi xác nhận công (ngày)
 * Quyền sửa: HCNS / Hành chính, role ADMIN của đơn vị (chỉ đơn vị mình), quản trị hệ thống (đơn vị đang xem).
 * Giám đốc đơn vị: chỉ xem.
 * Áp dụng ngay cho trang xác nhận công, bảng công tổng và phiếu lương CHƯA CHỐT kỳ.
 */
@Controller
public class WorkScheduleController {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final UnitScope unitScope;

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.service.UnitSyncRunner syncRunner;   // kéo công nền
    private final CompanyUnitRepository unitRepository;

    public WorkScheduleController(UnitScope unitScope, CompanyUnitRepository unitRepository) {
        this.unitScope = unitScope;
        this.unitRepository = unitRepository;
    }

    boolean canEdit(User u) {
        return u != null && (unitScope.isGlobalAdmin(u) || UnitScope.isAdmin(u) || NewsController.isHr(u));
    }

    boolean canView(User u) {
        return canEdit(u) || (u != null && unitScope.isDirector(u));
    }

    /** Đơn vị được làm việc: quản trị hệ thống = đơn vị đang xem (null = chưa chọn); người khác = đơn vị mình */
    CompanyUnit target(User me) {
        if (unitScope.isGlobalAdmin(me)) {
            Long id = unitScope.scopeUnitId();
            return id == null ? null : unitRepository.findById(id).orElse(null);
        }
        CompanyUnit u = unitScope.unitOf(me);
        return u == null ? null : unitRepository.findById(u.getId()).orElse(null);   // bản mới nhất trong DB
    }

    @GetMapping("/attendance/schedule")
    public String page(Model model) {
        User me = unitScope.currentUser();
        if (me == null) {
            return "redirect:/login";
        }
        if (!canView(me)) {
            return "redirect:/attendance/confirm/" + me.getEmployeeNo();
        }
        CompanyUnit unit = target(me);
        model.addAttribute("unit", unit);
        model.addAttribute("nextSync", com.example.hrm.service.UnitSyncRunner.nextTime(unit));
        model.addAttribute("syncing", unit != null && syncRunner.isRunning(unit.getId()));
        model.addAttribute("canEdit", canEdit(me));
        model.addAttribute("isGlobal", unitScope.isGlobalAdmin(me));
        if (unit != null) {
            model.addAttribute("months", preview(unit));
        } else {
            model.addAttribute("units", unitRepository.findAllByOrderByCodeAsc());
        }
        return "attendance-schedule";
    }

    @PostMapping("/attendance/schedule")
    public String save(@RequestParam Map<String, String> form, RedirectAttributes ra) {
        User me = unitScope.currentUser();
        if (!canEdit(me)) {
            ra.addFlashAttribute("error", "Chỉ HCNS và quản trị được sửa lịch làm việc");
            return "redirect:/attendance/schedule";
        }
        CompanyUnit unit = target(me);
        Long postedId = CompanyUnitController.parseLong(form.get("unitId"));
        if (unit == null || !Objects.equals(unit.getId(), postedId)) {
            // 🔒 không sửa được đơn vị khác (form cũ / sửa tay unitId)
            ra.addFlashAttribute("error", "Đơn vị không hợp lệ, tải lại trang rồi thử lại");
            return "redirect:/attendance/schedule";
        }

        List<String> errors = new ArrayList<>();
        String wd = CompanyUnitController.readWorkDays(form);
        if (wd == null || !CompanyUnit.validWorkDays(wd)) {
            errors.add("Chọn đủ 7 ngày và phải có ít nhất 1 ngày đi làm");
        }
        LocalTime late = time(form.get("lateAfter"), "Báo đi muộn sau", errors);
        LocalTime viol = time(form.get("violationAfter"), "Tính vi phạm sau", errors);
        LocalTime noon = time(form.get("noonSplit"), "Mốc chia ca", errors);
        LocalTime early = time(form.get("earlyBefore"), "Báo về sớm trước", errors);
        if (late != null && viol != null && viol.isBefore(late)) {
            errors.add("Giờ tính vi phạm phải bằng hoặc sau giờ báo đi muộn");
        }
        if (late != null && noon != null && !late.isBefore(noon)) {
            errors.add("Giờ báo đi muộn phải trước mốc chia ca sáng / chiều");
        }
        if (noon != null && early != null && !noon.isBefore(early)) {
            errors.add("Giờ báo về sớm phải sau mốc chia ca sáng / chiều");
        }
        Integer confirmDays = null;
        String cd = form.get("confirmDays") == null ? "" : form.get("confirmDays").trim();
        if (!cd.isEmpty()) {
            Long n = CompanyUnitController.parseLong(cd);
            if (n == null || n < 0 || n > 31) {
                errors.add("Hạn xác nhận công từ 0 đến 31 ngày (0 = không giới hạn)");
            } else {
                confirmDays = n.intValue();
            }
        }
        // NGÀY DỪNG + NGÀY CỘNG PHÉP (form cũ không có ô = giữ nguyên)
        Integer confirmCutoff = day(form, "confirmCutoffDay", 0, "Ngày dừng xác nhận công", errors, unit.getConfirmCutoffDay());
        Integer reportCutoff = day(form, "reportCutoffDay", 0, "Ngày dừng duyệt & chốt bảng công", errors, unit.getReportCutoffDay());
        Integer accrualDay = day(form, "leaveAccrualDay", 1, "Ngày cộng phép năm", errors, unit.getLeaveAccrualDay());
        if (!errors.isEmpty()) {
            ra.addFlashAttribute("error", String.join(". ", errors));
            return "redirect:/attendance/schedule";
        }

        // LỊCH KÉO CÔNG (cùng cách đọc với trang quản trị)
        List<String> syncErr = new ArrayList<>();
        if (unitScope.isGlobalAdmin(me)) {   // lịch kéo công: chỉ quản trị hệ thống (form HCNS không còn ô này)
            CompanyUnitController.applySyncForm(form, unit, syncErr);
        }
        if (!syncErr.isEmpty()) {
            ra.addFlashAttribute("error", String.join(". ", syncErr));
            return "redirect:/attendance/schedule";
        }

        String before = describe(unit);
        unit.setConfirmCutoffDay(confirmCutoff);
        unit.setReportCutoffDay(reportCutoff);
        unit.setLeaveAccrualDay(accrualDay);
        unit.setWorkDays(wd);
        unit.setLateAfter(late);
        unit.setViolationAfter(viol);
        unit.setNoonSplit(noon);
        unit.setEarlyBefore(early);
        unit.setConfirmDays(confirmDays);
        unit.setScheduleUpdatedBy(me.getFullName() != null ? me.getFullName() : me.getUsername());
        unit.setScheduleUpdatedAt(LocalDateTime.now(VN));
        unitRepository.save(unit);
        String after = describe(unit);
        ra.addFlashAttribute("message", before.equals(after)
                ? "Không có gì thay đổi"
                : "Đã lưu lịch làm việc " + unit.getCode() + ": " + after);
        return "redirect:/attendance/schedule";
    }

    // =====================================================================

    /** Công chuẩn tháng này + tháng sau theo lịch đang lưu */
    private Map<String, Number> preview(CompanyUnit unit) {
        Map<String, Number> m = new LinkedHashMap<>();
        YearMonth now = YearMonth.from(LocalDate.now(VN));
        for (YearMonth ym : List.of(now, now.plusMonths(1))) {
            m.put(ym.getMonthValue() + "/" + ym.getYear(), PayrollCalculationService.workingDaysNumber(ym, unit));
        }
        return m;
    }

    private static String describe(CompanyUnit u) {
        return u.getWorkDaysLabel() + " · muộn sau " + u.lateAfterOrDefault() + ", vi phạm sau " + u.violationAfterOrDefault()
                + ", chia ca " + u.noonOrDefault() + ", về sớm trước " + u.earlyBeforeOrDefault()
                + (u.confirmDaysOrDefault() == 0 ? ", không giới hạn hạn xác nhận" : ", hạn xác nhận " + u.confirmDaysOrDefault() + " ngày")
                + ", dừng xác nhận ngày " + u.confirmCutoffDayOrDefault() + ", dừng duyệt ngày " + u.reportCutoffDayOrDefault()
                + ", cộng phép ngày " + u.leaveAccrualDayOrDefault()
                + ", kéo công: " + u.getSyncScheduleLabel();
    }

    /** HCNS kéo lại công đơn vị mình theo khoảng ngày */
    @PostMapping("/attendance/schedule/sync-range")
    public String syncRange(@RequestParam Map<String, String> form, RedirectAttributes ra) {
        User me = unitScope.currentUser();
        if (me == null || !unitScope.isGlobalAdmin(me)) {   // kéo lại công: việc của quản trị hệ thống
            return "redirect:/attendance/schedule";
        }
        CompanyUnit unit = target(me);
        Long posted = CompanyUnitController.parseLong(form.get("unitId"));
        if (unit == null || (posted != null && !posted.equals(unit.getId()))) {
            ra.addFlashAttribute("error", "Chỉ kéo được công của đơn vị mình");
            return "redirect:/attendance/schedule";
        }
        String err = CompanyUnitController.rangeError(unit, form.get("from"), form.get("to"));
        if (err != null) {
            ra.addFlashAttribute("error", err);
        } else if (!syncRunner.startRange(unit.getId(), java.time.LocalDate.parse(form.get("from")), java.time.LocalDate.parse(form.get("to")))) {
            ra.addFlashAttribute("error", "Máy chấm công đang kéo dữ liệu, chờ vài phút rồi bấm lại");
        } else {
            ra.addFlashAttribute("message", "Đang kéo lại công từ " + form.get("from") + " đến " + form.get("to") + ", tải lại trang sau ít phút");
        }
        return "redirect:/attendance/schedule";
    }

    /** Ô ngày trong tháng (min..28); trống / không có ô = giữ giá trị cũ */
    private static Integer day(Map<String, String> form, String key, int min, String label, List<String> errors, Integer keep) {
        if (!form.containsKey(key)) {
            return keep;
        }
        String v = form.get(key) == null ? "" : form.get(key).trim();
        if (v.isEmpty()) {
            return null;
        }
        Long n = CompanyUnitController.parseLong(v);
        if (n == null || n < min || n > 28) {
            errors.add(label + " phải từ " + min + " đến 28");
            return keep;
        }
        return n.intValue();
    }

    private static LocalTime time(String raw, String label, List<String> errors) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) {
            errors.add("Thiếu giờ \"" + label + "\"");
            return null;
        }
        try {
            return LocalTime.parse(v.length() > 5 ? v.substring(0, 5) : v);
        } catch (DateTimeParseException e) {
            errors.add("Giờ \"" + label + "\" không hợp lệ");
            return null;
        }
    }
}
