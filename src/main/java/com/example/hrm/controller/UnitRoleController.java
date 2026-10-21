package com.example.hrm.controller;

import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.entity.User;
import com.example.hrm.repository.CompanyUnitRepository;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.service.AttendanceService;
import com.example.hrm.service.UnitScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * CHỨC VỤ & PHÒNG BAN (HCNS của từng đơn vị tự chia):
 *   - Người duyệt cấp đơn vị: Giám đốc, Giám đốc dịch vụ, Kế toán trưởng (chọn trong nhân viên đơn vị mình)
 *   - Từng nhân viên: phòng ban + Trưởng phòng / Nhân viên
 * Chỉ thấy và sửa người của đơn vị mình. Đổi xong, đơn xác nhận công đang chờ tự chuyển sang người duyệt mới.
 *
 * Ai vào được: phòng HCNS / Hành chính / Nhân sự, quản trị đơn vị (role ADMIN).
 * Quản trị hệ thống không làm việc này (SystemAdminGuard chặn), trừ khi tắt hrm.system-admin.restricted.
 */
@Controller
@RequestMapping("/hr/roles")
public class UnitRoleController {

    static final String MANAGER = "MANAGER";
    static final String STAFF = "STAFF";

    private final UnitScope unitScope;
    private final UserRepository userRepository;
    private final CompanyUnitRepository unitRepository;
    private final AttendanceService attendanceService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.repository.UnitDepartmentRepository departmentRepository;   // phòng ban + người duyệt

    @PersistenceContext
    private EntityManager em;

    public UnitRoleController(UnitScope unitScope, UserRepository userRepository,
                              CompanyUnitRepository unitRepository, AttendanceService attendanceService) {
        this.unitScope = unitScope;
        this.userRepository = userRepository;
        this.unitRepository = unitRepository;
        this.attendanceService = attendanceService;
    }

    boolean canEdit(User u) {
        return u != null && (NewsController.isHr(u) || UnitScope.isAdmin(u) || unitScope.isGlobalAdmin(u));
    }

    /** Đơn vị làm việc: quản trị hệ thống = đơn vị đang xem; người khác = đơn vị mình (bản mới nhất trong DB) */
    CompanyUnit target(User me) {
        if (unitScope.isGlobalAdmin(me)) {
            Long id = unitScope.scopeUnitId();
            return id == null ? null : unitRepository.findById(id).orElse(null);
        }
        CompanyUnit u = unitScope.unitOf(me);
        return u == null ? null : unitRepository.findById(u.getId()).orElse(null);
    }

    /** Nhân viên của đơn vị (bỏ tài khoản quản trị hệ thống và tài khoản không có mã NV) */
    List<User> staffOf(CompanyUnit unit) {
        List<User> out = new ArrayList<>();
        for (User u : userRepository.findAll()) {
            if (!Objects.equals(unitScope.unitKey(u), unit.getId())) {
                continue;
            }
            if (unitScope.isGlobalAdmin(u) || u.getEmployeeNo() == null || u.getEmployeeNo().isBlank()) {
                continue;
            }
            out.add(u);
        }
        out.sort(Comparator.comparing((User u) -> u.getDepartment() == null ? "~" : u.getDepartment())
                .thenComparing(u -> !Boolean.TRUE.equals(u.getDepartmentManager()))
                .thenComparing(u -> u.getFullName() == null ? "" : u.getFullName()));
        return out;
    }

    // =====================================================================

