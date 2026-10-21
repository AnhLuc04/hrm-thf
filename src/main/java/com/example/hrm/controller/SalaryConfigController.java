package com.example.hrm.controller;

import com.example.hrm.entity.MonthlyItemType;
import com.example.hrm.entity.SalaryConfig;
import com.example.hrm.entity.SalaryFormula;
import com.example.hrm.entity.User;
import com.example.hrm.formula.DefinitionParser;
import com.example.hrm.formula.DefinitionParser.Definition;
import com.example.hrm.formula.FormulaException;
import com.example.hrm.formula.SalaryVariables;
import com.example.hrm.repository.SalaryConfigRepository;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.service.FormulaChain;
import com.example.hrm.service.PayrollAuditService;
import com.example.hrm.service.PayrollCalculationService;
import com.example.hrm.service.SalaryFormulaService;

import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cấu hình lương từng nhân viên, theo 3 bước:
 *   1. THÔNG TIN CƠ BẢN : lương cơ bản, BHXH, phụ cấp, giảm trừ cố định (số liệu theo thẻ lương)
 *   2. GÁN MẪU CÔNG THỨC: mẫu chỉ là khuôn, nhân viên KẾ THỪA toàn bộ hạng mục của mẫu
 *   3. TỈ LỆ RIÊNG      : 4 tỉ lệ thưởng (luôn có, mặc định 100%) + các tỉ lệ mà mẫu khai báo
 *
 *   /salary-config?department=      bảng nhập cả phòng
 *   /salary-config/edit?userId=     chi tiết 1 người
 */
@Controller
@RequestMapping("/salary-config")
public class SalaryConfigController {

    private final SalaryConfigRepository configRepository;
    private final UserRepository userRepository;
    private final SalaryFormulaService formulaService;
    private final PayrollAuditService audit;

    /** ĐƠN VỊ: Kế toán chỉ thấy / sửa cấu hình lương nhân viên đơn vị mình */
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.service.UnitScope unitScope;

    public SalaryConfigController(SalaryConfigRepository configRepository,
                                  UserRepository userRepository,
                                  SalaryFormulaService formulaService,
                                  PayrollAuditService audit) {
        this.configRepository = configRepository;
        this.userRepository = userRepository;
        this.formulaService = formulaService;
        this.audit = audit;
    }

    /**
     * TẤT CẢ ô số của cấu hình lương NV (bảng cả phòng + trang Chi tiết dùng chung).
     * Tên ô trên form: {name}_{hậu tố}; tiền theo ĐỒNG; HS trách nhiệm theo %.
     */
    record Field(String name, String label, Function<SalaryConfig, Double> get, BiConsumer<SalaryConfig, Double> set,
                 boolean percent, boolean count) {
    }

    static final List<Field> FIELDS = List.of(
            money("luongCoBan", "Lương cơ bản", SalaryConfig::getLuongCoBan, SalaryConfig::setLuongCoBan),
            money("luongDongBhxh", "Lương đóng BHXH", SalaryConfig::getLuongDongBhxh, SalaryConfig::setLuongDongBhxh),
            new Field("soNguoiPhuThuoc", "Người phụ thuộc",
                    c -> c.getSoNguoiPhuThuoc() == null || c.getSoNguoiPhuThuoc() == 0 ? null : c.getSoNguoiPhuThuoc().doubleValue(),
                    (c, v) -> c.setSoNguoiPhuThuoc(v == null ? 0 : v.intValue()), false, true),
            // I. LƯƠNG + PHỤ CẤP
            money("phuCapThamNien", "Thâm niên hàng năm", SalaryConfig::getPhuCapThamNien, SalaryConfig::setPhuCapThamNien),
            money("mucTrachNhiem", "Trách nhiệm (mức)", SalaryConfig::getMucTrachNhiem, SalaryConfig::setMucTrachNhiem),
            new Field("heSoTrachNhiemPct", "HS T.Nhiệm (%)",
                    c -> c.getHeSoTrachNhiem() == null ? null : Math.round(c.getHeSoTrachNhiem() * 100 * 1e6) / 1e6,
                    (c, v) -> c.setHeSoTrachNhiem(v == null ? null : v / 100.0), true, false),
            money("trachNhiemCoDinh", "T.Nhiệm được nhận", SalaryConfig::getTrachNhiemCoDinh, SalaryConfig::setTrachNhiemCoDinh),
            money("phuCapLevel", "Phụ cấp Level", SalaryConfig::getPhuCapLevel, SalaryConfig::setPhuCapLevel),
            money("phuCapKiemNhiem", "PC kiêm nhiệm", SalaryConfig::getPhuCapKiemNhiem, SalaryConfig::setPhuCapKiemNhiem),
            // II.
            money("thamNien5Nam", "Thâm niên 10 năm", SalaryConfig::getThamNien5Nam, SalaryConfig::setThamNien5Nam),
            // III. GIẢM TRỪ
            money("kyQuy", "Ký quỹ", SalaryConfig::getKyQuy, SalaryConfig::setKyQuy),
            money("bhxhCoDinh", "Bảo hiểm xã hội", SalaryConfig::getBhxhCoDinh, SalaryConfig::setBhxhCoDinh),
            money("congDoanCoDinh", "Công đoàn", SalaryConfig::getCongDoanCoDinh, SalaryConfig::setCongDoanCoDinh),
            money("thueTncnCoDinh", "Thuế TNCN", SalaryConfig::getThueTncnCoDinh, SalaryConfig::setThueTncnCoDinh)
            // Thưởng, tạm ứng, phạt, rủi ro thay đổi từng tháng -> trang "Khoản theo tháng" (/salary-monthly)
    );

