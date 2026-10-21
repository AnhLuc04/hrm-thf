package com.example.hrm.service;

import com.example.hrm.entity.FormulaScope;
import com.example.hrm.entity.SalaryConfig;
import com.example.hrm.entity.SalaryFormula;
import com.example.hrm.entity.User;
import com.example.hrm.formula.DefinitionParser;
import com.example.hrm.formula.DefinitionParser.Definition;
import com.example.hrm.formula.FormulaEngine;
import com.example.hrm.formula.FormulaException;
import com.example.hrm.formula.SalaryVariables;
import com.example.hrm.formula.Vars;
import com.example.hrm.repository.SalaryConfigRepository;
import com.example.hrm.repository.SalaryFormulaRepository;
import com.example.hrm.repository.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Quản lý MẪU CÔNG THỨC lương.
 * Nhân viên chọn mẫu trong cấu hình lương (SalaryConfig.formula) và nhập tỉ lệ riêng.
 */
@Service
public class SalaryFormulaService {

    private final SalaryFormulaRepository formulaRepository;
    private final SalaryConfigRepository configRepository;
    private final UserRepository userRepository;

    public SalaryFormulaService(SalaryFormulaRepository formulaRepository,
                                SalaryConfigRepository configRepository,
                                UserRepository userRepository) {
        this.formulaRepository = formulaRepository;
        this.configRepository = configRepository;
        this.userRepository = userRepository;
    }

    /** ĐƠN VỊ (null trong kiểm thử = không lọc) */
    private UnitScope unitScope;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUnitScope(UnitScope unitScope) {
        this.unitScope = unitScope;
    }

    private Long scope() {
        return unitScope == null ? null : unitScope.scopeUnitId();
    }

    /** Mẫu này hiện cho người đang đăng nhập: mẫu dùng chung + mẫu của đơn vị mình */
    public boolean visible(SalaryFormula f) {
        if (f != null && unitScope != null && unitScope.isGlobalAdmin(unitScope.currentUser())) {
            return true;   // quản trị hệ thống thấy mọi mẫu (để nhân bản mẫu đơn vị này sang đơn vị khác)
        }
        Long s = scope();
        return f != null && (s == null || f.getUnit() == null || s.equals(f.getUnit().getId()));
    }

    /** Sửa / xoá được: quản trị hệ thống sửa mọi mẫu; người khác chỉ sửa mẫu RIÊNG của đơn vị mình */
    public boolean editable(SalaryFormula f) {
        if (f == null) {
            return false;
        }
        if (unitScope == null) {
            return true;
        }
        if (unitScope.isGlobalAdmin(unitScope.currentUser())) {
            return true;
        }
        Long s = scope();
        return f.getUnit() != null && s != null && s.equals(f.getUnit().getId());
    }

    private List<User> scopedUsers() {
        return unitScope == null ? userRepository.findAll() : unitScope.users();
    }

    // =====================================================================
    // TRA CỨU
    // =====================================================================

    public Optional<SalaryFormula> findById(Long id) {
        return id == null ? Optional.empty() : formulaRepository.findById(id);
    }

    /** Tất cả mẫu, sắp theo tên */
    public List<SalaryFormula> templates() {
        List<SalaryFormula> list = new ArrayList<>(formulaRepository.findAll());
        list.removeIf(f -> !visible(f));   // 🔒 ĐƠN VỊ: không thấy mẫu riêng của đơn vị khác
        list.sort(Comparator.comparing(f -> templateName(f).toLowerCase(Locale.ROOT)));
        return list;
    }

    /** Mẫu đang dùng được (để chọn cho nhân viên) */
    public List<SalaryFormula> activeTemplates() {
        return templates().stream().filter(SalaryFormula::activeOrDefault).toList();
    }

    /** id mẫu -> số nhân viên đang dùng */
    public Map<Long, Long> usageCount() {
        Map<Long, Long> m = new HashMap<>();
        for (SalaryConfig c : configRepository.findAll()) {
            if (c.getUser() != null && unitScope != null && !unitScope.inScope(c.getUser())) {
                continue;   // ĐƠN VỊ: chỉ đếm nhân viên đơn vị mình
            }
            if (c.getFormula() != null) {
                m.merge(c.getFormula().getId(), 1L, Long::sum);
            }
        }
        return m;
    }

    /** phòng ban (chuẩn hoá) -> số nhân viên */
    public Map<String, Long> employeeCountByDept() {
        Map<String, Long> m = new HashMap<>();
        for (User u : scopedUsers()) {
            m.merge(normDept(u.getDepartment()), 1L, Long::sum);
        }
        return m;
    }