    @GetMapping
    public String page(Model model) {
        User me = unitScope.currentUser();
        if (me == null) {
            return "redirect:/login";
        }
        if (!canEdit(me)) {
            return "redirect:/?error=no-permission";
        }
        CompanyUnit unit = target(me);
        if (unit == null) {
            model.addAttribute("noUnit", true);
            return "hr-roles";
        }
        List<User> staff = staffOf(unit);

        TreeSet<String> depts = new TreeSet<>();
        Map<String, List<User>> heads = new TreeMap<>();
        for (User u : staff) {
            String d = u.getDepartment() == null ? "" : u.getDepartment().trim();
            if (!d.isEmpty()) {
                depts.add(d);
                if (Boolean.TRUE.equals(u.getDepartmentManager())) {
                    heads.computeIfAbsent(d, k -> new ArrayList<>()).add(u);
                }
            }
        }

        Map<String, String> leaderOf = new LinkedHashMap<>();   // mã NV -> "Giám đốc" ...
        put(leaderOf, unit.getDirectorNo(), "Giám đốc");
        put(leaderOf, unit.getServiceDirectorNo(), "GĐ dịch vụ");
        put(leaderOf, unit.getChiefAccountantNo(), "Kế toán trưởng");

        List<String> warnings = new ArrayList<>();
        if (unit.getDirectorNo() == null || unit.getDirectorNo().isBlank()) {
            warnings.add(unit.isLegacyUnit()
                    ? "Chưa chọn Giám đốc: đơn đang về mã cũ 99999."
                    : "Chưa chọn Giám đốc: đơn của trưởng phòng và phòng chưa có trưởng sẽ không có người duyệt.");
        }
        for (Map.Entry<String, List<User>> e : heads.entrySet()) {
            if (e.getValue().size() > 1) {
                warnings.add("Phòng " + e.getKey() + " có " + e.getValue().size()
                        + " trưởng phòng: đơn chỉ đến người đầu tiên.");
            }
        }
        for (String d : depts) {
            if (!heads.containsKey(d) && !d.startsWith("Kinh Doanh") && !d.contains("Dịch Vụ") && !d.contains("Kế Toán")) {
                warnings.add("Phòng " + d + " chưa có trưởng phòng: đơn lên Giám đốc.");
            }
        }

        // PHÒNG BAN + NGƯỜI DUYỆT (HCNS cấu hình): phòng đã khai + phòng đang có người
        Map<String, com.example.hrm.entity.UnitDepartment> cfg = new LinkedHashMap<>();
        for (com.example.hrm.entity.UnitDepartment d : departmentRepository.findByUnitIdOrderByNameAsc(unit.getId())) {
            cfg.put(com.example.hrm.entity.UnitDepartment.norm(d.getName()), d);
            depts.add(d.getName().trim());
        }
        List<Map<String, Object>> deptRows = new ArrayList<>();
        for (String d : depts) {
            String k = com.example.hrm.entity.UnitDepartment.norm(d);
            com.example.hrm.entity.UnitDepartment c = cfg.get(k);
            long count = staff.stream().filter(s -> com.example.hrm.entity.UnitDepartment.norm(s.getDepartment()).equals(k)).count();
            List<User> hs = heads.getOrDefault(d, List.of());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", d);
            r.put("count", count);
            r.put("heads", hs.stream().map(h -> h.getFullName() + " (" + h.getEmployeeNo() + ")").toList());
            r.put("approverNo", c == null ? null : c.getApproverNo());
            r.put("defaultRule", d.contains("Dịch Vụ") ? "GĐ dịch vụ" : d.contains("Kế Toán") ? "Kế toán trưởng"
                    : hs.isEmpty() ? "Giám đốc (phòng chưa có trưởng)" : "Trưởng phòng " + hs.get(0).getFullName());
            deptRows.add(r);
        }

        model.addAttribute("unit", unit);
        model.addAttribute("staff", staff);
        model.addAttribute("depts", depts);
        model.addAttribute("deptRows", deptRows);
        model.addAttribute("leaderOf", leaderOf);
        model.addAttribute("warnings", warnings);
        model.addAttribute("managerCount", heads.values().stream().mapToInt(List::size).sum());
        return "hr-roles";
    }

    private static void put(Map<String, String> m, String no, String label) {
        if (no != null && !no.isBlank()) {
            m.merge(no.trim(), label, (a, b) -> a + ", " + b);
        }
    }

    // =====================================================================
    // PHÒNG BAN + AI DUYỆT PHÒNG NÀO (HCNS cấu hình)
    // =====================================================================

