package com.example.hrm.controller;

import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.entity.User;
import com.example.hrm.repository.CompanyUnitRepository;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.service.UnitScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * TÀI KHOẢN & CHIA ĐƠN VỊ (chỉ quản trị hệ thống).
 *   - Tạo tài khoản: Quản trị hệ thống (SUPER_ADMIN) / Quản trị đơn vị (ADMIN) / Người dùng (USER)
 *   - Đổi loại tài khoản, chuyển người sang đơn vị khác (từng người hoặc hàng loạt), đặt lại mật khẩu
 * KHÔNG chia Giám đốc / trưởng phòng / phòng ban ở đây: việc đó HCNS từng đơn vị làm ở /hr/roles.
 *
 * Cập nhật bằng câu UPDATE từng cột, không save(User) cả bản ghi, để không đụng các cột UNIQUE
 * (salary_code, employeeNo...) đang trống ở dữ liệu cũ.
 */
@Controller
@RequestMapping("/system/accounts")
public class SystemAccountController {

    static final Map<String, String> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("USER", "Người dùng");
        TYPES.put("ADMIN", "Quản trị đơn vị");
        TYPES.put("SUPER_ADMIN", "Quản trị hệ thống");
    }
    private static final int MAX_ROWS = 500;

    private final UserRepository userRepository;
    private final CompanyUnitRepository unitRepository;
    private final UnitScope unitScope;
    private final PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager em;

    public SystemAccountController(UserRepository userRepository, CompanyUnitRepository unitRepository,
                                   UnitScope unitScope, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.unitRepository = unitRepository;
        this.unitScope = unitScope;
        this.passwordEncoder = passwordEncoder;
    }

    private User me(Principal principal) {
        User u = unitScope.find(principal == null ? null : principal.getName());
        return unitScope.isGlobalAdmin(u) ? u : null;
    }

    // =====================================================================

    @GetMapping
    public String list(@RequestParam(required = false) String unit, @RequestParam(required = false) String q,
                       Principal principal, Model model) {
        if (me(principal) == null) {
            return "redirect:/?error=no-permission";
        }
        List<CompanyUnit> units = unitRepository.findAllByOrderByCodeAsc();
        Long legacy = unitScope.legacyId();
        List<User> all = userRepository.findAll();

        Map<Long, Long> counts = new LinkedHashMap<>();
        long unassigned = 0;
        for (User u : all) {
            if (u.getUnit() == null) {
                unassigned++;
            } else {
                counts.merge(u.getUnit().getId(), 1L, Long::sum);
            }
        }

        String filter = unit == null ? "" : unit.trim();
        String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<User> rows = new ArrayList<>();
        for (User u : all) {
            if ("none".equals(filter) && u.getUnit() != null) {
                continue;
            }
            Long id = CompanyUnitController.parseLong(filter);
            if (id != null && (u.getUnit() == null || !id.equals(u.getUnit().getId()))) {
                continue;
            }
            if (!term.isEmpty() && !(contains(u.getUsername(), term) || contains(u.getFullName(), term)
                    || contains(u.getEmployeeNo(), term) || contains(u.getDepartment(), term))) {
                continue;
            }
            rows.add(u);
        }
        rows.sort(Comparator.comparing((User u) -> u.getUnit() == null ? "" : u.getUnit().getCode())
                .thenComparing(u -> u.getEmployeeNo() == null ? "" : u.getEmployeeNo()));
        boolean truncated = rows.size() > MAX_ROWS;
        if (truncated) {
            rows = rows.subList(0, MAX_ROWS);
        }

        Map<Long, String> typeOf = new LinkedHashMap<>();
        for (User u : rows) {
            typeOf.put(u.getId(), typeOf(u));
        }

        model.addAttribute("units", units);
        model.addAttribute("counts", counts);
        model.addAttribute("unassigned", unassigned);
        model.addAttribute("legacyId", legacy);
        model.addAttribute("rows", rows);
        model.addAttribute("typeOf", typeOf);
        model.addAttribute("types", TYPES);
        model.addAttribute("truncated", truncated);
        model.addAttribute("total", all.size());
        model.addAttribute("unitFilter", filter);
        model.addAttribute("q", q == null ? "" : q.trim());
        return "system-accounts";
    }

    /** Loại tài khoản hiển thị (admin cũ theo username / phòng ADMIN vẫn là quản trị) */
    String typeOf(User u) {
        if (unitScope.isGlobalAdmin(u)) {
            return "SUPER_ADMIN";
        }
        return UnitScope.isAdmin(u) ? "ADMIN" : "USER";
    }

    // =====================================================================

    @PostMapping("/create")
    @Transactional
    public String create(@RequestParam Map<String, String> form, Principal principal, RedirectAttributes ra) {
        if (me(principal) == null) {
            return "redirect:/?error=no-permission";
        }
        String username = text(form.get("username"));
        String fullName = text(form.get("fullName"));
        String employeeNo = text(form.get("employeeNo"));
        String password = form.get("password") == null ? "" : form.get("password");
        String type = text(form.get("type")).toUpperCase(Locale.ROOT);
        Long unitId = CompanyUnitController.parseLong(form.get("unitId"));
        CompanyUnit unit = unitId == null ? null : unitRepository.findById(unitId).orElse(null);
        // PHÒNG: "HCNS" = tài khoản Hành chính nhân sự của đơn vị (vào được Chức vụ & phòng ban, Lịch làm việc...)
        String department = text(form.get("department"));
        if (department.length() > 100) {
            department = department.substring(0, 100);
        }

        List<String> errors = new ArrayList<>();
        if (!username.matches("[A-Za-z0-9._@-]{3,50}")) {
            errors.add("Tên đăng nhập 3-50 ký tự: chữ không dấu, số, dấu . _ - @");
        } else if (userRepository.findByUsername(username).isPresent()) {
            errors.add("Tên đăng nhập " + username + " đã có");
        }
        if (!employeeNo.isEmpty() && userRepository.findByEmployeeNo(employeeNo).isPresent()) {
            errors.add("Mã nhân viên " + employeeNo + " đã có");
        }
        if (password.length() < 6) {
            errors.add("Mật khẩu ít nhất 6 ký tự");
        }
        if (!TYPES.containsKey(type)) {
            errors.add("Chọn loại tài khoản");
        }
        if (!"SUPER_ADMIN".equals(type) && unit == null) {
            errors.add("Chọn đơn vị cho tài khoản");
        }
        if (!errors.isEmpty()) {
            ra.addFlashAttribute("error", String.join(" · ", errors));
            ra.addFlashAttribute("draft", form);
            return "redirect:/system/accounts";
        }

        User u = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .fullName(fullName.isEmpty() ? username : fullName)
                .employeeNo(employeeNo.isEmpty() ? null : employeeNo)
                .role(type)
                .unit(unit)
                .department(department.isEmpty() ? null : department)
                .build();
        userRepository.save(u);
        ra.addFlashAttribute("message", "Đã tạo tài khoản " + username + " (" + TYPES.get(type)
                + (department.isEmpty() ? "" : " · phòng " + department)
                + (unit != null ? " · " + unit.getCode() : "") + ")"
                + (NewsController.isHr(u) ? ". Người này đăng nhập sẽ có menu Chức vụ & phòng ban để tạo phòng, chia trưởng phòng / Giám đốc." : ""));
        return back(form);
    }

    // =====================================================================

    @PostMapping("/{id}/update")
    @Transactional
    public String update(@PathVariable Long id, @RequestParam Map<String, String> form, Principal principal,
                         RedirectAttributes ra) {
        User me = me(principal);
        if (me == null) {
            return "redirect:/?error=no-permission";
        }
        User u = userRepository.findById(id).orElse(null);
        if (u == null) {
            ra.addFlashAttribute("error", "Không tìm thấy tài khoản");
            return back(form);
        }
        String type = text(form.get("type")).toUpperCase(Locale.ROOT);
        if (!TYPES.containsKey(type)) {
            type = typeOf(u);
        }
        if (Objects.equals(me.getId(), u.getId()) && !"SUPER_ADMIN".equals(type)) {
            ra.addFlashAttribute("error", "Không tự hạ quyền tài khoản đang đăng nhập");
            return back(form);
        }
        String unitRaw = text(form.get("unitId"));
        Long unitId = CompanyUnitController.parseLong(unitRaw);
        CompanyUnit unit = unitId == null ? null : unitRepository.findById(unitId).orElse(null);
        if (!"SUPER_ADMIN".equals(type) && unit == null) {
            ra.addFlashAttribute("error", u.getUsername() + ": chọn đơn vị");
            return back(form);
        }

        em.createQuery("UPDATE User u SET u.role = :r, u.unit = :unit WHERE u.id = :id")
                .setParameter("r", type)
                .setParameter("unit", unit)
                .setParameter("id", id)
                .executeUpdate();
        ra.addFlashAttribute("message", "Đã lưu " + u.getUsername() + ": " + TYPES.get(type)
                + (unit != null ? " · " + unit.getCode() : ""));
        return back(form);
    }

    @PostMapping("/move")
    @Transactional
    public String move(@RequestParam(name = "ids", required = false) List<Long> ids,
                       @RequestParam Map<String, String> form, Principal principal, RedirectAttributes ra) {
        if (me(principal) == null) {
            return "redirect:/?error=no-permission";
        }
        Long unitId = CompanyUnitController.parseLong(form.get("toUnit"));
        CompanyUnit unit = unitId == null ? null : unitRepository.findById(unitId).orElse(null);
        if (ids == null || ids.isEmpty() || unit == null) {
            ra.addFlashAttribute("error", "Chọn người và đơn vị cần chuyển tới");
            return back(form);
        }
        int n = em.createQuery("UPDATE User u SET u.unit = :unit WHERE u.id IN :ids")
                .setParameter("unit", unit)
                .setParameter("ids", ids)
                .executeUpdate();
        ra.addFlashAttribute("message", "Đã chuyển " + n + " người sang " + unit.getLabel()
                + ". HCNS đơn vị " + unit.getCode() + " chia phòng ban / chức vụ ở trang Chức vụ & phòng ban.");
        return back(form);
    }

    @PostMapping("/{id}/password")
    @Transactional
    public String password(@PathVariable Long id, @RequestParam Map<String, String> form, Principal principal,
                           RedirectAttributes ra) {
        if (me(principal) == null) {
            return "redirect:/?error=no-permission";
        }
        String pw = form.get("newPassword") == null ? "" : form.get("newPassword");
        if (pw.length() < 6) {
            ra.addFlashAttribute("error", "Mật khẩu mới ít nhất 6 ký tự");
            return back(form);
        }
        int n = em.createQuery("UPDATE User u SET u.password = :p WHERE u.id = :id")
                .setParameter("p", passwordEncoder.encode(pw))
                .setParameter("id", id)
                .executeUpdate();
        ra.addFlashAttribute(n > 0 ? "message" : "error", n > 0 ? "Đã đặt lại mật khẩu" : "Không tìm thấy tài khoản");
        return back(form);
    }

    // =====================================================================

    /** Quay lại đúng bộ lọc đang xem (chỉ nhận giá trị an toàn) */
    private static String back(Map<String, String> form) {
        String unit = text(form.get("backUnit"));
        String q = text(form.get("backQ"));
        StringBuilder b = new StringBuilder("redirect:/system/accounts");
        String sep = "?";
        if (unit.matches("none|\\d{1,10}")) {
            b.append(sep).append("unit=").append(unit);
            sep = "&";
        }
        if (q.matches("[\\p{L}\\p{N} ._@-]{1,50}")) {
            b.append(sep).append("q=").append(java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    private static boolean contains(String s, String term) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(term);
    }

    private static String text(String s) {
        return s == null ? "" : s.trim();
    }
}
