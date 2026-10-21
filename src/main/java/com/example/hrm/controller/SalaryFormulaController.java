package com.example.hrm.controller;

import com.example.hrm.entity.FormulaScope;
import com.example.hrm.entity.SalaryConfig;
import com.example.hrm.entity.SalaryFormula;
import com.example.hrm.entity.User;
import com.example.hrm.formula.DefinitionParser;
import com.example.hrm.formula.FormulaException;
import com.example.hrm.formula.SalaryPresets;
import com.example.hrm.formula.SalaryVariables;
import com.example.hrm.repository.SalaryConfigRepository;
import com.example.hrm.service.FormulaChain;
import com.example.hrm.service.PayrollAuditService;
import com.example.hrm.service.SalaryFormulaService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.YearMonth;
import java.util.*;

/**
 * MẪU CÔNG THỨC lương.
 *   /salary-formula                 danh sách mẫu (+ tạo mới / nhân bản)
 *   /salary-formula/edit?id=        sửa mẫu
 *   /salary-formula/edit?name=      tạo mẫu mới (trống)
 *
 * Nhân viên chọn mẫu và nhập tỉ lệ riêng ở /salary-config.
 */
@Controller
@RequestMapping("/salary-formula")
public class SalaryFormulaController {

    private final SalaryFormulaService formulaService;
    private final SalaryConfigRepository configRepository;
    private final PayrollAuditService audit;

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.service.UnitScope unitScope;

    public SalaryFormulaController(SalaryFormulaService formulaService, SalaryConfigRepository configRepository,
                                   PayrollAuditService audit) {
        this.formulaService = formulaService;
        this.configRepository = configRepository;
        this.audit = audit;
    }

    // =====================================================================
    // DANH SÁCH
    // =====================================================================

    @GetMapping
    public String list(Model model) {

        List<SalaryFormula> templates = formulaService.templates();
        Map<Long, Long> usage = formulaService.usageCount();
        Map<String, Long> deptCount = formulaService.employeeCountByDept();

        List<TemplateRow> rows = new ArrayList<>();
        for (SalaryFormula f : templates) {
            List<DefinitionParser.Definition> params;
            String error = null;
            try {
                params = new FormulaChain().template(f).templateParams();
            } catch (FormulaException e) {
                params = List.of();
                error = e.getMessage();
            }
            long missing = new FormulaChain().template(f).missingItems().size();
            long inDept = f.getDepartment() == null ? 0
                    : deptCount.getOrDefault(SalaryFormulaService.normDept(f.getDepartment()), 0L);
            rows.add(new TemplateRow(f, usage.getOrDefault(f.getId(), 0L), params, missing, error, inDept));
        }

        long total = configRepository.findAll().stream()   // ĐƠN VỊ: chỉ đếm nhân viên đơn vị mình
                .filter(c -> c.getUser() == null || unitScope.inScope(c.getUser())).count();
        long assigned = usage.values().stream().mapToLong(Long::longValue).sum();

        model.addAttribute("rows", rows);
        model.addAttribute("templates", templates);
        model.addAttribute("canEdit", (java.util.function.Predicate<SalaryFormula>) formulaService::editable);
        model.addAttribute("assignedCount", assigned);
        model.addAttribute("configCount", total);
        model.addAttribute("departments", formulaService.departments());
        model.addAttribute("presets", SalaryPresets.ALL);
        Set<String> have = new HashSet<>();
        templates.forEach(t -> have.add(SalaryFormulaService.templateName(t).toLowerCase(Locale.ROOT)));
        model.addAttribute("presetMissing", SalaryPresets.ALL.stream()
                .filter(p -> !have.contains(p.name().toLowerCase(Locale.ROOT))).count());
        return "salary/formula-list";
    }