    @PostMapping("/departments")
    @Transactional
    public String departments(@RequestParam Map<String, String> form, RedirectAttributes ra) {
        User me = unitScope.currentUser();
        if (!canEdit(me)) {
            return "redirect:/?error=no-permission";
        }
        CompanyUnit unit = target(me);
        if (unit == null) {
            return "redirect:/hr/roles";
        }
        List<User> staff = staffOf(unit);
        Map<String, com.example.hrm.entity.UnitDepartment> cfg = new LinkedHashMap<>();
        for (com.example.hrm.entity.UnitDepartment d : departmentRepository.findByUnitIdOrderByNameAsc(unit.getId())) {
            cfg.put(com.example.hrm.entity.UnitDepartment.norm(d.getName()), d);
        }
        String who = me.getFullName() != null ? me.getFullName() : me.getUsername();
        List<String> errors = new ArrayList<>();
        int changed = 0;

        // các dòng phòng: name_i, approver_i, del_i
        for (int i = 0; i < 500 && form.containsKey("name_" + i); i++) {
            String name = clean(form.get("name_" + i));
            if (name.isEmpty()) {
                continue;
            }
            String k = com.example.hrm.entity.UnitDepartment.norm(name);
            com.example.hrm.entity.UnitDepartment c = cfg.get(k);
            long inDept = staff.stream().filter(s -> com.example.hrm.entity.UnitDepartment.norm(s.getDepartment()).equals(k)).count();
            if (form.containsKey("del_" + i)) {
                if (inDept > 0) {
                    errors.add("Phòng " + name + " còn " + inDept + " người, chuyển họ sang phòng khác trước khi xoá");
                } else if (c != null) {
                    departmentRepository.delete(c);
                    changed++;
                }
                continue;
            }
            String appr = pick(form.get("approver_" + i), staff, "Người duyệt phòng " + name, errors);
            String old = c == null ? null : emptyToNull(c.getApproverNo());
            if (c == null && appr == null) {
                continue;   // phòng đang có người, chưa giao ai riêng: giữ quy tắc cũ, không cần lưu
            }
            if (c != null && java.util.Objects.equals(old, appr)) {
                continue;
            }
            if (c == null) {
                c = new com.example.hrm.entity.UnitDepartment();
                c.setUnitId(unit.getId());
                c.setName(name);
            }
            c.setApproverNo(appr);
            c.setUpdatedBy(who);
            c.setUpdatedAt(java.time.LocalDateTime.now());
            departmentRepository.save(c);
            changed++;
        }

        // thêm phòng mới
        String newName = clean(form.get("newDept"));
        if (!newName.isEmpty()) {
            if (cfg.containsKey(com.example.hrm.entity.UnitDepartment.norm(newName))
                    || staff.stream().anyMatch(s -> com.example.hrm.entity.UnitDepartment.norm(s.getDepartment())
                    .equals(com.example.hrm.entity.UnitDepartment.norm(newName)))) {
                errors.add("Phòng " + newName + " đã có");
            } else {
                com.example.hrm.entity.UnitDepartment c = new com.example.hrm.entity.UnitDepartment();
                c.setUnitId(unit.getId());
                c.setName(newName);
                c.setApproverNo(pick(form.get("newApprover"), staff, "Người duyệt phòng " + newName, errors));
                c.setUpdatedBy(who);
                c.setUpdatedAt(java.time.LocalDateTime.now());
                departmentRepository.save(c);
                changed++;
            }
        }

        int moved = changed > 0 ? attendanceService.rerouteOpenConfirmations(staff) : 0;
        if (!errors.isEmpty()) {
            ra.addFlashAttribute("error", String.join(" ", errors));
        }
        ra.addFlashAttribute("message", changed == 0 ? "Không có thay đổi phòng ban"
                : "Đã lưu phòng ban & người duyệt (" + changed + " thay đổi)"
                + (moved > 0 ? ", " + moved + " đơn đang chờ đã chuyển sang người duyệt mới" : ""));
        return "redirect:/hr/roles";
    }

    // =====================================================================
    // NGƯỜI DUYỆT CẤP ĐƠN VỊ
    // =====================================================================