    /** Danh sách phòng ban (users.department) để lọc */
    public List<String> departments() {
        TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (User u : scopedUsers()) {
            if (u.getDepartment() != null && !u.getDepartment().isBlank()) {
                set.add(u.getDepartment().trim());
            }
        }
        for (SalaryFormula f : formulaRepository.findAll()) {
            if (visible(f) && f.getDepartment() != null && !f.getDepartment().isBlank()) {
                set.add(f.getDepartment().trim());
            }
        }
        return new ArrayList<>(set);
    }

    public static String normDept(String d) {
        return d == null ? "" : d.trim().toLowerCase(Locale.ROOT);
    }

    /** Tên hiển thị của mẫu (bản ghi cũ theo phòng ban / nhân viên chưa có tên thì tự đặt) */
    public static String templateName(SalaryFormula f) {
        if (f == null) {
            return "";
        }
        if (f.getName() != null && !f.getName().isBlank()) {
            return f.getName().trim();
        }
        if (f.getScope() == FormulaScope.DEPARTMENT && f.getDepartment() != null) {
            return "Phòng " + f.getDepartment();
        }
        if (f.getScope() == FormulaScope.EMPLOYEE && f.getUser() != null) {
            return "Riêng " + employeeLabel(f.getUser());
        }
        return "Mẫu #" + f.getId();
    }

    public static String employeeLabel(User u) {
        if (u == null) {
            return "?";
        }
        String code = u.getEmployeeNo() != null ? u.getEmployeeNo() : String.valueOf(u.getId());
        return u.getFullName() != null ? code + " - " + u.getFullName() : code;
    }

    // =====================================================================
    // CÔNG THỨC CỦA 1 NHÂN VIÊN
    // =====================================================================

    /** Nạp mẫu 1 lần để tính cả công ty (tránh query lặp) */
    public Snapshot snapshot() {
        Snapshot s = new Snapshot(unitScope == null ? u -> null : unitScope::unitKey);
        List<SalaryFormula> all = new ArrayList<>(formulaRepository.findAll());
        all.sort(Comparator.comparing(SalaryFormula::getId));
        for (SalaryFormula f : all) {
            s.byId.put(f.getId(), f);
            if (f.activeOrDefault() && f.getDepartment() != null && !f.getDepartment().isBlank()) {
                // ĐƠN VỊ: mẫu mặc định của phòng theo từng đơn vị; mẫu dùng chung là dự phòng
                s.byDept.putIfAbsent(Snapshot.key(f.getUnit() == null ? null : f.getUnit().getId(), f.getDepartment()), f);
            }
        }
        return s;
    }

    public static final class Snapshot {

        private final Map<Long, SalaryFormula> byId = new HashMap<>();
        private final Map<String, SalaryFormula> byDept = new HashMap<>();
        private final java.util.function.Function<User, Long> unitKey;

        Snapshot(java.util.function.Function<User, Long> unitKey) {
            this.unitKey = unitKey;
        }

        static String key(Long unitId, String department) {
            return (unitId == null ? "*" : unitId.toString()) + "|" + normDept(department);
        }

        /**
         * Mẫu áp dụng cho nhân viên:
         *   1. mẫu chọn riêng trong cấu hình lương NV (nếu đang dùng)
         *   2. không chọn -> mẫu MẶC ĐỊNH của phòng ban nhân viên
         *   3. không có -> chuỗi rỗng (không tính lương)
         */
        public FormulaChain chainFor(User user, SalaryConfig config) {
            FormulaChain chain = new FormulaChain();
            SalaryFormula f = templateFor(user, config);
            if (f == null) {
                return chain;
            }
            return chain.template(f).employee(config, employeeLabel(user));
        }

        public SalaryFormula templateFor(User user, SalaryConfig config) {
            if (config != null && config.getFormula() != null) {
                SalaryFormula f = byId.getOrDefault(config.getFormula().getId(), config.getFormula());
                return f.activeOrDefault() ? f : null;
            }
            return user == null ? null : departmentTemplate(user);
        }

        /** Mẫu mặc định của phòng nhân viên: mẫu riêng đơn vị trước, không có thì mẫu dùng chung */
        public SalaryFormula departmentTemplate(User user) {
            if (user == null) {
                return null;
            }
            Long k = unitKey.apply(user);
            SalaryFormula f = k == null ? null : byDept.get(key(k, user.getDepartment()));
            return f != null ? f : byDept.get(key(null, user.getDepartment()));
        }

        /** Mẫu mặc định của 1 phòng (null nếu chưa có) */
        public SalaryFormula departmentTemplate(String department) {
            return byDept.get(key(null, department));
        }
    }

    /** Lý do nhân viên không có công thức (hiện trên trang tính lương / giải trình) */
    public static String noFormulaReason(SalaryConfig config) {
        if (config == null || config.getFormula() == null) {
            return "chưa chọn mẫu công thức lương, phòng ban cũng chưa có mẫu mặc định";
        }
        return "mẫu '" + templateName(config.getFormula()) + "' đang tắt";
    }

