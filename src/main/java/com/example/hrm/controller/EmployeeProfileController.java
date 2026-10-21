package com.example.hrm.controller;

import com.example.hrm.entity.EmployeeDocument;
import com.example.hrm.entity.EmployeeProfile;
import com.example.hrm.entity.User;
import com.example.hrm.repository.EmployeeDocumentRepository;
import com.example.hrm.repository.EmployeeProfileRepository;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.service.EmployeeFileStorage;
import com.example.hrm.service.NewsFileStorage;
import com.example.hrm.service.UnitScope;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * HỒ SƠ NHÂN VIÊN, CV, GIẤY TỜ
 *   /hr/employees           HCNS / quản trị đơn vị / Ban giám đốc: danh sách nhân viên đơn vị mình (độ đầy đủ hồ sơ, HĐ sắp hết hạn)
 *   /hr/employees/{id}      xem / sửa hồ sơ 1 người
 *   /my-profile             nhân viên xem hồ sơ của mình, tự cập nhật liên hệ, ngân hàng, CV, tải file
 *
 * Quyền:
 *   - HCNS / quản trị ĐƠN VỊ (cùng đơn vị): sửa toàn bộ, tải / xoá mọi file.
 *   - Chính chủ: sửa thông tin cá nhân, giấy tờ, liên hệ khẩn cấp, học vấn & CV; KHÔNG sửa mục Công việc & hợp đồng,
 *     không thấy Ghi chú HCNS; chỉ xoá file mình tải lên.
 *   - Giám đốc đơn vị: chỉ xem, không thấy mục giấy tờ / ngân hàng (nhạy cảm).
 */
@Controller
public class EmployeeProfileController {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MAX_FILES_PER_UPLOAD = 10;

    /** Mô tả 1 ô của hồ sơ (form được vẽ theo danh sách này) */
    public record Field(String key, String label, String type, String group, boolean selfEdit,
                        List<String> options, String hint) {
        public boolean isWide() {
            return "textarea".equals(type) || key.endsWith("Address");
        }
    }

    public record Group(String key, String title, String icon, boolean sensitive, boolean hrOnly) {
    }

    static final List<Group> GROUPS = List.of(
            new Group("personal", "Thông tin cá nhân", "fa-regular fa-id-card", false, false),
            new Group("papers", "Giấy tờ & tài khoản", "fa-solid fa-building-columns", true, false),
            new Group("emergency", "Liên hệ khẩn cấp", "fa-solid fa-phone-volume", false, false),
            new Group("work", "Công việc & hợp đồng", "fa-solid fa-briefcase", false, false),
            new Group("cv", "Học vấn & CV", "fa-solid fa-graduation-cap", false, false),
            new Group("hr", "Ghi chú của HCNS", "fa-regular fa-note-sticky", false, true));

