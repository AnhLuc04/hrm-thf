package com.example.hrm.controller;


import com.example.hrm.entity.Payroll;
import com.example.hrm.entity.User;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.service.PayrollAutoService;
import com.example.hrm.service.PayrollCalculationService;
import com.example.hrm.service.PayrollService;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

import java.security.Principal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


@Controller
@RequestMapping("/payroll")
@RequiredArgsConstructor
public class PayrollController {

    private final PayrollService payrollService;
    private final UserRepository userRepository;
    private final com.example.hrm.service.UnitScope unitScope;   // ĐƠN VỊ: Kế toán chỉ xem phiếu đơn vị mình
    private final PayrollAutoService payrollAutoService;   // phiếu lương tạm tính theo bảng công
    private final PayrollCalculationService payrollCalculationService;   // phiếu lương dự kiến

    /** Ngày chi lương tháng trước (mặc định 15): từ ngày 1 đến ngày này nhân viên thấy 2 phiếu dự kiến */
    @Value("${payroll.pay-day:15}")
    private int payDay = 15;


    // ==============================
    // PHÂN QUYỀN
    // ==============================

    /** Người đang đăng nhập (principal có thể là username hoặc mã nhân viên) */
    private User currentUser(Principal principal) {
        if (principal == null) return null;
        String login = principal.getName();
        return userRepository.findByUsername(login)
                .orElseGet(() -> userRepository.findByEmployeeNo(login).orElse(null));
    }

    /** Phòng Kế Toán — chỉ phòng này được nhập bảng lương */
    private static boolean isAccountant(User u) {
        if (u == null || u.getDepartment() == null) return false;
        String d = u.getDepartment().trim().toLowerCase();
        return d.equals("kế toán") || d.contains("kế toán") || d.equals("ke toan");
    }

