package com.example.hrm.service;

import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.entity.User;
import com.example.hrm.repository.CompanyUnitRepository;
import com.example.hrm.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PHẠM VI ĐƠN VỊ của người đang đăng nhập. Mọi màn hình danh sách (nhân viên, bảng công, duyệt công,
 * cấu hình lương, tính lương...) lọc qua đây để người của đơn vị này không thấy dữ liệu đơn vị khác.
 *
 *   - Quản trị hệ thống (username trong hrm.units.global-admins, mặc định "admin", hoặc role SUPER_ADMIN):
 *     thấy mọi đơn vị, chọn "đang xem đơn vị nào" ở menu trái (lưu trong phiên đăng nhập).
 *   - Mọi người khác (kể cả role ADMIN, Giám đốc, Kế toán trưởng, Nhân sự, trưởng phòng):
 *     chỉ thấy đơn vị của mình.
 *   - Không có người đăng nhập (job chạy nền): không lọc.
 */
@Component
public class UnitScope {

    /** Thuộc tính phiên: đơn vị quản trị hệ thống đang chọn xem (null = tất cả) */
    public static final String VIEW_ATTR = "hrm.viewUnitId";

    private final UserRepository userRepository;
    private final CompanyUnitRepository unitRepository;

    @Value("${hrm.units.global-admins:admin}")
    private String globalAdmins = "admin";

    /** id đơn vị gốc, nạp 1 lần (đổi đơn vị gốc thì gọi refresh()) */
    private volatile Long legacyId;
    private volatile boolean legacyLoaded;

    public UnitScope(UserRepository userRepository, CompanyUnitRepository unitRepository) {
        this.userRepository = userRepository;
        this.unitRepository = unitRepository;
    }

    // =====================================================================
    // NGƯỜI ĐĂNG NHẬP
    // =====================================================================

    private static final String ME_ATTR = "hrm.unitScope.me";
    private static final String SCOPE_ATTR = "hrm.unitScope.scope";
    private static final Object NONE = new Object();