    static final List<Field> FIELDS = List.of(
            new Field("gender", "Giới tính", "select", "personal", true, List.of("Nam", "Nữ", "Khác"), null),
            new Field("birthDate", "Ngày sinh", "date", "personal", true, null, null),
            new Field("birthPlace", "Nơi sinh / quê quán", "text", "personal", true, null, null),
            new Field("ethnicity", "Dân tộc", "text", "personal", true, null, "VD: Kinh"),
            new Field("maritalStatus", "Tình trạng hôn nhân", "select", "personal", true, List.of("Độc thân", "Đã kết hôn", "Khác"), null),
            new Field("phone", "Số điện thoại", "tel", "personal", true, null, null),
            new Field("personalEmail", "Email cá nhân", "email", "personal", true, null, null),
            new Field("permanentAddress", "Hộ khẩu thường trú", "text", "personal", true, null, null),
            new Field("currentAddress", "Chỗ ở hiện tại", "text", "personal", true, null, null),

            new Field("idNumber", "Số CCCD", "text", "papers", true, null, "12 số"),
            new Field("idIssueDate", "Ngày cấp", "date", "papers", true, null, null),
            new Field("idIssuePlace", "Nơi cấp", "text", "papers", true, null, "VD: Cục CS QLHC về TTXH"),
            new Field("taxCode", "Mã số thuế cá nhân", "text", "papers", true, null, null),
            new Field("socialInsuranceNo", "Số sổ BHXH", "text", "papers", true, null, null),
            new Field("bankAccount", "Số tài khoản ngân hàng", "text", "papers", true, null, null),
            new Field("bankName", "Ngân hàng, chi nhánh", "text", "papers", true, null, "VD: Vietcombank Thanh Hóa"),

            new Field("emergencyName", "Họ tên người liên hệ", "text", "emergency", true, null, null),
            new Field("emergencyRelation", "Quan hệ", "text", "emergency", true, null, "VD: Vợ, Bố, Mẹ"),
            new Field("emergencyPhone", "Số điện thoại", "tel", "emergency", true, null, null),

            new Field("jobTitle", "Chức danh công việc", "text", "work", false, null, "VD: Tư vấn bán hàng"),
            new Field("startDate", "Ngày vào làm", "date", "work", false, null, null),
            new Field("contractType", "Loại hợp đồng", "select", "work", false,
                    List.of("Học việc", "Thử việc", "Có thời hạn 12 tháng", "Có thời hạn 24 tháng", "Có thời hạn 36 tháng",
                            "Không xác định thời hạn", "Cộng tác viên", "Thời vụ"), null),
            new Field("contractNo", "Số hợp đồng", "text", "work", false, null, null),
            new Field("contractStart", "Hợp đồng từ ngày", "date", "work", false, null, null),
            new Field("contractEnd", "Hợp đồng đến ngày", "date", "work", false, null, "Trống = không thời hạn"),
            new Field("leaveDate", "Ngày nghỉ việc", "date", "work", false, null, "Chỉ điền khi đã nghỉ"),

            new Field("educationLevel", "Trình độ", "select", "cv", true,
                    List.of("THPT", "Trung cấp", "Cao đẳng", "Đại học", "Thạc sĩ", "Tiến sĩ", "Khác"), null),
            new Field("major", "Chuyên ngành", "text", "cv", true, null, null),
            new Field("school", "Trường", "text", "cv", true, null, null),
            new Field("graduationYear", "Năm tốt nghiệp", "number", "cv", true, null, null),
            new Field("summary", "Giới thiệu bản thân / mục tiêu", "textarea", "cv", true, null, null),
            new Field("experience", "Kinh nghiệm làm việc", "textarea", "cv", true, null,
                    "Mỗi dòng 1 việc: thời gian · công ty · vị trí · công việc chính"),
            new Field("educationHistory", "Quá trình học tập, đào tạo", "textarea", "cv", true, null, null),
            new Field("skills", "Kỹ năng", "textarea", "cv", true, null, "VD: Tin học văn phòng, tiếng Anh giao tiếp, lái xe B2"),
            new Field("certificates", "Chứng chỉ, bằng lái", "textarea", "cv", true, null, null),

            new Field("note", "Ghi chú", "textarea", "hr", false, null, "Chỉ HCNS thấy"));

    /** Các ô tính độ đầy đủ hồ sơ */
    static final List<String> REQUIRED = List.of("gender", "birthDate", "phone", "permanentAddress", "currentAddress",
            "idNumber", "idIssueDate", "taxCode", "bankAccount", "bankName", "emergencyName", "emergencyPhone",
            "jobTitle", "startDate", "contractType", "educationLevel");

    private final UnitScope unitScope;
    private final UserRepository userRepository;
    private final EmployeeProfileRepository profileRepository;
    private final EmployeeDocumentRepository documentRepository;
    private final EmployeeFileStorage storage;

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.service.DeviceEnrollService deviceService;                 // ghi nhân viên xuống máy chấm công
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    public EmployeeProfileController(UnitScope unitScope, UserRepository userRepository,
                                     EmployeeProfileRepository profileRepository,
                                     EmployeeDocumentRepository documentRepository, EmployeeFileStorage storage) {
        this.unitScope = unitScope;
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.documentRepository = documentRepository;
        this.storage = storage;
    }

    // =====================================================================
    // QUYỀN
    // =====================================================================

    static boolean isHr(User u) {
        return NewsController.isHr(u) || UnitScope.isAdmin(u);
    }

    private boolean sameUnit(User a, User b) {
        return a != null && b != null && Objects.equals(unitScope.unitKey(a), unitScope.unitKey(b));
    }

