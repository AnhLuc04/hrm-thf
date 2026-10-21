package com.example.hrm.controller;

import com.example.hrm.entity.MonthlyItemType;
import com.example.hrm.entity.SalaryConfig;
import com.example.hrm.entity.User;
import com.example.hrm.repository.SalaryConfigRepository;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.service.ErpBonusService;
import com.example.hrm.service.PayrollCalculationService;
import com.example.hrm.service.PayrollExcelService;
import com.example.hrm.service.PayrollMonthlyItemService;
import com.example.hrm.service.PayrollOvertimeService;
import com.example.hrm.service.PayrollPeriodService;
import com.example.hrm.service.SalaryFormulaService;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.security.Principal;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * KHOẢN PHÁT SINH THEO THÁNG: thưởng, phạt, tạm ứng, rủi ro - mỗi tháng 1 bảng riêng.
 *
 *   GET  /salary-monthly?month=&year=&department=     bảng nhập (sửa trực tiếp)
 *   POST /salary-monthly/save                          lưu các dòng đã sửa
 *   POST /salary-monthly/copy-prev                     chép các khoản chọn từ tháng trước (ô trống)
 *   POST /salary-monthly/from-config                   lấy mức cũ đang lưu trong Cấu hình lương NV (ô trống)
 *   GET  /salary-monthly/export                        tải file Excel (làm mẫu nhập)
 *   POST /salary-monthly/import                        nhập từ file Excel
 *
 * Số tiền theo ĐỒNG. Loại thưởng đang lấy từ ERP thì chỉ xem, không nhập.
 * Ô thưởng là SỐ NHẬP của NV (doanh số, số thưởng...); số thực nhận đi qua cách tính thưởng
 * của NV ở Cấu hình lương NV (× tỉ lệ / số cố định / công thức riêng) - hiện ngay dưới ô.
 */
@Controller
@RequestMapping("/salary-monthly")
public class SalaryMonthlyController {

    private final PayrollMonthlyItemService monthlyService;
    private final PayrollPeriodService periodService;
    private final ErpBonusService erpService;
    private final PayrollCalculationService calculationService;
    private final PayrollExcelService excelService;
    private final UserRepository userRepository;
    private final SalaryConfigRepository configRepository;
    private final SalaryFormulaService formulaService;
    private final PayrollOvertimeService overtimeService;

    /** ĐƠN VỊ: Kế toán chỉ nhập khoản tháng cho nhân viên đơn vị mình */
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.service.UnitScope unitScope;

    public SalaryMonthlyController(PayrollMonthlyItemService monthlyService,
                                   PayrollPeriodService periodService,
                                   ErpBonusService erpService,
                                   PayrollCalculationService calculationService,
                                   PayrollExcelService excelService,
                                   UserRepository userRepository,
                                   SalaryConfigRepository configRepository,
                                   SalaryFormulaService formulaService,
                                   PayrollOvertimeService overtimeService) {
        this.monthlyService = monthlyService;
        this.periodService = periodService;
        this.erpService = erpService;
        this.calculationService = calculationService;
        this.excelService = excelService;
        this.userRepository = userRepository;
        this.configRepository = configRepository;
        this.formulaService = formulaService;
        this.overtimeService = overtimeService;
    }

    // =====================================================================
    // BẢNG NHẬP
    // =====================================================================