    private static boolean isAdmin(User u) {
        if (u == null) return false;
        return "ADMIN".equalsIgnoreCase(trim(u.getRole()))
                || "ADMIN".equalsIgnoreCase(trim(u.getDepartment()))
                || "admin".equalsIgnoreCase(trim(u.getUsername()));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private void requireAccountant(Principal principal) {
        if (!isAccountant(currentUser(principal))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Chỉ phòng Kế Toán được nhập bảng lương.");
        }
    }


    // ==============================
    // TRANG IMPORT — chỉ Kế Toán
    // ==============================

    @GetMapping("/import")
    public String importPage(Principal principal) {
        if (!isAccountant(currentUser(principal))) {
            return "redirect:/?error=no-permission";
        }
        return "payroll-import";
    }


    // ==============================
    // IMPORT EXCEL — chỉ Kế Toán
    // ==============================

    @PostMapping("/import")
    @ResponseBody
    public String importPayroll(
            @RequestParam("file") MultipartFile file,
            @RequestParam("month") Integer month,
            @RequestParam("year") Integer year,
            Principal principal
    ) throws Exception {

        requireAccountant(principal);

        String result = payrollService.importExcel(file, month, year);

        // Bọc trong <pre> để giữ nguyên xuống dòng; escape để nội dung file Excel không chạy được như HTML
        return "<pre style=\"font-family:monospace;font-size:14px;white-space:pre-wrap;\">"
                + HtmlUtils.htmlEscape(result == null ? "" : result)
                + "</pre>";
    }


    // ==============================
    // LỊCH SỬ LƯƠNG — chính chủ, Kế Toán hoặc Admin
    // ==============================

    @GetMapping("/history/{salaryCode}")
    public String history(@PathVariable String salaryCode,
                          Principal principal,
                          Model model) {

        User me = currentUser(principal);
        if (me == null) return "redirect:/login";

        boolean own = me.getSalaryCode() != null && me.getSalaryCode().equalsIgnoreCase(salaryCode);
        if (!own && !isAccountant(me) && !isAdmin(me)) {
            return "redirect:/payroll/my";
        }

        List<Payroll> list = payrollService.getHistory(salaryCode);
        model.addAttribute("payrolls", list);
        model.addAttribute("salaryCode", salaryCode);
        return "payroll-history";
    }


    // ==============================
    // PHIẾU LƯƠNG CỦA TÔI
    // ==============================

    @GetMapping("/my")
    public String myPayroll(Principal principal, Model model) {

        User user = currentUser(principal);
        if (user == null) {
            return "redirect:/login";
        }

        List<Payroll> payrolls = payrollService.getMyPayroll(user.getEmployeeNo());

        model.addAttribute("user", user);
        model.addAttribute("payrolls", payrolls);
        model.addAttribute("forecasts", forecasts(user, payrolls));

        return "payroll-my";
    }

    /**
     * Phiếu lương DỰ KIẾN (tính trực tiếp theo bảng công, KHÔNG lưu DB) của các tháng CHƯA có phiếu chính thức.
     *   - Luôn có tháng đang chạy.
     *   - Từ ngày 1 đến ngày chi lương (payroll.pay-day, mặc định 15) thêm tháng trước, vì lương tháng trước chưa chi.
     *     VD hôm nay 02/10: dự kiến tháng 9 (đủ công, chi ngày 15/10) + tháng 10 (tạm tính đến 02/10).
     * Lỗi / chưa cấu hình thì bỏ qua tháng đó, không làm hỏng trang.
     */
    private List<Map<String, Object>> forecasts(User user, List<Payroll> payrolls) {
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate today;
        try {
            today = payrollCalculationService.today();
        } catch (RuntimeException e) {
            return out;
        }
        YearMonth now = YearMonth.from(today);
        // ĐƠN VỊ: mỗi công ty một ngày chi lương (VD TH ngày 10, SS ngày 15)
        com.example.hrm.entity.CompanyUnit unit = unitScope.unitOf(user);
        int payDay = unit != null ? unit.payDayOr(this.payDay) : this.payDay;

        List<YearMonth> months = new ArrayList<>();
        if (today.getDayOfMonth() <= payDay) {
            months.add(now.minusMonths(1));
        }
        months.add(now);

        for (YearMonth ym : months) {
            boolean hasOfficial = payrolls.stream().anyMatch(p ->
                    Objects.equals(p.getMonth(), ym.getMonthValue()) && Objects.equals(p.getYear(), ym.getYear()));
            if (hasOfficial) {
                continue;
            }
            try {
                PayrollCalculationService.EmployeeCalc fc =
                        payrollCalculationService.trace(user.getId(), ym.getMonthValue(), ym.getYear());
                if (fc.getError() != null) {
                    continue;
                }
                YearMonth payMonth = ym.plusMonths(1);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("month", ym.getMonthValue());
                f.put("year", ym.getYear());
                f.put("payroll", fc.getPayroll());
                f.put("upTo", payrollCalculationService.previewMark(ym).getCalcUpTo());
                f.put("complete", ym.isBefore(now));          // tháng trước: đã đủ công, chờ chi
                f.put("payDate", payMonth.atDay(Math.min(Math.max(payDay, 1), payMonth.lengthOfMonth())));
                out.add(f);
            } catch (RuntimeException ignore) {
                // không để phần dự kiến làm hỏng trang phiếu lương
            }
        }
        return out;
    }


    // ==============================
    // CHI TIẾT PHIẾU LƯƠNG — chính chủ, Kế Toán hoặc Admin
    // ==============================

    @GetMapping("/detail/{id}")
    public String detail(@PathVariable Long id,
                         Principal principal,
                         Model model) {

        User me = currentUser(principal);
        if (me == null) return "redirect:/login";

        Payroll payroll = payrollService.findById(id);
        if (payroll == null) {
            return "redirect:/payroll/my";
        }

        User owner = payroll.getEmployee();
        boolean own = owner != null && (
                Objects.equals(owner.getId(), me.getId())
                        || (owner.getEmployeeNo() != null && owner.getEmployeeNo().equals(me.getEmployeeNo())));

        // Chặn xem phiếu lương người khác bằng cách đổi id trên đường dẫn
        if (!own && !isAccountant(me) && !isAdmin(me)) {
            return "redirect:/payroll/my";
        }
        // 🔒 ĐƠN VỊ: Kế toán / quản trị đơn vị này không xem phiếu đơn vị khác
        if (!own && owner != null && !unitScope.inScope(owner)) {
            return "redirect:/payroll/my";
        }

        model.addAttribute("payroll", payroll);
        model.addAttribute("user", owner);

        // "Công chuẩn = NTT - CN" trên thẻ lương
        if (payroll.getMonth() != null && payroll.getYear() != null) {
            model.addAttribute("dayInfo", Map.of("duCong",
                    PayrollCalculationService.workingDaysNumber(YearMonth.of(payroll.getYear(), payroll.getMonth()),
                            owner != null ? unitScope.unitOf(owner) : unitScope.legacyUnit())));   // theo lịch làm việc đơn vị
        }
        // phiếu do hệ thống tự tính theo bảng công (null = phiếu Kế toán import)
        model.addAttribute("autoInfo", payrollAutoService.info(payroll.getId()));

        return "payroll-detail";
    }
}