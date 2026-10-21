package com.example.hrm.controller;

import com.example.hrm.service.ErpBonusService;
import com.example.hrm.service.PayrollAuditService;
import com.example.hrm.service.PayrollCalculationService;
import com.example.hrm.service.PayrollDailyJob;
import com.example.hrm.service.PayrollExcelService;
import com.example.hrm.service.PayrollPeriodService;
import com.example.hrm.service.PayrollCalculationService.CalculationResult;
import com.example.hrm.service.SalaryFormulaService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.security.Principal;
import java.time.YearMonth;

/**
 * Tính lương tự động theo chấm công.
 *   GET  /payroll/auto-calculate?month=&year=&department=   xem trước (không lưu)
 *   POST /payroll/auto-calculate                              lưu phiếu lương
 *   GET  /payroll/auto-calculate/trace?userId=&month=&year=   giải trình 1 nhân viên
 *   POST /payroll/auto-calculate/refresh-current              cập nhật ngay phiếu tạm tính tháng này
 *   GET  /payroll/auto-calculate/export?month=&year=&department=   tải Excel bảng lương (nghìn đồng)
 *   POST /payroll/auto-calculate/lock | unlock                 chốt / mở lại kỳ lương
 */
@Controller
@RequestMapping("/payroll/auto-calculate")
public class PayrollCalculationController {

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.service.NotificationService notificationService;   // 🔔 chuông thông báo

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.repository.PayrollRepository payrollRepository;

    private final PayrollCalculationService calculationService;
    private final SalaryFormulaService formulaService;
    private final PayrollDailyJob dailyJob;
    private final PayrollPeriodService periodService;
    private final PayrollAuditService audit;
    private final PayrollExcelService excelService;
    private final ErpBonusService erpService;

    /** ĐƠN VỊ: tên công ty trên file Excel + chặn giải trình nhân viên đơn vị khác */
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.service.UnitScope unitScope;

    @Value("${payroll.company-name:}")
    private String companyName;

    public PayrollCalculationController(PayrollCalculationService calculationService,
                                        SalaryFormulaService formulaService,
                                        PayrollDailyJob dailyJob,
                                        PayrollPeriodService periodService,
                                        PayrollAuditService audit,
                                        PayrollExcelService excelService,
                                        ErpBonusService erpService) {
        this.calculationService = calculationService;
        this.formulaService = formulaService;
        this.dailyJob = dailyJob;
        this.periodService = periodService;
        this.audit = audit;
        this.excelService = excelService;
        this.erpService = erpService;
    }

    /** Tạo / cập nhật ngay phiếu lương TẠM TÍNH tháng này theo bảng công đến hôm nay */
    @PostMapping("/refresh-current")
    public String refreshCurrent(Principal principal, RedirectAttributes ra) {
        if (!dailyJob.isEnabled()) {
            ra.addFlashAttribute("message", "Chức năng tự lưu phiếu tạm tính đang TẮT (payroll.auto.enabled=false), không lưu gì.");
            return "redirect:/payroll/auto-calculate";
        }
        String msg = String.join("  ||  ", dailyJob.runNow());
        audit.log(PayrollWeb.username(principal), "PAYROLL_REFRESH", "Phiếu tạm tính", msg);
        ra.addFlashAttribute("message", "Phiếu tạm tính theo bảng công đến hôm nay: " + msg);
        YearMonth now = YearMonth.from(calculationService.today());
        ra.addAttribute("month", now.getMonthValue());
        ra.addAttribute("year", now.getYear());
        return "redirect:/payroll/auto-calculate";
    }

    @GetMapping
    public String page(@RequestParam(required = false) Integer month,
                       @RequestParam(required = false) Integer year,
                       @RequestParam(required = false) String department,
                       Model model) {

        // Mặc định mở tháng đang chạy (phiếu tạm tính theo bảng công đến hôm nay)
        YearMonth now = YearMonth.from(calculationService.today());

        model.addAttribute("month", month != null ? month : now.getMonthValue());
        model.addAttribute("year", year != null ? year : now.getYear());
        model.addAttribute("today", calculationService.today());
        model.addAttribute("autoEnabled", dailyJob.isEnabled());
        model.addAttribute("department", department);
        model.addAttribute("departments", formulaService.departments());
        model.addAttribute("erpEnabled", erpService.isEnabled());

        if (month != null && year != null) {
            YearMonth ym = YearMonth.of(year, month);
            model.addAttribute("period", periodService.get(ym));
            CalculationResult result = calculationService.calculateMonth(month, year, department, false, true);
            model.addAttribute("result", result);
        }

        return "salary/calc";
    }