    /**
     * TẠO SẴN BỘ MẪU CÔNG THỨC (Văn phòng, Kinh doanh, Dịch vụ, Lương khoán, Thử việc) cho đơn vị đang xem.
     * Mẫu đã có cùng tên thì bỏ qua (không ghi đè chỗ Kế toán đã sửa). Phòng có đúng tên gợi ý (HCNS, Dịch Vụ)
     * mà chưa có mẫu mặc định thì gắn luôn làm mặc định cho phòng đó.
     */
    @PostMapping("/presets")
    public String createPresets(@RequestParam(required = false) String key, Principal principal, RedirectAttributes ra) {
        List<SalaryFormula> existing = formulaService.templates();
        Set<String> names = new HashSet<>();
        Set<String> deptWithDefault = new HashSet<>();
        for (SalaryFormula t : existing) {
            names.add(SalaryFormulaService.templateName(t).toLowerCase(Locale.ROOT));
            if (t.getDepartment() != null && !t.isShared()) {
                deptWithDefault.add(SalaryFormulaService.normDept(t.getDepartment()));
            }
        }
        Map<String, String> deptByNorm = new HashMap<>();
        for (String d : formulaService.departments()) {
            deptByNorm.put(SalaryFormulaService.normDept(d), d);
        }

        List<String> created = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (SalaryPresets.Preset p : SalaryPresets.ALL) {
            if (key != null && !key.isBlank() && !p.key().equalsIgnoreCase(key)) {
                continue;
            }
            if (names.contains(p.name().toLowerCase(Locale.ROOT))) {
                continue;
            }
            SalaryFormula f = new SalaryFormula();
            f.setScope(FormulaScope.DEFAULT);
            f.setName(p.name());
            f.setThamSo(p.thamSo().strip());
            f.setBienTrungGian(p.bienTrungGian() == null || p.bienTrungGian().isBlank() ? null : p.bienTrungGian().strip());
            f.getItems().putAll(p.items());
            f.setGhiChu(p.note());
            f.setActive(true);
            f.setUnit(formulaService.ownerForNew());
            String dept = p.departmentHint() == null ? null : deptByNorm.get(SalaryFormulaService.normDept(p.departmentHint()));
            if (dept != null && !deptWithDefault.contains(SalaryFormulaService.normDept(dept))) {
                f.setDepartment(dept);
                deptWithDefault.add(SalaryFormulaService.normDept(dept));
            }
            List<String> err = formulaService.validate(f);
            if (!err.isEmpty() && f.getDepartment() != null) {
                f.setDepartment(null);   // phòng đã có mẫu mặc định khác: vẫn tạo mẫu, không gắn phòng
                err = formulaService.validate(f);
            }
            if (!err.isEmpty()) {
                errors.add(p.name() + ": " + String.join("; ", err));
                continue;
            }
            formulaService.save(f);
            created.add(p.name() + (f.getDepartment() != null ? " (mặc định phòng " + f.getDepartment() + ")" : ""));
        }
        if (!created.isEmpty()) {
            audit.log(PayrollWeb.username(principal), "FORMULA_PRESET", "Tạo sẵn mẫu công thức", String.join(", ", created));
            ra.addFlashAttribute("message", "Đã tạo " + created.size() + " mẫu: " + String.join(", ", created)
                    + ". Gán mẫu cho nhân viên ở Cấu hình lương NV (hoặc chọn \"Mặc định cho phòng\" trong mẫu).");
        } else if (errors.isEmpty()) {
            ra.addFlashAttribute("message", "Các mẫu soạn sẵn đã có đủ, không tạo thêm.");
        }
        if (!errors.isEmpty()) {
            ra.addFlashAttribute("error", "Không tạo được: " + String.join(" | ", errors));
        }
        return "redirect:/salary-formula";
    }

    // =====================================================================
    // SỬA / TẠO
    // =====================================================================

    @GetMapping("/edit")
    public String edit(@RequestParam(required = false) Long id,
                       @RequestParam(required = false) String name,
                       @RequestParam(required = false) String department,
                       Model model) {

        SalaryFormula f = id != null ? formulaService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy mẫu")) : null;
        if (f != null && !formulaService.visible(f)) {
            return "redirect:/salary-formula";   // 🔒 ĐƠN VỊ: mẫu riêng của đơn vị khác
        }
        if (f != null && !formulaService.editable(f)) {
            model.addAttribute("readOnlyShared", true);   // mẫu dùng chung: chỉ xem, muốn sửa thì nhân bản
        }

        if (f == null) {
            f = new SalaryFormula();
            f.setScope(FormulaScope.DEFAULT);
            f.setName(trimToNull(name));
            f.setDepartment(trimToNull(department));
            f.setActive(true);
            // Mẫu mới: điền sẵn công thức gợi ý cho đủ 15 hạng mục, HR sửa lại cho phù hợp
            for (SalaryVariables.Item it : SalaryVariables.ITEMS) {
                f.getItems().put(it.code(), it.defaultFormula());
            }
        }

        return renderEdit(f, List.of(), model);
    }

