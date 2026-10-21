package com.example.hrm.controller;

import com.example.hrm.entity.MonthlyItemType;
import com.example.hrm.entity.PayrollAuditLog;
import com.example.hrm.entity.User;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.service.ErpBonusService;
import com.example.hrm.service.PayrollAuditService;
import com.example.hrm.service.PayrollCalculationService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Trang quản trị module lương:
 *   GET  /salary-erp?month=&year=          trạng thái kết nối SQL Server (Cyber ERP) + các câu SQL đang cấu hình
 *   POST /salary-erp/test                   chạy thử 1 câu SQL (không lưu gì), xem số dòng / khớp mã NV
 *   POST /salary-erp/clear-cache            xoá bộ nhớ đệm thưởng ERP
 *   GET  /salary-audit?from=&to=&action=&q= nhật ký thao tác
 */
@Controller
public class SalaryAdminController {

    private final ErpBonusService erpService;
    private final PayrollAuditService audit;
    private final PayrollCalculationService calculationService;
    private final UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.service.UnitScope unitScope;

    public SalaryAdminController(ErpBonusService erpService,
                                 PayrollAuditService audit,
                                 PayrollCalculationService calculationService,
                                 UserRepository userRepository) {
        this.erpService = erpService;
        this.audit = audit;
        this.calculationService = calculationService;
        this.userRepository = userRepository;
    }

    // =====================================================================
    // KẾT NỐI ERP
    // =====================================================================

    @GetMapping("/salary-erp")
    public String erp(@RequestParam(required = false) Integer month,
                      @RequestParam(required = false) Integer year,
                      Model model) {
        YearMonth ym = PayrollWeb.ym(month, year, calculationService.today());
        erpModel(model, ym);
        return "salary/erp";
    }

    @PostMapping("/salary-erp/test")
    public String erpTest(@RequestParam String type,
                          @RequestParam Integer month,
                          @RequestParam Integer year,
                          Principal principal,
                          Model model) {
        YearMonth ym = YearMonth.of(year, month);
        erpModel(model, ym);

        MonthlyItemType t;
        try {
            t = MonthlyItemType.valueOf(type);
        } catch (IllegalArgumentException e) {
            model.addAttribute("flashError", "Loại khoản không hợp lệ: " + type);
            return "salary/erp";
        }
        if (!erpService.isEnabled() || erpService.sqlFor(t) == null) {
            model.addAttribute("flashError", "Chưa bật kết nối hoặc loại '" + t.getLabel() + "' chưa có câu SQL.");
            return "salary/erp";
        }

        long start = System.currentTimeMillis();
        Map<String, Double> data = erpService.fetch(t, ym, true);
        long ms = System.currentTimeMillis() - start;
        String err = erpService.getLastError();

        Map<String, User> byCode = new HashMap<>();
        for (User u : unitScope.users()) {   // 🔒 ĐƠN VỊ: chỉ hiện tên nhân viên đơn vị mình
            byCode.putIfAbsent(ErpBonusService.normEmp(u.getEmployeeNo()), u);
            if (u.getSalaryCode() != null) {
                byCode.putIfAbsent(ErpBonusService.normEmp(u.getSalaryCode()), u);
            }
        }
        List<Map<String, Object>> sample = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        double total = 0;
        for (Map.Entry<String, Double> e : new TreeMap<>(data).entrySet()) {
            total += e.getValue();
            User u = byCode.get(e.getKey());
            if (u == null) {
                unmatched.add(e.getKey());
            }
            // 🔒 ĐƠN VỊ: Kế toán đơn vị chỉ xem số tiền của nhân viên đơn vị mình
            if (u == null && unitScope.scopeUnitId() != null) {
                continue;
            }
            if (sample.size() < 50) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("code", e.getKey());
                row.put("name", u == null ? null : u.getFullName());
                row.put("amount", e.getValue());
                sample.add(row);
            }
        }

        Map<String, Object> test = new LinkedHashMap<>();
        test.put("type", t);
        test.put("error", err != null && err.startsWith(t.name()) ? err : null);
        test.put("rows", data.size());
        test.put("total", total);
        test.put("ms", ms);
        test.put("sample", sample);
        test.put("unmatched", unmatched);
        model.addAttribute("test", test);

        audit.log(PayrollWeb.username(principal), "ERP_TEST", "ERP " + t.name() + " " + ym,
                data.size() + " dòng, " + unmatched.size() + " mã không khớp" + (test.get("error") == null ? "" : ", lỗi: " + err));
        return "salary/erp";
    }

    @PostMapping("/salary-erp/clear-cache")
    public String clearCache(@RequestParam(required = false) Integer month,
                             @RequestParam(required = false) Integer year,
                             RedirectAttributes ra) {
        erpService.clearCache();
        ra.addFlashAttribute("message", "Đã xoá bộ nhớ đệm, lần tính tiếp theo sẽ lấy lại số liệu từ ERP.");
        if (month != null && year != null) {
            ra.addAttribute("month", month);
            ra.addAttribute("year", year);
        }
        return "redirect:/salary-erp";
    }

    private void erpModel(Model model, YearMonth ym) {
        List<Map<String, Object>> types = new ArrayList<>();
        for (MonthlyItemType t : MonthlyItemType.values()) {
            if (!t.isErp()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", t);
            m.put("sql", erpService.sqlFor(t));
            m.put("active", erpService.erpTypes().contains(t));
            types.add(m);
        }
        model.addAttribute("erp", erpService);
        model.addAttribute("types", types);
        model.addAttribute("month", ym.getMonthValue());
        model.addAttribute("year", ym.getYear());
    }

    // =====================================================================
    // NHẬT KÝ
    // =====================================================================

    @GetMapping("/salary-audit")
    public String auditLog(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @RequestParam(required = false) String action,
                           @RequestParam(required = false) String q,
                           Model model) {
        LocalDate today = calculationService.today();
        LocalDate t = to != null ? to : today;
        LocalDate f = from != null ? from : t.minusDays(30);

        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<PayrollAuditLog> all = audit.between(f, t).stream()
                .filter(audit::visible)   // 🔒 ĐƠN VỊ
                .collect(Collectors.toList());
        List<PayrollAuditLog> logs = all.stream()
                .filter(l -> action == null || action.isBlank() || action.equals(l.getAction()))
                .filter(l -> query.isEmpty() || contains(l.getUsername(), query) || contains(l.getTarget(), query)
                        || contains(l.getDetail(), query))
                .collect(Collectors.toList());

        model.addAttribute("logs", logs);
        model.addAttribute("actions", all.stream().map(PayrollAuditLog::getAction).filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new)));
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        model.addAttribute("action", action);
        model.addAttribute("q", q);
        model.addAttribute("limited", all.size() >= 500);
        return "salary/audit";
    }

    private static boolean contains(String s, String q) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(q);
    }
}