    /** HCNS / quản trị cùng đơn vị (quản trị hệ thống khi đã tắt chế độ hạn chế) */
    boolean canManage(User me, User emp) {
        if (me == null || emp == null) {
            return false;
        }
        if (unitScope.isGlobalAdmin(me)) {
            return unitScope.inScope(emp);
        }
        // HỒ SƠ CỦA TÔI luôn chỉ xem, kể cả người đó là HCNS / quản trị đơn vị:
        // hồ sơ, máy chấm công, giấy tờ của ai thì HCNS KHÁC (hoặc quản trị) nhập cho người đó.
        if (Objects.equals(me.getId(), emp.getId())) {
            return false;
        }
        return isHr(me) && sameUnit(me, emp);
    }

    boolean canView(User me, User emp) {
        if (me == null || emp == null) {
            return false;
        }
        return Objects.equals(me.getId(), emp.getId()) || canManage(me, emp)
                || (unitScope.isDirector(me) && sameUnit(me, emp));
    }

    // =====================================================================
    // DANH SÁCH (HCNS)
    // =====================================================================

    @GetMapping("/hr/employees")
    public String list(@RequestParam(required = false) String q, @RequestParam(required = false) String dept,
                       @RequestParam(required = false) String filter, Model model) {
        User me = unitScope.currentUser();
        if (me == null) {
            return "redirect:/login";
        }
        boolean manager = isHr(me) || unitScope.isGlobalAdmin(me) || unitScope.isDirector(me);
        if (!manager) {
            return "redirect:/my-profile";
        }
        // người cùng đơn vị (quản trị hệ thống: đơn vị đang xem), có mã NV
        List<User> staff = new ArrayList<>();
        for (User u : userRepository.findAll()) {
            if (u.getEmployeeNo() == null || u.getEmployeeNo().isBlank() || unitScope.isGlobalAdmin(u)) {
                continue;
            }
            boolean mine = unitScope.isGlobalAdmin(me) ? unitScope.inScope(u) : sameUnit(me, u);
            if (mine) {
                staff.add(u);
            }
        }
        Set<Long> ids = new HashSet<>();
        staff.forEach(u -> ids.add(u.getId()));
        Map<Long, EmployeeProfile> profiles = new HashMap<>();
        profileRepository.findByUserIdIn(ids).forEach(p -> profiles.put(p.getUserId(), p));
        Set<Long> hasCv = new HashSet<>();
        documentRepository.findByUserIdInAndCategory(ids, "CV").forEach(d -> hasCv.add(d.getUserId()));

        LocalDate today = LocalDate.now(VN);
        String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String f = filter == null ? "" : filter;
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> depts = new java.util.TreeSet<>();
        int expiring = 0, incomplete = 0, noCv = 0;
        for (User u : staff) {
            if (u.getDepartment() != null && !u.getDepartment().isBlank()) {
                depts.add(u.getDepartment().trim());
            }
            EmployeeProfile p = profiles.get(u.getId());
            int pct = completeness(p);
            LocalDate end = p == null ? null : p.getContractEnd();
            boolean exp = end != null && !end.isBefore(today) && !end.isAfter(today.plusDays(30));
            boolean expired = end != null && end.isBefore(today) && (p.getLeaveDate() == null);
            boolean cv = hasCv.contains(u.getId());
            if (exp || expired) expiring++;
            if (pct < 100) incomplete++;
            if (!cv) noCv++;

            if (dept != null && !dept.isBlank() && !dept.equalsIgnoreCase(u.getDepartment() == null ? "" : u.getDepartment().trim())) {
                continue;
            }
            if (!term.isEmpty() && !(contains(u.getFullName(), term) || contains(u.getEmployeeNo(), term)
                    || contains(p == null ? null : p.getPhone(), term) || contains(p == null ? null : p.getJobTitle(), term))) {
                continue;
            }
            if ("expiring".equals(f) && !(exp || expired)) continue;
            if ("incomplete".equals(f) && pct >= 100) continue;
            if ("nocv".equals(f) && cv) continue;

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("user", u);
            r.put("p", p);
            r.put("pct", pct);
            r.put("cv", cv);
            r.put("expiring", exp);
            r.put("expired", expired);
            rows.add(r);
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(((User) r.get("user")).getDepartment()))
                .thenComparing(r -> String.valueOf(((User) r.get("user")).getFullName())));