    @PostMapping("/leaders")
    @Transactional
    public String leaders(@RequestParam Map<String, String> form, RedirectAttributes ra) {
        User me = unitScope.currentUser();
        if (!canEdit(me)) {
            return "redirect:/?error=no-permission";
        }
        CompanyUnit unit = target(me);
        if (unit == null) {
            return "redirect:/hr/roles";
        }
        List<User> staff = staffOf(unit);
        List<String> errors = new ArrayList<>();
        String director = pick(form.get("directorNo"), staff, "Giám đốc", errors);
        String service = pick(form.get("serviceDirectorNo"), staff, "Giám đốc dịch vụ", errors);
        String chief = pick(form.get("chiefAccountantNo"), staff, "Kế toán trưởng", errors);
        if (!errors.isEmpty()) {
            ra.addFlashAttribute("error", String.join(" ", errors));
            return "redirect:/hr/roles";
        }
        unit.setDirectorNo(director);
        unit.setServiceDirectorNo(service);
        unit.setChiefAccountantNo(chief);
        unitRepository.save(unit);
        unitScope.refresh();

        int moved = attendanceService.rerouteOpenConfirmations(staff);
        ra.addFlashAttribute("message", "Đã lưu người duyệt của " + unit.getLabel()
                + (moved > 0 ? ". " + moved + " đơn đang chờ đã chuyển sang người duyệt mới." : "."));
        return "redirect:/hr/roles";
    }

    /** Ô chọn trống = không khai (GĐ dịch vụ / Kế toán trưởng trống thì Giám đốc duyệt) */
    private static String pick(String raw, List<User> staff, String role, List<String> errors) {
        String no = raw == null ? "" : raw.trim();
        if (no.isEmpty()) {
            return null;
        }
        boolean mine = staff.stream().anyMatch(u -> no.equalsIgnoreCase(u.getEmployeeNo()));
        if (!mine) {
            errors.add(role + " " + no + " không phải nhân viên đơn vị này.");
            return null;
        }
        return no;
    }

    // =====================================================================
    // PHÒNG BAN + TRƯỞNG PHÒNG / NHÂN VIÊN
    // =====================================================================

    @PostMapping("/save")
    @Transactional
    public String save(@RequestParam Map<String, String> form, RedirectAttributes ra) {
        User me = unitScope.currentUser();
        if (!canEdit(me)) {
            return "redirect:/?error=no-permission";
        }
        CompanyUnit unit = target(me);
        if (unit == null) {
            return "redirect:/hr/roles";
        }
        List<User> staff = staffOf(unit);
        int changed = 0;
        for (User u : staff) {
            String dk = "dept_" + u.getId();
            String pk = "pos_" + u.getId();
            if (!form.containsKey(dk) && !form.containsKey(pk)) {
                continue;   // dòng bị lọc ẩn trên trang: không gửi lên, giữ nguyên
            }
            String dept = form.containsKey(dk) ? clean(form.get(dk)) : u.getDepartment();
            boolean manager = form.containsKey(pk) ? MANAGER.equals(form.get(pk)) : Boolean.TRUE.equals(u.getDepartmentManager());
            if (Objects.equals(emptyToNull(dept), emptyToNull(u.getDepartment()))
                    && manager == Boolean.TRUE.equals(u.getDepartmentManager())) {
                continue;
            }
            em.createQuery("UPDATE User u SET u.department = :d, u.departmentManager = :m WHERE u.id = :id")
                    .setParameter("d", emptyToNull(dept))
                    .setParameter("m", manager)
                    .setParameter("id", u.getId())
                    .executeUpdate();
            changed++;
        }
        em.flush();
        em.clear();

        int moved = changed > 0 ? attendanceService.rerouteOpenConfirmations(staffOf(unit)) : 0;
        ra.addFlashAttribute("message", changed == 0 ? "Không có thay đổi"
                : "Đã lưu " + changed + " người" + (moved > 0 ? ", " + moved + " đơn đang chờ đã chuyển sang người duyệt mới" : ""));
        return "redirect:/hr/roles";
    }

    private static String clean(String s) {
        String t = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        return t.length() > 100 ? t.substring(0, 100) : t;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