    // =====================================================================
    // KIỂM TRA HỢP LỆ
    // =====================================================================

    private static boolean sameOwner(SalaryFormula a, SalaryFormula b) {
        Long x = a.getUnit() == null ? null : a.getUnit().getId();
        Long y = b.getUnit() == null ? null : b.getUnit().getId();
        return java.util.Objects.equals(x, y);
    }

    /** Đơn vị sở hữu mẫu mới tạo: đơn vị đang xem (quản trị hệ thống xem "Tất cả" = mẫu dùng chung) */
    public com.example.hrm.entity.CompanyUnit ownerForNew() {
        return unitScope == null ? null : unitScope.scopeUnit();
    }

    public List<String> validate(SalaryFormula f) {

        List<String> errors = new ArrayList<>();

        if (f.getName() == null || f.getName().isBlank()) {
            errors.add("Chưa nhập tên mẫu");
        } else {
            for (SalaryFormula other : formulaRepository.findAll()) {
                // ĐƠN VỊ: trùng tên với mẫu dùng chung hoặc mẫu cùng đơn vị (mẫu dùng chung: so với mọi mẫu)
                boolean sameView = f.getUnit() == null || other.getUnit() == null || sameOwner(f, other);
                if (sameView && !other.getId().equals(f.getId()) && templateName(other).equalsIgnoreCase(f.getName().trim())) {
                    errors.add("Đã có mẫu tên '" + f.getName().trim() + "'");
                }
            }
        }

        if (f.getDepartment() != null && !f.getDepartment().isBlank()) {
            for (SalaryFormula other : formulaRepository.findAll()) {
                // ĐƠN VỊ: mỗi đơn vị 1 mẫu mặc định / phòng; mẫu riêng đơn vị được ưu tiên hơn mẫu dùng chung
                if (sameOwner(f, other) && !other.getId().equals(f.getId()) && other.activeOrDefault()
                        && normDept(other.getDepartment()).equals(normDept(f.getDepartment()))) {
                    errors.add("Phòng '" + f.getDepartment().trim() + "' đã dùng mẫu '" + templateName(other)
                            + "' làm mặc định. Mỗi phòng chỉ 1 mẫu mặc định: bỏ chọn phòng ở mẫu kia hoặc tắt mẫu kia trước");
                }
            }
        }

        String src = "Mẫu " + (f.getName() == null ? "" : f.getName().trim());
        try {
            DefinitionParser.parse(f.getThamSo(), src + " - Tham số");
        } catch (FormulaException e) {
            errors.add(e.getMessage());
        }
        try {
            DefinitionParser.parse(f.getBienTrungGian(), src + " - Biến trung gian");
        } catch (FormulaException e) {
            errors.add(e.getMessage());
        }
        for (SalaryVariables.Item it : SalaryVariables.ITEMS) {
            String expr = f.expression(it.code());
            if (expr == null && it.optional()) {
                continue;   // hạng mục thưởng để trống = lấy số nhập
            }
            if (expr == null) {
                errors.add(it.label() + " (" + it.code() + "): chưa nhập công thức. Bấm \"Mẫu\" hoặc \"Điền mẫu cho các ô trống\"");
                continue;
            }
            try {
                FormulaEngine.parse(expr);
            } catch (FormulaException e) {
                errors.add(it.label() + ": " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            return errors;
        }

        try {
            errors.addAll(checkDependencies(new FormulaChain().template(f)));
        } catch (FormulaException e) {
            errors.add(e.getMessage());
        }
        return errors;
    }

    /**
     * Mọi biến trong công thức phải được khai báo TRƯỚC nó:
     * tham số -> biến hệ thống -> biến trung gian -> hạng mục lương (theo thứ tự).
     */
    public List<String> checkDependencies(FormulaChain chain) {

        List<String> errors = new ArrayList<>();
        Set<String> known = new HashSet<>(SalaryVariables.PARAM_KEYS);

        for (Definition d : chain.params()) {
            String k = Vars.key(d.name());
            if (SalaryVariables.INPUT_KEYS.contains(k) || SalaryVariables.ITEM_KEYS.contains(k)) {
                errors.add(d.where() + ": tên này trùng biến hệ thống / hạng mục lương, hãy đặt tên khác");
                continue;
            }
            checkVars(d.expression(), known, d.where(), errors,
                    "tham số chỉ được dùng số và các tham số khai báo phía trên");
            known.add(k);
        }

        known.addAll(SalaryVariables.INPUT_KEYS);

        for (Definition d : chain.intermediates()) {
            String k = Vars.key(d.name());
            if (SalaryVariables.ITEM_KEYS.contains(k)) {
                errors.add(d.where() + ": trùng tên hạng mục lương, muốn đổi cách tính thì sửa ở ô hạng mục đó");
                continue;
            }
            checkVars(d.expression(), known, d.where(), errors,
                    "biến trung gian chỉ dùng được tham số, biến hệ thống và biến trung gian phía trên");
            known.add(k);
        }

        for (SalaryVariables.Item it : SalaryVariables.ITEMS) {
            FormulaChain.Resolved r = chain.item(it.code());
            if (r != null) {
                checkVars(r.expression(), known, "[" + r.source() + "] " + it.label(), errors,
                        "hạng mục chỉ dùng được các hạng mục đứng TRƯỚC nó trong danh sách");
            }
            known.add(Vars.key(it.code()));
        }

        return errors;
    }

    private void checkVars(String expr, Set<String> known, String where, List<String> errors, String hint) {
        for (String v : FormulaEngine.variablesOf(expr)) {
            if (!known.contains(Vars.key(v))) {
                errors.add(where + ": biến '" + v + "' chưa được khai báo (" + hint
                        + "; tỉ lệ riêng từng nhân viên cần khai báo giá trị mặc định ở ô Tham số)");
            }
        }
    }

    /**
     * Kiểm tra 1 giá trị tỉ lệ riêng nhân viên nhập: phải tính được ra số,
     * chỉ dùng số, % và tham số (không dùng dữ liệu chấm công).
     * @return null nếu hợp lệ, không thì thông báo lỗi
     */
    public static String checkRate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Set<String> vars = FormulaEngine.variablesOf(value);
            for (String v : vars) {
                if (!SalaryVariables.PARAM_KEYS.contains(Vars.key(v))) {
                    return "chỉ nhập số hoặc % (VD 1.5%, 2, 0.85)";
                }
            }
            return null;
        } catch (FormulaException e) {
            return e.getMessage();
        }
    }