        model.addAttribute("rows", rows);
        model.addAttribute("total", staff.size());
        model.addAttribute("depts", depts);
        model.addAttribute("dept", dept == null ? "" : dept);
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("filter", f);
        model.addAttribute("expiringCount", expiring);
        model.addAttribute("incompleteCount", incomplete);
        model.addAttribute("noCvCount", noCv);
        model.addAttribute("canAdd", isHr(me) || unitScope.isGlobalAdmin(me));
        return "hr-employees";
    }

    static int completeness(EmployeeProfile p) {
        if (p == null) {
            return 0;
        }
        BeanWrapper w = new BeanWrapperImpl(p);
        int filled = 0;
        for (String k : REQUIRED) {
            Object v = w.getPropertyValue(k);
            if (v != null && !(v instanceof String s && s.isBlank())) {
                filled++;
            }
        }
        return Math.round(filled * 100f / REQUIRED.size());
    }

    private static boolean contains(String s, String term) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(term);
    }

    // =====================================================================
    // XEM / SỬA 1 HỒ SƠ
    // =====================================================================

    @GetMapping("/my-profile")
    public String mine() {
        User me = unitScope.currentUser();
        return me == null ? "redirect:/login" : "redirect:/hr/employees/" + me.getId();
    }

    @GetMapping("/hr/employees/{id}")
    public String view(@PathVariable Long id, Model model) {
        User me = unitScope.currentUser();
        if (me == null) {
            return "redirect:/login";
        }
        User emp = userRepository.findById(id).orElse(null);
        if (!canView(me, emp)) {
            return "redirect:/my-profile";
        }
        boolean self = Objects.equals(me.getId(), emp.getId());
        boolean hr = canManage(me, emp);
        EmployeeProfile p = profileRepository.findByUserId(emp.getId()).orElseGet(EmployeeProfile::new);

        BeanWrapper w = new BeanWrapperImpl(p);
        Map<String, Object> values = new HashMap<>();
        for (Field f : FIELDS) {
            values.put(f.key(), w.getPropertyValue(f.key()));
        }
        List<Group> groups = new ArrayList<>();
        for (Group g : GROUPS) {
            if (g.hrOnly() && !hr) continue;                    // ghi chú HCNS
            if (g.sensitive() && !hr && !self) continue;        // giấy tờ / ngân hàng: chỉ HCNS + chính chủ
            groups.add(g);
        }
        List<EmployeeDocument> docs = documentRepository.findByUserIdOrderByUploadedAtDesc(emp.getId());
        if (!hr && !self) {
            docs = docs.stream().filter(d -> !"CCCD".equals(d.getCategory())).toList();
        }
        EmployeeDocument photo = docs.stream().filter(d -> "ANH_THE".equals(d.getCategory()) && d.isPicture()).findFirst().orElse(null);

        model.addAttribute("emp", emp);
        model.addAttribute("p", p);
        model.addAttribute("values", values);
        model.addAttribute("fields", FIELDS);
        model.addAttribute("groups", groups);
        model.addAttribute("docs", docs);
        model.addAttribute("photo", photo);
        model.addAttribute("categories", EmployeeDocument.CATEGORIES);
        model.addAttribute("self", self);
        model.addAttribute("hr", hr);
        model.addAttribute("canEdit", hr);   // HCNS nhập & quản lý hồ sơ; nhân viên chỉ xem
        model.addAttribute("pct", completeness(p.getId() == null ? null : p));
        model.addAttribute("unitLabel", unitScope.unitOf(emp) == null ? "" : unitScope.unitOf(emp).getLabel());
        model.addAttribute("fileAccept", NewsFileStorage.ACCEPT);
        com.example.hrm.entity.CompanyUnit eu = unitScope.unitOf(emp);
        model.addAttribute("hasDevice", eu != null && eu.isHasDevice());
        model.addAttribute("deviceNo", deviceService.deviceNo(emp));
        model.addAttribute("hasPhoto", photo != null);
        return "employee-profile";
    }

    @PostMapping("/hr/employees/{id}")
    public String save(@PathVariable Long id, @RequestParam Map<String, String> form, RedirectAttributes ra) {
        User me = unitScope.currentUser();
        User emp = userRepository.findById(id).orElse(null);
        boolean hr = canManage(me, emp);
        if (!hr) {
            return "redirect:/my-profile";   // chỉ HCNS / quản trị đơn vị sửa hồ sơ, nhân viên chỉ xem
        }
        EmployeeProfile p = profileRepository.findByUserId(emp.getId()).orElseGet(() -> {
            EmployeeProfile n = new EmployeeProfile();
            n.setUserId(emp.getId());
            return n;
        });
        BeanWrapper w = new BeanWrapperImpl(p);
        List<String> errors = new ArrayList<>();
        for (Field f : FIELDS) {
            if (!form.containsKey(f.key())) {
                continue;                                     // ô không có trên form: giữ nguyên
            }
            String raw = form.get(f.key()) == null ? "" : form.get(f.key()).trim();
            try {
                Object v = convert(f, raw);
                w.setPropertyValue(f.key(), v);
            } catch (IllegalArgumentException e) {
                errors.add(f.label() + ": " + e.getMessage());
            }
        }
        if (p.getContractStart() != null && p.getContractEnd() != null && p.getContractEnd().isBefore(p.getContractStart())) {
            errors.add("Ngày hết hợp đồng phải sau ngày bắt đầu");
        }
        if (!errors.isEmpty()) {
            ra.addFlashAttribute("error", String.join(" · ", errors));
            return "redirect:/hr/employees/" + id;
        }
        p.setUpdatedBy(me.getFullName() != null ? me.getFullName() : me.getUsername());
        p.setUpdatedAt(LocalDateTime.now(VN));
        profileRepository.save(p);
        ra.addFlashAttribute("message", "Đã lưu hồ sơ " + emp.getFullName());
        return "redirect:/hr/employees/" + id;
    }

    private static Object convert(Field f, String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        switch (f.type()) {
            case "date":
                try {
                    return LocalDate.parse(raw);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("ngày không hợp lệ");
                }
            case "number":
                try {
                    int n = Integer.parseInt(raw);
                    if (n < 1950 || n > 2100) throw new NumberFormatException();
                    return n;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("năm không hợp lệ");
                }
            case "select":
                if (f.options() != null && !f.options().contains(raw)) {
                    throw new IllegalArgumentException("giá trị không hợp lệ");
                }
                return raw;
            case "email":
                if (!raw.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                    throw new IllegalArgumentException("email không hợp lệ");
                }
                return cut(raw, 150);
            case "tel":
                if (!raw.matches("[0-9+ ().-]{8,20}")) {
                    throw new IllegalArgumentException("số điện thoại không hợp lệ");
                }
                return raw;
            case "textarea":
                return cut(raw, 8000);
            default:
                return cut(raw, "idNumber".equals(f.key()) || "taxCode".equals(f.key()) || "socialInsuranceNo".equals(f.key())
                        || "bankAccount".equals(f.key()) ? 30 : 300);
        }
    }

    private static String cut(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    // =====================================================================
    // THÊM NHÂN VIÊN (HCNS) + TẠO LUÔN TRÊN MÁY CHẤM CÔNG
    // =====================================================================

    @GetMapping("/hr/employees/new")
    public String newForm(Model model) {
        User me = unitScope.currentUser();
        if (me == null) {
            return "redirect:/login";
        }
        com.example.hrm.entity.CompanyUnit unit = workUnit(me);
        if (unit == null || !(isHr(me) || unitScope.isGlobalAdmin(me))) {
            return "redirect:/hr/employees";
        }
        java.util.TreeSet<String> depts = new java.util.TreeSet<>();
        int maxNo = 0;
        for (User u : userRepository.findAll()) {
            if (!Objects.equals(unitScope.unitKey(u), unit.getId())) continue;
            if (u.getDepartment() != null && !u.getDepartment().isBlank()) depts.add(u.getDepartment().trim());
            String d = deviceService.deviceNo(u);
            if (d.matches("\\d{1,9}")) maxNo = Math.max(maxNo, Integer.parseInt(d));
        }
        model.addAttribute("unit", unit);
        model.addAttribute("depts", depts);
        model.addAttribute("suggestNo", String.valueOf(maxNo + 1));
        model.addAttribute("genders", List.of("Nam", "Nữ", "Khác"));
        model.addAttribute("contractTypes", FIELDS.stream().filter(f -> f.key().equals("contractType")).findFirst().get().options());
        return "employee-new";
    }

    /** Đơn vị đang làm việc của HCNS (quản trị hệ thống: đơn vị đang xem) */
    private com.example.hrm.entity.CompanyUnit workUnit(User me) {
        return unitScope.isGlobalAdmin(me) ? unitScope.scopeUnit() : unitScope.unitOf(me);
    }

    @PostMapping("/hr/employees/new")
    public String create(@RequestParam Map<String, String> form,
                         @RequestParam(value = "face", required = false) MultipartFile face, RedirectAttributes ra) {
        User me = unitScope.currentUser();
        com.example.hrm.entity.CompanyUnit unit = me == null ? null : workUnit(me);
        if (unit == null || !(isHr(me) || unitScope.isGlobalAdmin(me))) {
            return "redirect:/hr/employees";
        }
        String code = form.getOrDefault("deviceNo", "").trim();
        String name = form.getOrDefault("fullName", "").trim().replaceAll("\\s+", " ");
        String dept = form.getOrDefault("department", "").trim();
        String pw = form.getOrDefault("password", "");
        List<String> errors = new ArrayList<>();
        if (!code.matches("[0-9]{1,9}")) errors.add("Mã trên máy chỉ gồm số (1-9 chữ số)");
        if (name.isEmpty() || name.length() > 150) errors.add("Nhập họ tên");
        if (pw.length() < 6) errors.add("Mật khẩu ban đầu ít nhất 6 ký tự");
        String hrmNo = unit.employeeNoFor(code);
        if (errors.isEmpty() && (userRepository.findByEmployeeNo(hrmNo).isPresent() || userRepository.findByUsername(hrmNo).isPresent())) {
            errors.add("Mã nhân viên " + hrmNo + " đã có trên phần mềm");
        }
        if (!errors.isEmpty()) {
            ra.addFlashAttribute("error", String.join(" · ", errors));
            ra.addFlashAttribute("draft", form);
            return "redirect:/hr/employees/new";
        }
        String type = switch (form.getOrDefault("employeeType", "THU_VIEC")) {
            case "CHINH_THUC" -> "CHINH_THUC";
            case "HOC_VIEC" -> "HOC_VIEC";
            default -> "THU_VIEC";
        };
        User u = User.builder()
                .username(hrmNo)
                .employeeNo(hrmNo)
                .attendanceCode(code)
                .fullName(name)
                .department(dept.isEmpty() ? null : (dept.length() > 100 ? dept.substring(0, 100) : dept))
                .password(passwordEncoder.encode(pw))
                .role("USER")
                .employeeType(type)
                .departmentManager("MANAGER".equals(form.get("position")))
                .unit(unit)
                .build();
        u = userRepository.save(u);

        // hồ sơ ban đầu
        EmployeeProfile p = new EmployeeProfile();
        p.setUserId(u.getId());
        String gender = form.get("gender");
        p.setGender(List.of("Nam", "Nữ", "Khác").contains(gender) ? gender : null);
        p.setPhone(form.getOrDefault("phone", "").trim().isEmpty() ? null : cutS(form.get("phone").trim(), 20));
        p.setJobTitle(form.getOrDefault("jobTitle", "").trim().isEmpty() ? null : cutS(form.get("jobTitle").trim(), 150));
        try {
            String sd = form.getOrDefault("startDate", "");
            p.setStartDate(sd.isBlank() ? LocalDate.now(VN) : LocalDate.parse(sd));
        } catch (DateTimeParseException e) {
            p.setStartDate(LocalDate.now(VN));
        }
        String ct = form.get("contractType");
        if (ct != null && FIELDS.stream().anyMatch(f -> f.key().equals("contractType") && f.options().contains(ct))) {
            p.setContractType(ct);
        }
        p.setUpdatedBy(me.getFullName() != null ? me.getFullName() : me.getUsername());
        p.setUpdatedAt(LocalDateTime.now(VN));
        profileRepository.save(p);

        List<String> msgs = new ArrayList<>();
        msgs.add("Đã tạo nhân viên " + name + " (mã " + hrmNo + ", đăng nhập: " + hrmNo + ")");
        boolean warn = false;
        if (form.containsKey("toDevice")) {
            var r = deviceService.pushUser(u, p.getGender());
            msgs.add(r.message());
            warn |= !r.ok();
            if (r.ok() && face != null && !face.isEmpty()) {
                try {
                    var f = deviceService.uploadFace(u, face.getBytes());
                    msgs.add(f.message());
                    warn |= !f.ok();
                    saveFaceAsPhoto(u, me, face);
                } catch (IOException e) {
                    msgs.add("Không đọc được ảnh khuôn mặt");
                    warn = true;
                }
            }
            String card = form.getOrDefault("cardNo", "").trim();
            if (r.ok() && !card.isEmpty()) {
                var c = deviceService.addCard(u, card);
                msgs.add(c.message());
                warn |= !c.ok();
            }
        }
        ra.addFlashAttribute(warn ? "error" : "message", String.join(". ", msgs));
        return "redirect:/hr/employees/" + u.getId();
    }

    /** Ảnh khuôn mặt đã dùng thì lưu luôn làm ảnh thẻ trong hồ sơ */
    private void saveFaceAsPhoto(User emp, User me, MultipartFile face) {
        try {
            if (storage.validate(face) != null) return;
            NewsFileStorage.Stored st = storage.save(face);
            EmployeeDocument d = new EmployeeDocument();
            d.setUserId(emp.getId());
            d.setCategory("ANH_THE");
            d.setOriginalName(st.originalName());
            d.setStoredName(st.storedName());
            d.setContentType(st.contentType());
            d.setSizeBytes(st.size());
            d.setImage(st.image());
            d.setUploadedBy(me.getFullName() != null ? me.getFullName() : me.getUsername());
            d.setByHr(true);
            d.setUploadedAt(LocalDateTime.now(VN));
            documentRepository.save(d);
        } catch (IOException | RuntimeException ignore) {
            // ảnh hồ sơ là phụ, không làm hỏng việc chính
        }
    }

    private static String cutS(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** Máy chấm công cho 1 nhân viên: push (thêm / cập nhật tên), face, card, remove */
    @PostMapping("/hr/employees/{id}/device/{action}")
    public String device(@PathVariable Long id, @PathVariable String action, @RequestParam Map<String, String> form,
                         @RequestParam(value = "face", required = false) MultipartFile face, RedirectAttributes ra) {
        User me = unitScope.currentUser();
        User emp = userRepository.findById(id).orElse(null);
        if (!canManage(me, emp)) {
            return "redirect:/my-profile";
        }
        String gender = profileRepository.findByUserId(emp.getId()).map(EmployeeProfile::getGender).orElse(null);
        com.example.hrm.service.DeviceEnrollService.Result r;
        try {
            r = switch (action) {
                case "push" -> deviceService.pushUser(emp, gender);
                case "remove" -> deviceService.removeUser(emp);
                case "card" -> deviceService.addCard(emp, form.get("cardNo"));
                case "face" -> {
                    byte[] img = null;
                    if (face != null && !face.isEmpty()) {
                        img = face.getBytes();
                    } else if (form.containsKey("usePhoto")) {
                        EmployeeDocument ph = documentRepository.findByUserIdOrderByUploadedAtDesc(emp.getId()).stream()
                                .filter(d -> "ANH_THE".equals(d.getCategory()) && d.isPicture()).findFirst().orElse(null);
                        Path pth = ph == null ? null : storage.resolve(ph.getStoredName());
                        img = pth == null ? null : java.nio.file.Files.readAllBytes(pth);
                    }
                    if (img == null) {
                        yield new com.example.hrm.service.DeviceEnrollService.Result(false, "Chọn ảnh khuôn mặt (hoặc tải Ảnh thẻ vào hồ sơ trước)");
                    }
                    var res = deviceService.uploadFace(emp, img);
                    if (res.ok() && face != null && !face.isEmpty()) {
                        saveFaceAsPhoto(emp, me, face);
                    }
                    yield res;
                }
                default -> new com.example.hrm.service.DeviceEnrollService.Result(false, "Lệnh không hợp lệ");
            };
        } catch (IOException e) {
            r = new com.example.hrm.service.DeviceEnrollService.Result(false, "Không đọc được ảnh");
        }
        ra.addFlashAttribute(r.ok() ? "message" : "error", r.message());
        return "redirect:/hr/employees/" + id + "#device";
    }

    // =====================================================================
    // FILE HỒ SƠ (CV, CCCD, bằng cấp...)
    // =====================================================================

    @PostMapping("/hr/employees/{id}/files")
    public String upload(@PathVariable Long id, @RequestParam(required = false) String category,
                         @RequestParam(value = "files", required = false) List<MultipartFile> files, RedirectAttributes ra) {
        User me = unitScope.currentUser();
        User emp = userRepository.findById(id).orElse(null);
        boolean self = me != null && emp != null && Objects.equals(me.getId(), emp.getId());
        boolean hr = canManage(me, emp);
        if (!hr) {
            return "redirect:/my-profile";   // chỉ HCNS tải file hồ sơ
        }
        String cat = category != null && EmployeeDocument.CATEGORIES.containsKey(category) ? category : "KHAC";
        List<MultipartFile> ups = files == null ? List.of() : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (ups.isEmpty()) {
            ra.addFlashAttribute("error", "Chưa chọn file");
            return "redirect:/hr/employees/" + id + "#files";
        }
        if (ups.size() > MAX_FILES_PER_UPLOAD) {
            ra.addFlashAttribute("error", "Mỗi lần tối đa " + MAX_FILES_PER_UPLOAD + " file");
            return "redirect:/hr/employees/" + id + "#files";
        }
        for (MultipartFile f : ups) {
            String err = storage.validate(f);
            if (err != null) {
                ra.addFlashAttribute("error", err);
                return "redirect:/hr/employees/" + id + "#files";
            }
        }
        int n = 0;
        for (MultipartFile f : ups) {
            try {
                NewsFileStorage.Stored st = storage.save(f);
                EmployeeDocument d = new EmployeeDocument();
                d.setUserId(emp.getId());
                d.setCategory(cat);
                d.setOriginalName(st.originalName());
                d.setStoredName(st.storedName());
                d.setContentType(st.contentType());
                d.setSizeBytes(st.size());
                d.setImage(st.image());
                d.setUploadedBy(me.getFullName() != null ? me.getFullName() : me.getUsername());
                d.setByHr(hr && !self);
                d.setUploadedAt(LocalDateTime.now(VN));
                documentRepository.save(d);
                n++;
            } catch (IOException | RuntimeException e) {
                ra.addFlashAttribute("error", "Không lưu được " + f.getOriginalFilename() + ": " + e.getMessage());
            }
        }
        if (n > 0) {
            ra.addFlashAttribute("message", "Đã tải lên " + n + " file (" + EmployeeDocument.CATEGORIES.get(cat) + ")");
        }
        return "redirect:/hr/employees/" + id + "#files";
    }

    @PostMapping("/hr/employees/files/{fileId}/delete")
    public String deleteFile(@PathVariable Long fileId, RedirectAttributes ra) {
        User me = unitScope.currentUser();
        EmployeeDocument d = documentRepository.findById(fileId).orElse(null);
        if (me == null || d == null) {
            return "redirect:/my-profile";
        }
        User emp = userRepository.findById(d.getUserId()).orElse(null);
        boolean hr = canManage(me, emp);
        if (!hr) {
            ra.addFlashAttribute("error", "Chỉ HCNS xoá được file hồ sơ");
            return "redirect:/hr/employees/" + d.getUserId() + "#files";
        }
        documentRepository.delete(d);
        storage.delete(d.getStoredName());
        ra.addFlashAttribute("message", "Đã xoá " + d.getOriginalName());
        return "redirect:/hr/employees/" + d.getUserId() + "#files";
    }

    @GetMapping("/hr/employees/files/{fileId}")
    public ResponseEntity<Resource> file(@PathVariable Long fileId, @RequestParam(required = false) String download) {
        User me = unitScope.currentUser();
        EmployeeDocument d = me == null ? null : documentRepository.findById(fileId).orElse(null);
        User emp = d == null ? null : userRepository.findById(d.getUserId()).orElse(null);
        if (d == null || !canView(me, emp)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        boolean self = Objects.equals(me.getId(), emp.getId());
        if ("CCCD".equals(d.getCategory()) && !self && !canManage(me, emp)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Path p = storage.resolve(d.getStoredName());
        if (p == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        boolean inline = download == null && (d.isPicture() || "PDF".equals(d.getExt()));
        ContentDisposition cd = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(d.getOriginalName(), StandardCharsets.UTF_8).build();
        MediaType type;
        try {
            type = MediaType.parseMediaType(d.getContentType() == null ? "application/octet-stream" : d.getContentType());
        } catch (RuntimeException e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(type)
                .body(new FileSystemResource(p));
    }
}