    @GetMapping
    public String page(@RequestParam(required = false) Integer month,
                       @RequestParam(required = false) Integer year,
                       @RequestParam(required = false) String department,
                       Model model) {

        YearMonth ym = PayrollWeb.ym(month, year, calculationService.today());
        Set<MonthlyItemType> erpTypes = erpService.erpTypes();

        Map<Long, Map<MonthlyItemType, Double>> values = monthlyService.forMonth(ym);
        Map<String, Map<MonthlyItemType, Double>> erp = erpTypes.isEmpty() ? Map.of() : erpService.fetchMonth(ym);
        Map<Long, SalaryConfig> configs = configsByUser();
        Map<Long, List<PayrollOvertimeService.Line>> overtime = overtimeService.forMonth(ym);

        List<Row> rows = new ArrayList<>();
        for (User u : users(department)) {
            Map<MonthlyItemType, Double> erpRow = erp.get(ErpBonusService.normEmp(u.getEmployeeNo()));
            if (erpRow == null && u.getSalaryCode() != null) {
                erpRow = erp.get(ErpBonusService.normEmp(u.getSalaryCode()));
            }
            SalaryConfig cfg = configs.get(u.getId());
            rows.add(new Row(u, values.getOrDefault(u.getId(), Map.of()),
                    erpRow == null ? Map.of() : erpRow, legacy(cfg),
                    cfg == null ? null : cfg.getRuiRo(),
                    overtime.getOrDefault(u.getId(), List.of()),
                    cfg));
        }

        Map<MonthlyItemType, Double> totals = new EnumMap<>(MonthlyItemType.class);
        for (MonthlyItemType t : MonthlyItemType.values()) {
            totals.put(t, rows.stream().mapToDouble(r -> r.amount(t) == null ? 0 : r.amount(t)).sum());
        }

        model.addAttribute("month", ym.getMonthValue());
        model.addAttribute("year", ym.getYear());
        model.addAttribute("prev", ym.minusMonths(1));
        model.addAttribute("department", department);
        model.addAttribute("departments", formulaService.departments());
        model.addAttribute("types", MonthlyItemType.values());
        model.addAttribute("erpTypes", erpTypes);
        model.addAttribute("erpError", erpTypes.isEmpty() ? null : erpService.getLastError());
        model.addAttribute("rows", rows);
        model.addAttribute("totals", totals);
        model.addAttribute("otHours", rows.stream().mapToDouble(Row::getOtHours).sum());
        model.addAttribute("otConverted", rows.stream().mapToDouble(Row::getOtConverted).sum());
        model.addAttribute("locked", periodService.isLocked(ym));
        model.addAttribute("period", periodService.get(ym));
        model.addAttribute("hasLegacy", rows.stream().anyMatch(Row::isHasLegacy));
        return "salary/monthly";
    }

    // =====================================================================
    // LƯU
    // =====================================================================