    @PostMapping("/save")
    public String save(@RequestParam Map<String, String> form, Model model, Principal principal, RedirectAttributes ra) {

        Long id = parseLong(form.get("id"));
        SalaryFormula existing = id != null ? formulaService.findById(id).orElse(null) : null;
        if (existing != null && !formulaService.editable(existing)) {
            ra.addFlashAttribute("error", "Mẫu '" + SalaryFormulaService.templateName(existing)
                    + "' là mẫu dùng chung (hoặc của đơn vị khác), chỉ quản trị hệ thống sửa được. Bấm Nhân bản để có bản riêng của đơn vị.");
            return "redirect:/salary-formula";
        }

        // Đọc form vào BẢN NHÁP: mẫu đang lưu (entity JPA) chỉ bị sửa khi đã hợp lệ
        SalaryFormula draft = new SalaryFormula();
        draft.setId(existing != null ? existing.getId() : null);
        draft.setScope(existing != null && existing.getScope() != null ? existing.getScope() : FormulaScope.DEFAULT);
        draft.setName(trimToNull(form.get("name")));
        draft.setDepartment(trimToNull(form.get("department")));
        draft.setThamSo(form.get("thamSo"));
        draft.setBienTrungGian(form.get("bienTrungGian"));
        draft.setGhiChu(form.get("ghiChu"));
        draft.setActive(form.containsKey("active"));
        draft.setUnit(existing != null ? existing.getUnit() : formulaService.ownerForNew());   // ĐƠN VỊ sở hữu mẫu
        for (SalaryVariables.Item it : SalaryVariables.ITEMS) {
            String expr = form.get("item_" + it.code());
            if (expr != null && !expr.isBlank()) {
                draft.getItems().put(it.code(), expr.trim());
            }
        }

        List<String> errors = formulaService.validate(draft);
        if (!errors.isEmpty()) {
            return renderEdit(draft, errors, model);
        }

        String changes = existing == null ? "tạo mới" : describeChanges(existing, draft);

        SalaryFormula f = existing != null ? existing : new SalaryFormula();
        if (existing == null) {
            f.setUnit(draft.getUnit());
        }
        f.setScope(draft.getScope());
        f.setName(draft.getName());
        f.setDepartment(draft.getDepartment());
        f.setThamSo(draft.getThamSo());
        f.setBienTrungGian(draft.getBienTrungGian());
        f.setGhiChu(draft.getGhiChu());
        f.setActive(draft.getActive());
        f.getItems().clear();
        f.getItems().putAll(draft.getItems());

        formulaService.save(f);
        audit.log(PayrollWeb.username(principal), "FORMULA_SAVE", "Mẫu công thức '" + f.getName() + "'", changes);
        ra.addFlashAttribute("message", "Đã lưu mẫu '" + f.getName() + "'");
        return "redirect:/salary-formula";
    }

    /** Liệt kê phần thay đổi của mẫu (ghi nhật ký) */
    private static String describeChanges(SalaryFormula a, SalaryFormula b) {
        List<String> out = new ArrayList<>();
        diff(out, "tên", a.getName(), b.getName());
        diff(out, "phòng mặc định", a.getDepartment(), b.getDepartment());
        diff(out, "đang dùng", String.valueOf(a.activeOrDefault()), String.valueOf(b.activeOrDefault()));
        diff(out, "tham số", a.getThamSo(), b.getThamSo());
        diff(out, "biến trung gian", a.getBienTrungGian(), b.getBienTrungGian());
        Set<String> codes = new TreeSet<>(a.getItems().keySet());
        codes.addAll(b.getItems().keySet());
        for (String code : codes) {
            diff(out, code, a.getItems().get(code), b.getItems().get(code));
        }
        return out.isEmpty() ? "(không đổi công thức)" : String.join("\n", out);
    }

    private static void diff(List<String> out, String what, String before, String after) {
        String x = before == null ? "" : before.trim();
        String y = after == null ? "" : after.trim();
        if (!x.equals(y)) {
            out.add(what + ": [" + x + "] → [" + y + "]");
        }
    }