    @PostMapping
    public String save(@RequestParam Integer month,
                       @RequestParam Integer year,
                       @RequestParam(required = false) String department,
                       @RequestParam(defaultValue = "false") boolean overwrite,
                       Principal principal,
                       RedirectAttributes ra) {

        back(ra, month, year, department);
        try {
            CalculationResult result = calculationService.calculateMonth(month, year, department, true, overwrite);
            audit.log(PayrollWeb.username(principal), overwrite ? "PAYROLL_SAVE_OVERWRITE" : "PAYROLL_SAVE",
                    "Bảng lương " + month + "/" + year + (department == null || department.isBlank() ? "" : " - " + department),
                    result.summary());
            ra.addFlashAttribute("message", result.summary());
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/payroll/auto-calculate";
    }

    /** Chốt kỳ: không sửa khoản tháng, không lưu / tính lại phiếu của tháng */
    @PostMapping("/lock")
    public String lock(@RequestParam Integer month,
                       @RequestParam Integer year,
                       @RequestParam(required = false) String department,
                       @RequestParam(required = false) String note,
                       Principal principal,
                       RedirectAttributes ra) {
        back(ra, month, year, department);
        try {
            periodService.lock(YearMonth.of(year, month), PayrollWeb.username(principal), note);
            // 🔔 nhân viên có phiếu tháng này (trong đơn vị đang chốt): lương đã chốt, xem phiếu
            int n = 0;
            try {
                for (com.example.hrm.entity.Payroll p : payrollRepository.findByMonthAndYear(month, year)) {
                    if (p.getEmployee() != null && unitScope.inScope(p.getEmployee())
                            && notificationService.send(p.getEmployee().getId(), "PAYROLL",
                            "Lương tháng " + month + "/" + year + " đã chốt",
                            "Phiếu lương chính thức đã có, bấm để xem chi tiết.",
                            "/payroll/detail/" + p.getId(), "PAY:" + year + "-" + month)) {
                        n++;
                    }
                }
            } catch (RuntimeException ignore) {
                // thông báo lỗi không làm hỏng việc chốt kỳ
            }
            ra.addFlashAttribute("message", "Đã CHỐT kỳ lương " + month + "/" + year + (n > 0 ? ", đã báo " + n + " nhân viên" : ""));
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/payroll/auto-calculate";
    }

    @PostMapping("/unlock")
    public String unlock(@RequestParam Integer month,
                         @RequestParam Integer year,
                         @RequestParam(required = false) String department,
                         @RequestParam(required = false) String reason,
                         Principal principal,
                         RedirectAttributes ra) {
        back(ra, month, year, department);
        if (reason == null || reason.isBlank()) {
            ra.addFlashAttribute("flashError", "Mở lại kỳ đã chốt cần ghi lý do.");
            return "redirect:/payroll/auto-calculate";
        }
        try {
            periodService.unlock(YearMonth.of(year, month), PayrollWeb.username(principal), reason.trim());
            ra.addFlashAttribute("message", "Đã MỞ LẠI kỳ lương " + month + "/" + year + " (lý do: " + reason.trim() + ")");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/payroll/auto-calculate";
    }

    /** Tải Excel bảng lương đúng như bảng xem trước (không lưu gì) */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam Integer month,
                                         @RequestParam Integer year,
                                         @RequestParam(required = false) String department,
                                         Principal principal) throws IOException {
        CalculationResult result = calculationService.calculateMonth(month, year, department, false, true);
        audit.log(PayrollWeb.username(principal), "PAYROLL_EXPORT",
                "Bảng lương " + month + "/" + year + (department == null || department.isBlank() ? "" : " - " + department),
                result.getRows().size() + " dòng");
        String name = "bang-luong-" + year + "-" + String.format("%02d", month)
                + (department == null || department.isBlank() ? "" : "-" + department.trim().replaceAll("[^\\p{L}\\p{N}]+", "-"))
                + ".xlsx";
        com.example.hrm.entity.CompanyUnit unit = unitScope.scopeUnit();
        return PayrollWeb.xlsx(excelService.payroll(result, unit != null ? unit.getName() : companyName), name);
    }

    private boolean userInScope(Long userId) {
        com.example.hrm.entity.User u = calculationService.userById(userId);
        return u != null && unitScope.inScope(u);
    }

    private static void back(RedirectAttributes ra, Integer month, Integer year, String department) {
        ra.addAttribute("month", month);
        ra.addAttribute("year", year);
        if (department != null && !department.isBlank()) {
            ra.addAttribute("department", department);
        }
    }

    @GetMapping("/trace")
    public String trace(@RequestParam Long userId,
                        @RequestParam Integer month,
                        @RequestParam Integer year,
                        Model model) {

        if (!userInScope(userId)) {
            return "redirect:/payroll/auto-calculate";   // 🔒 ĐƠN VỊ
        }
        model.addAttribute("calc", calculationService.trace(userId, month, year));
        model.addAttribute("month", month);
        model.addAttribute("year", year);
        return "salary/trace";
    }
}