    public User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        // nạp 1 lần mỗi request (hàm này được gọi trong vòng lặp lọc danh sách)
        Object cached = requestAttr(ME_ATTR);
        if (cached instanceof User u && auth.getName().equals(cachedLogin(u, auth.getName()))) {
            return u;
        }
        User u = find(auth.getName());
        setRequestAttr(ME_ATTR, u);
        return u;
    }

    private static String cachedLogin(User u, String login) {
        return login.equals(u.getUsername()) || login.equals(u.getEmployeeNo()) ? login : "";
    }

    public User find(String login) {
        if (login == null) {
            return null;
        }
        return userRepository.findByUsername(login)
                .orElseGet(() -> userRepository.findByEmployeeNo(login).orElse(null));
    }

    /** Quản trị toàn hệ thống: thấy mọi đơn vị */
    public boolean isGlobalAdmin(User u) {
        if (u == null) {
            return false;
        }
        if ("SUPER_ADMIN".equalsIgnoreCase(trim(u.getRole()))) {
            return true;
        }
        String name = trim(u.getUsername()).toLowerCase(Locale.ROOT);
        return !name.isEmpty() && globalAdminSet().contains(name);
    }

    /** Quản trị theo định nghĩa CŨ của hệ thống (role/phòng ADMIN hoặc username admin) */
    public static boolean isAdmin(User u) {
        return u != null && ("ADMIN".equalsIgnoreCase(trim(u.getRole()))
                || "SUPER_ADMIN".equalsIgnoreCase(trim(u.getRole()))
                || "ADMIN".equalsIgnoreCase(trim(u.getDepartment()))
                || "admin".equalsIgnoreCase(trim(u.getUsername())));
    }

    // =====================================================================
    // ĐƠN VỊ
    // =====================================================================

    public CompanyUnit legacyUnit() {
        Long id = legacyId();
        return id == null ? null : unitRepository.findById(id).orElse(null);
    }

    public Long legacyId() {
        if (!legacyLoaded) {
            legacyId = unitRepository.findFirstByLegacyTrue().map(CompanyUnit::getId).orElse(null);
            legacyLoaded = true;
        }
        return legacyId;
    }

    /** Gọi sau khi thêm / sửa đơn vị */
    public void refresh() {
        legacyLoaded = false;
    }

    /** Đơn vị thực tế: chưa gán = đơn vị gốc */
    public CompanyUnit unitOf(User u) {
        if (u != null && u.getUnit() != null) {
            return u.getUnit();
        }
        return legacyUnit();
    }

    /** Khoá so sánh đơn vị của 1 nhân viên (chưa gán = đơn vị gốc) */
    public Long unitKey(User u) {
        if (u != null && u.getUnit() != null) {
            return u.getUnit().getId();
        }
        return legacyId();
    }

    /** Khoá so sánh của 1 đơn vị lưu trên bản ghi (null = dữ liệu cũ = đơn vị gốc) */
    public Long unitKey(CompanyUnit c) {
        return c != null ? c.getId() : legacyId();
    }

    public boolean sameUnit(User a, User b) {
        return a != null && b != null && Objects.equals(unitKey(a), unitKey(b));
    }

    // =====================================================================
    // PHẠM VI ĐANG XEM
    // =====================================================================

    /**
     * Đơn vị đang xem: id, hoặc null = mọi đơn vị (quản trị hệ thống chọn "Tất cả", hoặc job chạy nền).
     */
    public Long scopeUnitId() {
        Object cached = requestAttr(SCOPE_ATTR);
        if (cached != null) {
            return cached == NONE ? null : (Long) cached;
        }
        Long s = computeScope();
        setRequestAttr(SCOPE_ATTR, s == null ? NONE : s);
        return s;
    }

    private Long computeScope() {
        User me = currentUser();
        if (me == null) {
            return null;
        }
        if (isGlobalAdmin(me)) {
            Object v = sessionAttr(VIEW_ATTR);
            if (v instanceof Long l) {
                return l;
            }
            // mới có 1 đơn vị: "Tất cả" chính là đơn vị đó (chốt kỳ, tạo mẫu... không bắt chọn đơn vị)
            return unitRepository.count() == 1 ? legacyId() : null;
        }
        // chưa có đơn vị nào trong hệ thống: coi như 1 công ty, không lọc
        return unitKey(me);
    }

    public CompanyUnit scopeUnit() {
        Long id = scopeUnitId();
        return id == null ? null : unitRepository.findById(id).orElse(null);
    }

    /** Nhân viên này nằm trong phạm vi người đang đăng nhập được xem? */
    public boolean inScope(User employee) {
        Long scope = scopeUnitId();
        return scope == null || Objects.equals(scope, unitKey(employee));
    }

    public boolean inScopeUnit(Long unitKey) {
        Long scope = scopeUnitId();
        return scope == null || Objects.equals(scope, unitKey);
    }

    public <T extends User> List<T> filter(Collection<T> users) {
        Long scope = scopeUnitId();
        if (scope == null) {
            return List.copyOf(users);
        }
        return users.stream().filter(u -> Objects.equals(scope, unitKey(u))).toList();
    }

    /** Nhân viên trong phạm vi đang xem */
    public List<User> users() {
        return filter(userRepository.findAll());
    }

    /** Mã nhân viên trong phạm vi đang xem (null = không lọc) */
    public Set<String> employeeNos() {
        Long scope = scopeUnitId();
        if (scope == null) {
            return null;
        }
        return users().stream().map(User::getEmployeeNo).filter(Objects::nonNull)
                .map(s -> s.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    public void setView(Long unitId) {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra != null) {
            ra.setAttribute(VIEW_ATTR, unitId, RequestAttributes.SCOPE_SESSION);
            ra.removeAttribute(SCOPE_ATTR, RequestAttributes.SCOPE_REQUEST);
        }
    }

    // =====================================================================
    // NGƯỜI DUYỆT CỦA ĐƠN VỊ
    // =====================================================================

    /** Giám đốc của đơn vị (bản cũ: mã 99999 hoặc tài khoản gdkd) */
    public boolean isDirector(User u) {
        if (u == null) {
            return false;
        }
        CompanyUnit unit = unitOf(u);
        if (unit != null && same(u.getEmployeeNo(), unit.getDirectorNo())) {
            return true;
        }
        // tương thích bản cũ: đơn vị gốc (hoặc chưa có đơn vị nào)
        boolean legacy = unit == null || unit.isLegacyUnit();
        return legacy && ("99999".equals(trim(u.getEmployeeNo())) || "gdkd".equalsIgnoreCase(trim(u.getUsername())));
    }

    /** Người duyệt cấp đơn vị: Giám đốc, Giám đốc dịch vụ, Kế toán trưởng */
    public boolean isUnitApprover(User u) {
        if (u == null) {
            return false;
        }
        if (isDirector(u)) {
            return true;
        }
        CompanyUnit unit = unitOf(u);
        String no = trim(u.getEmployeeNo());
        if (unit != null && (same(no, unit.getServiceDirectorNo()) || same(no, unit.getChiefAccountantNo()))) {
            return true;
        }
        boolean legacy = unit == null || unit.isLegacyUnit();
        return legacy && ("88888".equals(no) || "77777".equals(no));
    }

    /** Mã người duyệt theo vai trò trong đơn vị; trống thì về Giám đốc; vẫn trống thì mã cũ */
    public String directorNo(CompanyUnit unit) {
        String v = unit == null ? "" : trim(unit.getDirectorNo());
        if (!v.isEmpty()) {
            return v;
        }
        // đơn vị gốc giữ mã cũ; đơn vị mới chưa khai Giám đốc: đơn chờ quản trị hệ thống duyệt,
        // KHÔNG rơi sang Giám đốc đơn vị khác
        return unit == null || unit.isLegacyUnit() ? "99999" : "GD-" + unit.getCode();
    }

    public String serviceDirectorNo(CompanyUnit unit) {
        String v = unit == null ? "88888" : trim(unit.getServiceDirectorNo());
        if (unit != null && unit.isLegacyUnit() && v.isEmpty()) {
            return "88888";
        }
        return v.isEmpty() ? directorNo(unit) : v;
    }

    public String chiefAccountantNo(CompanyUnit unit) {
        String v = unit == null ? "77777" : trim(unit.getChiefAccountantNo());
        if (unit != null && unit.isLegacyUnit() && v.isEmpty()) {
            return "77777";
        }
        return v.isEmpty() ? directorNo(unit) : v;
    }

    // =====================================================================

    private Set<String> globalAdminSet() {
        return Arrays.stream((globalAdmins == null ? "" : globalAdmins).split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private static Object requestAttr(String name) {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        return ra == null ? null : ra.getAttribute(name, RequestAttributes.SCOPE_REQUEST);
    }

    private static void setRequestAttr(String name, Object v) {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra != null) {
            if (v == null) {
                ra.removeAttribute(name, RequestAttributes.SCOPE_REQUEST);
            } else {
                ra.setAttribute(name, v, RequestAttributes.SCOPE_REQUEST);
            }
        }
    }

    private static Object sessionAttr(String name) {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        return ra == null ? null : ra.getAttribute(name, RequestAttributes.SCOPE_SESSION);
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && !a.isBlank() && a.trim().equalsIgnoreCase(b.trim());
    }

    static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