    // =====================================================================
    // LƯU / NHÂN BẢN / XOÁ
    // =====================================================================

    @Transactional
    public SalaryFormula save(SalaryFormula f) {
        if (f.getScope() == null) {
            f.setScope(FormulaScope.DEFAULT);
        }
        if (f.getName() != null) {
            f.setName(f.getName().trim());
        }
        f.setDepartment(f.getDepartment() == null || f.getDepartment().isBlank() ? null : f.getDepartment().trim());
        f.getItems().entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBlank());
        f.getItems().replaceAll((k, v) -> v.trim());
        f.setUpdatedAt(LocalDateTime.now());
        if (f.getId() == null && f.getUnit() == null && unitScope != null) {
            f.setUnit(unitScope.scopeUnit());   // mẫu mới thuộc đơn vị đang xem
        }
        return formulaRepository.save(f);
    }

    /** Tạo mẫu mới chép toàn bộ nội dung từ mẫu có sẵn */
    @Transactional
    public SalaryFormula copy(Long sourceId, String newName) {
        SalaryFormula src = formulaRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy mẫu"));
        SalaryFormula f = new SalaryFormula();
        f.setScope(FormulaScope.DEFAULT);
        f.setName(uniqueName(newName != null && !newName.isBlank() ? newName.trim() : templateName(src) + " (bản sao)"));
        f.setThamSo(src.getThamSo());
        f.setBienTrungGian(src.getBienTrungGian());
        f.getItems().putAll(src.getItems());
        f.setGhiChu(src.getGhiChu());
        f.setActive(true);
        f.setUpdatedAt(LocalDateTime.now());
        // ĐƠN VỊ: bản sao thuộc đơn vị đang xem (quản trị hệ thống xem "Tất cả" = mẫu dùng chung)
        f.setUnit(unitScope == null ? src.getUnit() : unitScope.scopeUnit());
        return formulaRepository.save(f);
    }

    private String uniqueName(String base) {
        Set<String> names = new HashSet<>();
        formulaRepository.findAll().forEach(x -> names.add(templateName(x).toLowerCase(Locale.ROOT)));
        String name = base;
        int i = 2;
        while (names.contains(name.toLowerCase(Locale.ROOT))) {
            name = base + " " + i++;
        }
        return name;
    }

    /** Không cho xoá mẫu đang có nhân viên dùng */
    @Transactional
    public String delete(Long id) {
        long used = configRepository.findAll().stream()   // đếm MỌI đơn vị: mẫu dùng chung có thể đang được đơn vị khác dùng
                .filter(c -> c.getFormula() != null && id.equals(c.getFormula().getId())).count();
        if (used > 0) {
            return "Không xoá được: còn " + used + " nhân viên đang dùng mẫu này. Hãy chuyển họ sang mẫu khác trước "
                    + "(hoặc tắt mẫu).";
        }
        formulaRepository.deleteById(id);
        return null;
    }
}