    private static Field money(String name, String label, Function<SalaryConfig, Double> get, BiConsumer<SalaryConfig, Double> set) {
        return new Field(name, label, get, set, false, false);
    }

    static Field field(String name) {
        for (Field f : FIELDS) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        return null;
    }

    /** tên viết thường -> tên chuẩn của 4 tỉ lệ thưởng (tyLeThuongDichVu, ...) */
    static Map<String, String> bonusNames() {
        Map<String, String> m = new LinkedHashMap<>();
        for (MonthlyItemType t : PayrollCalculationService.bonusTypes()) {
            String n = PayrollCalculationService.bonusRateName(t);
            m.put(n.toLowerCase(Locale.ROOT), n);
        }
        return m;
    }


    // =====================================================================
    // BẢNG NHẬP CẢ PHÒNG
    // =====================================================================

    @GetMapping
    public String list(@RequestParam(required = false) String department, Model model) {

        Map<Long, SalaryConfig> configs = configsByUser();
        String filter = SalaryFormulaService.normDept(department);
        SalaryFormulaService.Snapshot snap = formulaService.snapshot();

        List<Row> rows = new ArrayList<>();
        for (User u : unitScope.users()) {   // 🔒 ĐƠN VỊ
            if (!filter.isEmpty() && !filter.equals(SalaryFormulaService.normDept(u.getDepartment()))) {
                continue;
            }
            rows.add(new Row(u, configs.get(u.getId()), snap.departmentTemplate(u)));
        }
        rows.sort(Comparator.comparing((Row r) -> SalaryFormulaService.normDept(r.getUser().getDepartment()))
                .thenComparing(r -> r.getUser().getFullName() == null ? "" : r.getUser().getFullName()));

        addTemplateModel(model);
        model.addAttribute("rows", rows);
        model.addAttribute("departments", formulaService.departments());
        model.addAttribute("department", department);
        return "salary/config-list";
    }