    @PostMapping("/copy")
    public String copy(@RequestParam Long id,
                       @RequestParam(required = false) String name,
                       Principal principal,
                       RedirectAttributes ra) {
        if (formulaService.findById(id).filter(formulaService::visible).isEmpty()) {
            return "redirect:/salary-formula";   // 🔒 ĐƠN VỊ
        }
        SalaryFormula f = formulaService.copy(id, name);
        audit.log(PayrollWeb.username(principal), "FORMULA_COPY", "Mẫu công thức '" + f.getName() + "'", "nhân bản từ mẫu id " + id);
        ra.addFlashAttribute("message", "Đã tạo mẫu '" + f.getName() + "' từ mẫu có sẵn, sửa lại cho phù hợp rồi lưu");
        ra.addAttribute("id", f.getId());
        return "redirect:/salary-formula/edit";
    }

    @PostMapping("/delete")
    public String delete(@RequestParam Long id, Principal principal, RedirectAttributes ra) {
        formulaService.findById(id).ifPresent(f -> {
            if (!formulaService.editable(f)) {
                ra.addFlashAttribute("error", "Chỉ quản trị hệ thống xoá được mẫu dùng chung / mẫu của đơn vị khác");
                return;
            }
            String name = SalaryFormulaService.templateName(f);
            String err = formulaService.delete(id);
            if (err != null) {
                ra.addFlashAttribute("error", err);
            } else {
                audit.log(PayrollWeb.username(principal), "FORMULA_DELETE", "Mẫu công thức '" + name + "'", null);
                ra.addFlashAttribute("message", "Đã xoá mẫu '" + SalaryFormulaService.templateName(f) + "'");
            }
        });
        return "redirect:/salary-formula";
    }

    // =====================================================================
    // HELPER
    // =====================================================================

    private String renderEdit(SalaryFormula f, List<String> errors, Model model) {

        List<User> testUsers = new ArrayList<>();
        if (f.getId() != null) {
            for (SalaryConfig c : configRepository.findAll()) {
                if (c.getFormula() != null && f.getId().equals(c.getFormula().getId()) && c.getUser() != null
                        && unitScope.inScope(c.getUser())) {   // 🔒 ĐƠN VỊ
                    testUsers.add(c.getUser());
                }
            }
            testUsers.sort(Comparator.comparing(u -> u.getFullName() == null ? "" : u.getFullName()));
        }

        YearMonth last = YearMonth.now().minusMonths(1);

        model.addAttribute("departments", formulaService.departments());
        model.addAttribute("deptCount", f.getDepartment() == null ? 0L
                : formulaService.employeeCountByDept().getOrDefault(SalaryFormulaService.normDept(f.getDepartment()), 0L));
        model.addAttribute("formula", f);
        model.addAttribute("title", f.getId() == null ? "Tạo mẫu công thức" : "Mẫu: " + SalaryFormulaService.templateName(f));
        model.addAttribute("errors", errors);
        model.addAttribute("items", SalaryVariables.ITEMS);
        model.addAttribute("builtinParams", SalaryVariables.BUILTIN_PARAMS);
        model.addAttribute("inputGroups", SalaryVariables.inputsByGroup());
        model.addAttribute("functions", SalaryVariables.FUNCTIONS);
        model.addAttribute("testUsers", testUsers);
        model.addAttribute("testMonth", last.getMonthValue());
        model.addAttribute("testYear", last.getYear());
        return "salary/formula-edit";
    }

    static Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static class TemplateRow {

        private final SalaryFormula formula;
        private final long employeeCount;
        private final List<DefinitionParser.Definition> params;
        private final long missingItems;
        private final String error;
        private final long deptEmployeeCount;

        public TemplateRow(SalaryFormula formula, long employeeCount, List<DefinitionParser.Definition> params,
                           long missingItems, String error, long deptEmployeeCount) {
            this.formula = formula;
            this.employeeCount = employeeCount;
            this.params = params;
            this.missingItems = missingItems;
            this.error = error;
            this.deptEmployeeCount = deptEmployeeCount;
        }

        /** Số NV của phòng mà mẫu làm mặc định (gồm cả người đã chọn mẫu khác) */
        public long getDeptEmployeeCount() {
            return deptEmployeeCount;
        }

        public SalaryFormula getFormula() {
            return formula;
        }

        public String getName() {
            return SalaryFormulaService.templateName(formula);
        }

        public long getEmployeeCount() {
            return employeeCount;
        }

        public List<DefinitionParser.Definition> getParams() {
            return params;
        }

        public long getMissingItems() {
            return missingItems;
        }

        public String getError() {
            return error;
        }
    }
}