    /** Tên ô: row_{userId}, v_{userId}_{LOẠI}, ot_{userId} (tăng ca). Chỉ các dòng đã sửa được gửi lên. */
    @PostMapping("/save")
    public String save(@RequestParam Map<String, String> form,
                       @RequestParam Integer month,
                       @RequestParam Integer year,
                       @RequestParam(required = false) String department,
                       Principal principal,
                       RedirectAttributes ra) {

        YearMonth ym = YearMonth.of(year, month);
        back(ra, ym, department);

        Set<MonthlyItemType> erpTypes = erpService.erpTypes();
        Map<Long, Map<MonthlyItemType, Double>> values = new LinkedHashMap<>();
        Map<Long, String> labels = new HashMap<>();
        Map<Long, List<PayrollOvertimeService.Line>> otValues = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (String key : form.keySet()) {
            if (!key.startsWith("row_")) {
                continue;
            }
            Long userId = SalaryFormulaController.parseLong(key.substring(4));
            if (userId == null || !inScope(userId)) {   // 🔒 ĐƠN VỊ: bỏ dòng của đơn vị khác
                continue;
            }
            String label = form.getOrDefault("label_" + userId, "#" + userId);
            labels.put(userId, label);
            Map<MonthlyItemType, Double> row = new EnumMap<>(MonthlyItemType.class);
            for (MonthlyItemType t : MonthlyItemType.values()) {
                String k = "v_" + userId + "_" + t.name();
                if (!form.containsKey(k) || erpTypes.contains(t)) {
                    continue;   // ô không có trên form / đang lấy từ ERP -> giữ nguyên
                }
                String raw = form.get(k);
                if (raw == null || raw.isBlank()) {
                    row.put(t, null);
                    continue;
                }
                Double v = SalaryConfigController.num(raw);
                if (v == null) {
                    errors.add(label + " - " + t.getLabel() + ": '" + raw + "' không phải số");
                } else {
                    row.put(t, v);
                }
            }
            values.put(userId, row);
            if (form.containsKey("ot_" + userId)) {
                try {
                    otValues.put(userId, PayrollOvertimeService.parse(form.get("ot_" + userId)));
                } catch (IllegalArgumentException e) {
                    errors.add(label + " - Tăng ca: " + e.getMessage());
                }
            }
        }

        if (!errors.isEmpty()) {
            ra.addFlashAttribute("flashError", "Chưa lưu. Sửa các ô sau rồi lưu lại: " + String.join("; ", errors));
            return "redirect:/salary-monthly";
        }

        try {
            int n = monthlyService.save(ym, values, labels, PayrollWeb.username(principal));
            int ot = overtimeService.save(ym, otValues, labels, PayrollWeb.username(principal));
            ra.addFlashAttribute("message", n == 0 && ot == 0 ? "Không có thay đổi nào"
                    : "Đã lưu tháng " + month + "/" + year + ": " + n + " ô khoản tháng"
                    + (ot > 0 ? ", tăng ca của " + ot + " người" : ""));
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/salary-monthly";
    }

    @PostMapping("/copy-prev")
    public String copyPrev(@RequestParam Integer month,
                           @RequestParam Integer year,
                           @RequestParam(required = false) String department,
                           @RequestParam(required = false) List<String> types,
                           Principal principal,
                           RedirectAttributes ra) {

        YearMonth ym = YearMonth.of(year, month);
        back(ra, ym, department);

        Set<MonthlyItemType> set = EnumSet.noneOf(MonthlyItemType.class);
        if (types != null) {
            for (String t : types) {
                try {
                    set.add(MonthlyItemType.valueOf(t));
                } catch (IllegalArgumentException ignored) {
                    // bỏ qua giá trị lạ
                }
            }
        }
        set.removeAll(erpService.erpTypes());
        if (set.isEmpty()) {
            ra.addFlashAttribute("flashError", "Chọn ít nhất 1 khoản cần chép từ tháng trước.");
            return "redirect:/salary-monthly";
        }
        try {
            int n = monthlyService.copyFromPrevious(ym, set, PayrollWeb.username(principal));
            ra.addFlashAttribute("message", "Đã chép " + n + " khoản từ tháng " + ym.minusMonths(1).getMonthValue()
                    + "/" + ym.minusMonths(1).getYear() + " (chỉ điền ô đang trống).");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/salary-monthly";
    }

    /** Chuyển các mức "hằng tháng" cũ đã lưu trong Cấu hình lương NV sang tháng này (chỉ ô trống) */
    @PostMapping("/from-config")
    public String fromConfig(@RequestParam Integer month,
                             @RequestParam Integer year,
                             @RequestParam(required = false) String department,
                             Principal principal,
                             RedirectAttributes ra) {

        YearMonth ym = YearMonth.of(year, month);
        back(ra, ym, department);

        Map<Long, Map<MonthlyItemType, Double>> current = monthlyService.forMonth(ym);
        Map<Long, SalaryConfig> configs = configsByUser();
        Set<MonthlyItemType> erpTypes = erpService.erpTypes();

        Map<Long, Map<MonthlyItemType, Double>> values = new LinkedHashMap<>();
        Map<Long, String> labels = new HashMap<>();
        for (User u : users(department)) {
            Map<MonthlyItemType, Double> old = legacy(configs.get(u.getId()));
            Map<MonthlyItemType, Double> cur = current.getOrDefault(u.getId(), Map.of());
            Map<MonthlyItemType, Double> row = new EnumMap<>(MonthlyItemType.class);
            old.forEach((t, v) -> {
                if (!cur.containsKey(t) && !erpTypes.contains(t)) {
                    row.put(t, v);
                }
            });
            if (!row.isEmpty()) {
                values.put(u.getId(), row);
                labels.put(u.getId(), SalaryFormulaService.employeeLabel(u));
            }
        }
        try {
            int n = monthlyService.save(ym, values, labels, PayrollWeb.username(principal));
            ra.addFlashAttribute("message", "Đã lấy " + n + " khoản từ Cấu hình lương NV sang tháng " + month + "/" + year);
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/salary-monthly";
    }

    // =====================================================================
    // EXCEL
    // =====================================================================

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam Integer month,
                                         @RequestParam Integer year,
                                         @RequestParam(required = false) String department) throws IOException {
        YearMonth ym = YearMonth.of(year, month);
        byte[] data = excelService.monthlyItems(ym, users(department), monthlyService.forMonth(ym),
                overtimeService.forMonth(ym));
        return PayrollWeb.xlsx(data, "khoan-theo-thang-" + year + "-" + String.format("%02d", month) + ".xlsx");
    }

    @PostMapping("/import")
    public String importExcel(@RequestParam Integer month,
                              @RequestParam Integer year,
                              @RequestParam(required = false) String department,
                              @RequestParam("file") MultipartFile file,
                              Principal principal,
                              RedirectAttributes ra) {

        YearMonth ym = YearMonth.of(year, month);
        back(ra, ym, department);

        if (file == null || file.isEmpty()) {
            ra.addFlashAttribute("flashError", "Chưa chọn file Excel.");
            return "redirect:/salary-monthly";
        }

        Map<String, User> byCode = new HashMap<>();
        for (User u : unitScope.users()) {   // 🔒 ĐƠN VỊ: mã trong file chỉ khớp nhân viên đơn vị mình
            byCode.putIfAbsent(ErpBonusService.normEmp(u.getEmployeeNo()), u);
            if (u.getSalaryCode() != null) {
                byCode.putIfAbsent(ErpBonusService.normEmp(u.getSalaryCode()), u);
            }
        }

        try {
            PayrollExcelService.MonthlyImport imp = excelService.readMonthlyItems(file.getInputStream(), byCode,
                    erpService.erpTypes());
            if (!imp.errors().isEmpty()) {
                ra.addFlashAttribute("flashError", "Chưa nhập. File có lỗi: " + String.join("; ", imp.errors()));
                return "redirect:/salary-monthly";
            }
            int n = monthlyService.save(ym, imp.values(), imp.labels(), PayrollWeb.username(principal));
            int ot = overtimeService.save(ym, imp.overtime(), imp.labels(), PayrollWeb.username(principal));
            ra.addFlashAttribute("message", "Nhập Excel: " + imp.values().size() + " nhân viên, " + n + " ô thay đổi"
                    + (ot > 0 ? ", tăng ca của " + ot + " người" : "")
                    + (imp.warnings().isEmpty() ? "" : ". Lưu ý: " + String.join("; ", imp.warnings())));
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        } catch (IOException | RuntimeException e) {
            ra.addFlashAttribute("flashError", "Không đọc được file Excel: " + e.getMessage());
        }
        return "redirect:/salary-monthly";
    }

    // =====================================================================
    // HELPER
    // =====================================================================

    private List<User> users(String department) {
        String filter = SalaryFormulaService.normDept(department);
        return unitScope.users().stream()   // 🔒 ĐƠN VỊ
                .filter(u -> filter.isEmpty() || filter.equals(SalaryFormulaService.normDept(u.getDepartment())))
                .sorted(Comparator.comparing((User u) -> SalaryFormulaService.normDept(u.getDepartment()))
                        .thenComparing(u -> u.getFullName() == null ? "" : u.getFullName()))
                .collect(Collectors.toList());
    }

    private boolean inScope(Long userId) {
        return userRepository.findById(userId).map(unitScope::inScope).orElse(false);
    }

    private Map<Long, SalaryConfig> configsByUser() {
        return configRepository.findAll().stream()
                .filter(c -> c.getUser() != null)
                .collect(Collectors.toMap(c -> c.getUser().getId(), Function.identity(), (a, b) -> a));
    }

    /** Mức "hằng tháng" cũ còn lưu trong cấu hình lương NV (bản trước) */
    static Map<MonthlyItemType, Double> legacy(SalaryConfig c) {
        Map<MonthlyItemType, Double> m = new EnumMap<>(MonthlyItemType.class);
        if (c == null) {
            return m;
        }
        putIf(m, MonthlyItemType.THUONG_KHAC, c.getThuongKhac());
        putIf(m, MonthlyItemType.PHAT_VI_PHAM, c.getPhatViPham());
        putIf(m, MonthlyItemType.TAM_UNG, c.getTamUng());
        putIf(m, MonthlyItemType.PHAT_KD_DV, c.getPhatKdDv());
        return m;
    }

    private static void putIf(Map<MonthlyItemType, Double> m, MonthlyItemType t, Double v) {
        if (v != null && v != 0) {
            m.put(t, v);
        }
    }

    private static void back(RedirectAttributes ra, YearMonth ym, String department) {
        ra.addAttribute("month", ym.getMonthValue());
        ra.addAttribute("year", ym.getYear());
        if (department != null && !department.isBlank()) {
            ra.addAttribute("department", department);
        }
    }

    // =====================================================================
    // 1 DÒNG
    // =====================================================================

    public static class Row {
        private final User user;
        private final Map<MonthlyItemType, Double> values;
        private final Map<MonthlyItemType, Double> erp;
        private final Map<MonthlyItemType, Double> legacy;
        /** Mức Rủi ro mặc định trong cấu hình lương NV */
        private final Double ruiRoMacDinh;
        private final List<PayrollOvertimeService.Line> overtime;
        /** Cấu hình lương NV (để biết cách tính thưởng) */
        private final SalaryConfig config;

        Row(User user, Map<MonthlyItemType, Double> values, Map<MonthlyItemType, Double> erp,
            Map<MonthlyItemType, Double> legacy, Double ruiRoMacDinh, List<PayrollOvertimeService.Line> overtime,
            SalaryConfig config) {
            this.ruiRoMacDinh = ruiRoMacDinh;
            this.overtime = overtime;
            this.user = user;
            this.values = values;
            this.erp = erp;
            this.legacy = legacy;
            this.config = config;
        }

        public User getUser() {
            return user;
        }

        /** Số đang áp dụng (ERP nếu có, không thì số nhập) - để tính tổng cột */
        public Double amount(MonthlyItemType t) {
            return erp.containsKey(t) ? erp.get(t) : values.get(t);
        }

        /** Ô nhập (5.000.000; trống nếu chưa nhập) */
        public String cell(MonthlyItemType t) {
            return PayrollWeb.money(values.get(t));
        }

        public String erpCell(MonthlyItemType t) {
            return PayrollWeb.money(erp.get(t));
        }

        /** Cách tính thưởng của NV cho khoản này: "× 80%", "cố định 2.000.000 (nhập để ghi đè)", "công thức: ..." */
        public String rule(MonthlyItemType t) {
            if (!t.isBonus() || config == null || config.getTyLe() == null) {
                return "";
            }
            return PayrollCalculationService.bonusRuleText(config.getTyLe(), t);
        }

        /** Gợi ý trong ô trống: mức cố định của NV / mức cũ trong cấu hình lương NV */
        public String hint(MonthlyItemType t) {
            if (t == MonthlyItemType.RUI_RO && ruiRoMacDinh != null) {
                return "mặc định: " + PayrollWeb.money(ruiRoMacDinh);
            }
            Double v = legacy.get(t);
            return v == null ? "" : "cấu hình cũ: " + PayrollWeb.money(v);
        }

        /** Ô tăng ca: "4x2 + 5x3" */
        public String getOtText() {
            return PayrollOvertimeService.format(overtime);
        }

        public double getOtHours() {
            return PayrollOvertimeService.totalHours(overtime);
        }

        public double getOtConverted() {
            return PayrollOvertimeService.totalConverted(overtime);
        }

        public boolean isHasLegacy() {
            return !legacy.isEmpty();
        }
    }
}