    @PostMapping("/bulk-save")
    @Transactional
    public String bulkSave(@RequestParam Map<String, String> form,
                           @RequestParam(required = false) String department,
                           Principal principal,
                           RedirectAttributes ra) {

        Map<Long, SalaryConfig> configs = configsByUser();
        Map<Long, SalaryFormula> templates = templatesById();
        SalaryFormulaService.Snapshot snap = formulaService.snapshot();

        List<String> errors = new ArrayList<>();

        // ---- LƯỢT 1: chỉ đọc + kiểm tra, KHÔNG sửa entity nào ----
        // (entity đang được JPA quản lý: sửa rồi mới phát hiện lỗi thì vẫn bị ghi xuống DB khi commit)
        Map<Long, Parsed> parsed = new LinkedHashMap<>();

        for (String key : form.keySet()) {
            if (!key.startsWith("row_")) {
                continue;
            }
            Long userId = SalaryFormulaController.parseLong(key.substring(4));
            if (userId == null) {
                continue;
            }
            String label = form.getOrDefault("label_" + userId, String.valueOf(userId));
            User u = userRepository.findById(userId).orElse(null);
            if (u == null || !unitScope.inScope(u)) {
                continue;   // 🔒 ĐƠN VỊ: bỏ dòng của đơn vị khác
            }
            SalaryFormula deptTpl = u == null ? null : snap.departmentTemplate(u);
            parsed.put(userId, parseRow(form, userId + "", label, templates, deptTpl, errors));
        }

        if (department != null && !department.isBlank()) {
            ra.addAttribute("department", department);
        }

        if (!errors.isEmpty()) {
            ra.addFlashAttribute("error", "Chưa lưu. Sửa các ô sau rồi bấm Lưu lại: " + String.join("; ", errors));
            return "redirect:/salary-config";
        }

        // ---- LƯỢT 2: không còn lỗi -> mới cập nhật ----
        List<SalaryConfig> toSave = new ArrayList<>();
        StringBuilder log = new StringBuilder();

        for (Map.Entry<Long, Parsed> e : parsed.entrySet()) {
            Parsed p = e.getValue();
            SalaryConfig cfg = configs.get(e.getKey());

            // Chưa có cấu hình và không nhập gì -> không tạo bản ghi rỗng
            if (cfg == null && p.isEmpty()) {
                continue;
            }
            if (cfg == null) {
                User u = userRepository.findById(e.getKey()).orElse(null);
                if (u == null) {
                    continue;
                }
                cfg = new SalaryConfig();
                cfg.setUser(u);
            }

            if (cfg.getId() != null && !p.differsFrom(cfg)) {
                continue;
            }

            log.append(form.getOrDefault("label_" + e.getKey(), "#" + e.getKey())).append(": ")
                    .append(p.describeChanges(cfg)).append('\n');
            p.applyTo(cfg);
            cfg.setUpdatedAt(LocalDateTime.now());
            toSave.add(cfg);
        }

        configRepository.saveAll(toSave);
        if (!toSave.isEmpty()) {
            audit.log(PayrollWeb.username(principal), "CONFIG_BULK_SAVE",
                    "Cấu hình lương NV (" + toSave.size() + " người)", log.toString());
        }
        ra.addFlashAttribute("message", toSave.isEmpty()
                ? "Không có thay đổi nào"
                : "Đã lưu cấu hình lương cho " + toSave.size() + " nhân viên");
        return "redirect:/salary-config";
    }

    // =====================================================================
    // CHI TIẾT 1 NHÂN VIÊN
    // =====================================================================

    @GetMapping("/edit")
    public String edit(@RequestParam Long userId, Model model) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên"));
        if (!unitScope.inScope(user)) {
            return "redirect:/salary-config";   // 🔒 ĐƠN VỊ
        }

        SalaryConfig cfg = configRepository.findByUserId(userId).orElseGet(() -> {
            SalaryConfig c = new SalaryConfig();
            c.setUser(user);
            c.setLuongCoBan(user.getSalary());
            return c;
        });

        return renderEdit(cfg, List.of(), model);
    }

    @PostMapping("/save")
    @Transactional
    public String save(@RequestParam Map<String, String> form, Model model, Principal principal, RedirectAttributes ra) {

        Long userId = SalaryFormulaController.parseLong(form.get("userId"));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên"));
        if (!unitScope.inScope(user)) {
            ra.addFlashAttribute("error", "Nhân viên này thuộc đơn vị khác");
            return "redirect:/salary-config";   // 🔒 ĐƠN VỊ
        }

        List<String> errors = new ArrayList<>();

        // Trang chi tiết dùng cùng tên ô với bảng, hậu tố "_x"
        Parsed p = parseRow(form, "x", SalaryFormulaService.employeeLabel(user), templatesById(),
                formulaService.snapshot().departmentTemplate(user), errors);

        String thamSoRieng = form.get("thamSoRieng");
        try {
            DefinitionParser.parse(thamSoRieng, "Tham số riêng");
        } catch (FormulaException e) {
            errors.add(e.getMessage());
        }

        SalaryConfig existing = configRepository.findByUserId(userId).orElse(null);

        if (!errors.isEmpty()) {
            // Hiện lại form bằng 1 BẢN SAO (không sửa entity đang được JPA quản lý)
            SalaryConfig draft = new SalaryConfig();
            draft.setUser(user);
            p.applyTo(draft);
            draft.setThamGiaBhxh(form.containsKey("thamGiaBhxh"));
            draft.setThamGiaCongDoan(form.containsKey("thamGiaCongDoan"));
            draft.setThamSoRieng(thamSoRieng);
            draft.setGhiChu(form.get("ghiChu"));
            if (existing != null) {
                draft.setId(existing.getId());
            }
            return renderEdit(draft, errors, model);
        }

        SalaryConfig cfg = existing != null ? existing : new SalaryConfig();
        String changes = existing == null ? "tạo mới: " + p.describeChanges(new SalaryConfig()) : p.describeChanges(cfg);
        if (existing != null && !Objects.equals(existing.getThamSoRieng(), thamSoRieng)) {
            changes += "; tham số riêng: [" + nvl(existing.getThamSoRieng()) + "] → [" + nvl(thamSoRieng) + "]";
        }
        cfg.setUser(user);
        p.applyTo(cfg);
        cfg.setThamGiaBhxh(form.containsKey("thamGiaBhxh"));
        cfg.setThamGiaCongDoan(form.containsKey("thamGiaCongDoan"));
        cfg.setThamSoRieng(thamSoRieng);
        cfg.setGhiChu(form.get("ghiChu"));
        cfg.setUpdatedAt(LocalDateTime.now());
        configRepository.save(cfg);
        audit.log(PayrollWeb.username(principal), "CONFIG_SAVE",
                "Cấu hình lương " + SalaryFormulaService.employeeLabel(user), changes);

        ra.addFlashAttribute("message", "Đã lưu cấu hình lương của " + SalaryFormulaService.employeeLabel(user));
        ra.addAttribute("department", user.getDepartment());
        return "redirect:/salary-config";
    }

    private String renderEdit(SalaryConfig cfg, List<String> errors, Model model) {
        addTemplateModel(model);
        model.addAttribute("cfg", cfg);
        model.addAttribute("row", new Row(cfg.getUser(), cfg,
                formulaService.snapshot().departmentTemplate(cfg.getUser())));
        model.addAttribute("user", cfg.getUser());
        model.addAttribute("errors", errors);
        return "salary/config-edit";
    }

    // =====================================================================
    // ĐỌC 1 DÒNG (bảng) HOẶC FORM CHI TIẾT
    // =====================================================================

    /** Dữ liệu 1 nhân viên đọc từ form, CHƯA ghi vào entity */
    static final class Parsed {
        final Map<String, Double> values = new HashMap<>();
        SalaryFormula formula;
        final Map<String, String> rates = new LinkedHashMap<>();
        /** form có gửi ô tỉ lệ không; không gửi (bảng danh sách gọn) -> giữ nguyên tỉ lệ đang lưu */
        boolean ratesProvided;

        boolean isEmpty() {
            return formula == null && rates.isEmpty() && values.values().stream().allMatch(Objects::isNull);
        }

        boolean differsFrom(SalaryConfig c) {
            Long oldF = c.getFormula() == null ? null : c.getFormula().getId();
            Long newF = formula == null ? null : formula.getId();
            if (!Objects.equals(oldF, newF)) {
                return true;
            }
            if (ratesProvided && !Objects.equals(c.getTyLe() == null ? Map.of() : c.getTyLe(), rates)) {
                return true;
            }
            for (Field f : FIELDS) {
                if (values.containsKey(f.name()) && !sameNumber(f.get().apply(c), values.get(f.name()))) {
                    return true;
                }
            }
            return false;
        }

        void applyTo(SalaryConfig c) {
            c.setFormula(formula);
            if (c.getTyLe() == null) {
                c.setTyLe(new LinkedHashMap<>());
            }
            if (ratesProvided) {
                c.getTyLe().clear();
                c.getTyLe().putAll(rates);
            }
            for (Field f : FIELDS) {
                if (values.containsKey(f.name())) {
                    f.set().accept(c, values.get(f.name()));
                }
            }
        }

        /** "Lương cơ bản: 10.000.000 → 11.000.000; mẫu: A → B" (ghi nhật ký) */
        String describeChanges(SalaryConfig c) {
            List<String> out = new ArrayList<>();
            String oldF = c.getFormula() == null ? "(theo phòng)" : SalaryFormulaService.templateName(c.getFormula());
            String newF = formula == null ? "(theo phòng)" : SalaryFormulaService.templateName(formula);
            if (!oldF.equals(newF)) {
                out.add("mẫu: " + oldF + " → " + newF);
            }
            Map<String, String> oldRates = c.getTyLe() == null ? Map.of() : c.getTyLe();
            if (ratesProvided && !Objects.equals(oldRates, rates)) {
                out.add("tỉ lệ riêng: " + oldRates + " → " + rates);
            }
            for (Field f : FIELDS) {
                if (values.containsKey(f.name()) && !sameNumber(f.get().apply(c), values.get(f.name()))) {
                    out.add(f.label() + ": " + show(f.get().apply(c)) + " → " + show(values.get(f.name())));
                }
            }
            return out.isEmpty() ? "(không đổi số liệu)" : String.join("; ", out);
        }

        private static String show(Double v) {
            return v == null ? "(trống)" : PayrollWeb.money(v);
        }

        private static boolean sameNumber(Double a, Double b) {
            if (a == null || b == null) {
                return a == null && b == null;
            }
            return Math.abs(a - b) < 1e-6;
        }
    }

    /**
     * Tên ô: {field}_{suffix}, formula_{suffix}, tyle_{suffix}_{tenThamSo}.
     * Tỉ lệ đọc gồm: các tham số mà mẫu ĐANG ÁP DỤNG khai báo + 4 tỉ lệ thưởng (luôn có).
     */
    private Parsed parseRow(Map<String, String> form, String suffix, String label,
                            Map<Long, SalaryFormula> templates, SalaryFormula deptTemplate, List<String> errors) {
        Parsed p = new Parsed();
        String ratePrefix = "tyle_" + suffix + "_";
        p.ratesProvided = form.keySet().stream().anyMatch(k -> k.startsWith(ratePrefix));

        for (Field f : FIELDS) {
            String key = f.name() + "_" + suffix;
            if (form.containsKey(key)) {   // ô không có trên form (trang cũ) -> giữ nguyên giá trị đang lưu
                p.values.put(f.name(), checkedNum(form.get(key), label + " - " + f.label(), errors));
            }
        }

        Long formulaId = SalaryFormulaController.parseLong(form.get("formula_" + suffix));
        if (formulaId != null) {
            p.formula = templates.get(formulaId);
            if (p.formula == null) {
                errors.add(label + ": mẫu công thức không còn tồn tại, chọn lại");
                return p;
            }
        }

        Map<String, String> bonus = bonusNames();
        Map<String, String> declaredBonus = new HashMap<>();   // tên chuẩn -> tên đúng như mẫu khai báo

        // Không chọn mẫu riêng -> tỉ lệ nhập theo mẫu mặc định của phòng ban
        SalaryFormula effective = p.formula != null ? p.formula : deptTemplate;
        if (effective != null) {
            List<Definition> params;
            try {
                params = new FormulaChain().template(effective).templateParams();
            } catch (FormulaException e) {
                errors.add(label + ": mẫu '" + SalaryFormulaService.templateName(effective) + "' đang lỗi: " + e.getMessage());
                return p;
            }
            for (Definition d : params) {
                String canon = bonus.get(d.name().toLowerCase(Locale.ROOT));
                if (canon != null) {
                    declaredBonus.put(canon, d.name());   // tỉ lệ thưởng: đọc ở ô thưởng bên dưới
                    continue;
                }
                readRate(form, "tyle_" + suffix + "_" + d.name(), d.name(), label + " - " + d.display(), p.rates, errors);
            }
        }

        // 4 khoản thưởng: tỉ lệ (luôn có) + cách tính riêng (số nhập × tỉ lệ / số tiền cố định / công thức riêng)
        for (MonthlyItemType t : PayrollCalculationService.bonusTypes()) {
            String canon = PayrollCalculationService.bonusRateName(t);
            String what = label + " - " + t.getLabel();
            readRate(form, ratePrefix + canon, declaredBonus.getOrDefault(canon, canon),
                    what + " (tỉ lệ)", p.rates, errors);

            String modeKey = PayrollCalculationService.bonusModeKey(t);
            String fixedKey = PayrollCalculationService.bonusFixedKey(t);
            String formulaKey = PayrollCalculationService.bonusFormulaKey(t);
            String mode = nvl(form.get(ratePrefix + modeKey));

            if ("1".equals(mode)) {
                int before = errors.size();
                Double amount = checkedNum(form.get(ratePrefix + fixedKey), what + " (số tiền cố định)", errors);
                if (errors.size() == before && amount == null) {
                    errors.add(what + ": chọn 'Số tiền cố định' thì phải nhập số tiền");
                } else if (amount != null) {
                    p.rates.put(modeKey, "1");
                    p.rates.put(fixedKey, plain(amount));
                }
            } else if ("2".equals(mode)) {
                String f = nvl(form.get(ratePrefix + formulaKey));
                if (f.isEmpty()) {
                    errors.add(what + ": chọn 'Công thức riêng' thì phải nhập công thức");
                } else {
                    try {
                        DefinitionParser.parse("congThuc = " + f, what);
                        p.rates.put(modeKey, "2");
                        p.rates.put(formulaKey, f);
                    } catch (FormulaException e) {
                        errors.add(what + ": công thức sai - " + e.getMessage());
                    }
                }
            }
        }
        return p;
    }

    /** 2000000.0 -> "2000000"; 1500.5 -> "1500.5" */
    private static String plain(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static void readRate(Map<String, String> form, String key, String name, String what,
                                 Map<String, String> rates, List<String> errors) {
        String raw = form.get(key);
        if (raw == null || raw.isBlank()) {
            return;   // trống = dùng mặc định của mẫu (tỉ lệ thưởng: 100%)
        }
        String v = normalizeRate(raw);
        String err = SalaryFormulaService.checkRate(v);
        if (err != null) {
            errors.add(what + ": '" + raw + "' " + err);
        } else {
            rates.put(name, v);
        }
    }

    /** "1,5%" -> "1.5%", "2.000.000" -> "2000000", "0,85" -> "0.85" (cho phép gõ kiểu Việt Nam) */
    static String normalizeRate(String raw) {
        String t = raw.trim().replace(" ", "");
        boolean pct = t.endsWith("%");
        String n = pct ? t.substring(0, t.length() - 1) : t;
        if (n.matches("\\d{1,3}(\\.\\d{3})+(,\\d+)?")) {
            n = n.replace(".", "").replace(",", ".");
        } else if (n.matches("\\d+,\\d+")) {
            n = n.replace(",", ".");
        } else {
            return t;   // biểu thức khác: để nguyên cho bộ kiểm tra báo lỗi nếu sai
        }
        return pct ? n + "%" : n;
    }

    private static String nvl(String s) {
        return s == null ? "" : s.trim();
    }

    private static Double checkedNum(String raw, String label, List<String> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Double v = num(raw);
        if (v == null || v < 0) {
            errors.add(label + ": '" + raw + "' không phải số hợp lệ");
            return null;
        }
        return v;
    }

    // =====================================================================
    // DỮ LIỆU MẪU CHO GIAO DIỆN
    // =====================================================================

    /**
     * templates         : mẫu đang dùng được (ô chọn)
     * templateParams    : id mẫu -> tham số (ô nhập tỉ lệ), KHÔNG gồm 4 tỉ lệ thưởng (có ô riêng)
     * bonusRates        : 4 ô tỉ lệ thưởng (tên, nhãn)
     * bonusDefaults     : id mẫu -> (tên tỉ lệ thưởng -> mặc định mẫu khai báo); không khai báo = 100%
     * templateJson      : cùng dữ liệu cho JavaScript + các hạng mục nhân viên kế thừa từ mẫu
     */
    private void addTemplateModel(Model model) {
        Map<String, String> bonus = bonusNames();
        Map<Long, List<Definition>> params = new HashMap<>();
        Map<Long, Map<String, String>> bonusDefaults = new HashMap<>();
        List<Map<String, Object>> json = new ArrayList<>();

        for (SalaryFormula f : formulaService.templates()) {
            List<Definition> ps;
            try {
                ps = new FormulaChain().template(f).templateParams();
            } catch (FormulaException e) {
                ps = List.of();
            }
            List<Definition> visible = new ArrayList<>();
            Map<String, String> bDef = new LinkedHashMap<>();
            for (Definition d : ps) {
                String canon = bonus.get(d.name().toLowerCase(Locale.ROOT));
                if (canon != null) {
                    bDef.put(canon, d.expression());
                } else {
                    visible.add(d);
                }
            }
            params.put(f.getId(), visible);
            bonusDefaults.put(f.getId(), bDef);

            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", f.getId());
            t.put("name", SalaryFormulaService.templateName(f));
            t.put("active", f.activeOrDefault());
            List<Map<String, String>> pl = new ArrayList<>();
            for (Definition d : visible) {
                pl.add(Map.of("name", d.name(), "label", d.display(), "def", d.expression()));
            }
            t.put("params", pl);
            t.put("bonusDefaults", bDef);

            // Hạng mục nhân viên kế thừa từ mẫu (chỉ xem)
            List<Map<String, Object>> items = new ArrayList<>();
            for (SalaryVariables.Item it : SalaryVariables.ITEMS) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", it.code());
                m.put("label", it.label());
                String expr = f.getItems() == null ? null : f.getItems().get(it.code());
                m.put("expr", expr == null || expr.isBlank() ? "" : expr);
                m.put("fallback", it.optional() ? "số nhập (Khoản theo tháng / ERP) × tỉ lệ thưởng của NV" : "(mẫu chưa có công thức)");
                items.add(m);
            }
            t.put("items", items);
            json.add(t);
        }

        List<Map<String, String>> bonusRates = new ArrayList<>();
        for (MonthlyItemType bt : PayrollCalculationService.bonusTypes()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", PayrollCalculationService.bonusRateName(bt));
            m.put("label", bt.getLabel());
            m.put("modeKey", PayrollCalculationService.bonusModeKey(bt));
            m.put("fixedKey", PayrollCalculationService.bonusFixedKey(bt));
            m.put("formulaKey", PayrollCalculationService.bonusFormulaKey(bt));
            bonusRates.add(m);
        }

        model.addAttribute("templates", formulaService.activeTemplates());
        model.addAttribute("allTemplates", formulaService.templates());
        model.addAttribute("templateParams", params);
        model.addAttribute("bonusRates", bonusRates);
        model.addAttribute("bonusDefaults", bonusDefaults);
        model.addAttribute("templateJson", json);
    }

    private Map<Long, SalaryConfig> configsByUser() {
        return configRepository.findAll().stream()
                .filter(c -> c.getUser() != null)
                .collect(Collectors.toMap(c -> c.getUser().getId(), c -> c, (a, b) -> a));
    }

    private Map<Long, SalaryFormula> templatesById() {
        Map<Long, SalaryFormula> m = new HashMap<>();
        formulaService.templates().forEach(f -> m.put(f.getId(), f));
        return m;
    }

    /** "12.000.000" / "5,610,000" / "12000000" / "1,5" / "1.5" -> số; trống/sai -> null */
    static Double num(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim().replace(" ", "");
        if (t.matches("-?\\d{1,3}(\\.\\d{3})+(,\\d+)?")) {
            t = t.replace(".", "").replace(",", ".");
        } else if (t.matches("-?\\d{1,3}(,\\d{3})+(\\.\\d+)?")) {
            t = t.replace(",", "");
        } else {
            t = t.replace(",", ".");
        }
        try {
            return Double.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // =====================================================================
    // 1 DÒNG TRÊN BẢNG
    // =====================================================================

    public static class Row {

        private final User user;
        private final SalaryConfig config;
        private final SalaryFormula deptTemplate;

        public Row(User user, SalaryConfig config) {
            this(user, config, null);
        }

        public Row(User user, SalaryConfig config, SalaryFormula deptTemplate) {
            this.user = user;
            this.config = config;
            this.deptTemplate = deptTemplate;
        }

        /** Mẫu mặc định của phòng ban nhân viên (null nếu phòng chưa có) */
        public SalaryFormula getDeptTemplate() {
            return deptTemplate;
        }

        public String getDeptTemplateName() {
            return deptTemplate == null ? null : SalaryFormulaService.templateName(deptTemplate);
        }

        /** Mẫu đang áp dụng: chọn riêng, không có thì theo phòng */
        public Long getEffectiveFormulaId() {
            Long id = getFormulaId();
            return id != null ? id : (deptTemplate == null ? null : deptTemplate.getId());
        }

        public User getUser() {
            return user;
        }

        public SalaryConfig getConfig() {
            return config;
        }

        public Long getFormulaId() {
            return config == null || config.getFormula() == null ? null : config.getFormula().getId();
        }

        public SalaryFormula getFormula() {
            return config == null ? null : config.getFormula();
        }

        /** Tỉ lệ riêng đã nhập (trống = dùng mặc định của mẫu); tên không phân biệt hoa thường */
        public String rate(String name) {
            if (config == null || config.getTyLe() == null) {
                return "";
            }
            String v = config.getTyLe().get(name);
            if (v == null) {
                for (Map.Entry<String, String> e : config.getTyLe().entrySet()) {
                    if (e.getKey().equalsIgnoreCase(name)) {
                        v = e.getValue();
                        break;
                    }
                }
            }
            return v == null ? "" : v;
        }

        /** Giá trị hiển thị trong ô số (5.610.000; trống nếu chưa nhập) */
        public String cell(String name) {
            if (config == null) {
                return "luongCoBan".equals(name) && user.getSalary() != null ? fmt(user.getSalary()) : "";
            }
            Field f = field(name);
            if (f == null) {
                return "";
            }
            Double v = f.get().apply(config);
            if (v == null) {
                return "";
            }
            if (f.percent() || f.count()) {
                return new DecimalFormat("0.##").format(v);
            }
            return fmt(v);
        }

        /** Có lương cơ bản (cấu hình hoặc hồ sơ nhân viên) */
        public boolean isHasBaseSalary() {
            Double v = config != null && config.getLuongCoBan() != null ? config.getLuongCoBan() : user.getSalary();
            return v != null && v > 0;
        }

        /** Tổng phụ cấp hằng tháng (đủ công), hiện tóm tắt trên bảng: "1.500.000"; trống nếu không có */
        public String getTongPhuCap() {
            if (config == null) {
                return "";
            }
            double tn = config.getTrachNhiemCoDinh() != null ? config.getTrachNhiemCoDinh()
                    : (getTrachNhiemDuocNhan() == null ? 0 : getTrachNhiemDuocNhan());
            double sum = nz(config.getPhuCapThamNien()) + nz(config.getThamNien5Nam()) + nz(config.getPhuCapLevel())
                    + nz(config.getPhuCapKiemNhiem()) + tn;
            return sum > 0 ? fmt(sum) : "";
        }

        /** Cách tính thưởng riêng: ["Thưởng dịch vụ: × 80%", "Thưởng chính sách: cố định 2.000.000"]; rỗng = mặc định */
        public List<String> getCustomBonusRates() {
            List<String> out = new ArrayList<>();
            if (config == null || config.getTyLe() == null) {
                return out;
            }
            for (MonthlyItemType t : PayrollCalculationService.bonusTypes()) {
                String v = PayrollCalculationService.bonusRuleText(config.getTyLe(), t);
                if (!v.isBlank()) {
                    out.add(t.getLabel() + ": " + v);
                }
            }
            return out;
        }

        /** Số tiền cố định đã lưu, hiện theo kiểu 2.000.000 */
        public String money(String key) {
            String v = rate(key);
            if (v.isBlank()) {
                return "";
            }
            Double d = num(v);
            return d == null ? v : fmt(d);
        }

        private static double nz(Double v) {
            return v == null ? 0 : v;
        }

        /** Trách nhiệm được nhận (đủ công) = mức × hệ số; hệ số trống = 100% */
        public Double getTrachNhiemDuocNhan() {
            if (config == null || config.getMucTrachNhiem() == null) {
                return null;
            }
            double hs = config.getHeSoTrachNhiem() == null ? 1 : config.getHeSoTrachNhiem();
            return config.getMucTrachNhiem() * hs;
        }

        private static String fmt(Double v) {
            if (v == null) {
                return "";
            }
            DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.ROOT);
            sym.setGroupingSeparator('.');
            sym.setDecimalSeparator(',');
            return new DecimalFormat("#,##0.##", sym).format(v);
        }
    }
}