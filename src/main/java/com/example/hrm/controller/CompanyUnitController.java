package com.example.hrm.controller;

import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.entity.User;
import com.example.hrm.repository.CompanyUnitRepository;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.service.AttendanceService;
import com.example.hrm.service.HikvisionUserSyncService;
import com.example.hrm.service.UnitScope;
import com.example.hrm.util.HikvisionApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * QUẢN LÝ ĐƠN VỊ (chỉ quản trị hệ thống): thêm / sửa đơn vị, giờ làm, người duyệt, máy chấm công,
 * kéo nhân viên + chấm công từng máy, chọn đơn vị đang xem.
 */
@Controller
@RequestMapping("/units")
public class CompanyUnitController {

    private static final Logger log = LoggerFactory.getLogger(CompanyUnitController.class);

    private final CompanyUnitRepository unitRepository;
    private final UserRepository userRepository;
    private final UnitScope unitScope;
    private final AttendanceService attendanceService;
    private final HikvisionUserSyncService userSyncService;
    private final HikvisionApiClient hikvisionApiClient;

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.service.UnitSyncRunner syncRunner;   // kéo công nền dùng chung với lịch tự kéo

    /** Kéo máy chạy nền, mỗi lúc 1 việc (máy chấm công yếu, không kéo song song) */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "unit-sync");
        t.setDaemon(true);
        return t;
    });
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public CompanyUnitController(CompanyUnitRepository unitRepository, UserRepository userRepository, UnitScope unitScope,
                                 AttendanceService attendanceService, HikvisionUserSyncService userSyncService,
                                 HikvisionApiClient hikvisionApiClient) {
        this.unitRepository = unitRepository;
        this.userRepository = userRepository;
        this.unitScope = unitScope;
        this.attendanceService = attendanceService;
        this.userSyncService = userSyncService;
        this.hikvisionApiClient = hikvisionApiClient;
    }

    private boolean denied(Principal principal) {
        return !unitScope.isGlobalAdmin(unitScope.find(principal == null ? null : principal.getName()));
    }

    // =====================================================================
    // CHỌN ĐƠN VỊ ĐANG XEM (menu trái)
    // =====================================================================

    @GetMapping("/view")
    public String view(@RequestParam(required = false) String unitId, Principal principal,
                       @RequestParam(required = false) String back,
                       jakarta.servlet.http.HttpServletRequest request) {
        if (!denied(principal)) {
            Long id = parseLong(unitId);
            unitScope.setView(id != null && unitRepository.existsById(id) ? id : null);
        }
        String ref = back != null ? back : request.getHeader("Referer");
        // chỉ quay về trang trong hệ thống
        if (ref != null) {
            int i = ref.indexOf("://");
            if (i > 0) {
                int slash = ref.indexOf('/', i + 3);
                ref = slash > 0 ? ref.substring(slash) : "/";
            }
            // chỉ đường dẫn nội bộ: "/abc", không "//host", "/\\host" hay có scheme
            if (ref.matches("/[A-Za-z0-9/_\\-.?=&%]*") && !ref.startsWith("//")) {
                return "redirect:" + ref;
            }
        }
        return "redirect:/";
    }

    // =====================================================================
    // DANH SÁCH
    // =====================================================================

    @GetMapping
    public String list(Principal principal, Model model) {
        if (denied(principal)) {
            return "redirect:/?error=no-permission";
        }
        List<CompanyUnit> units = unitRepository.findAllByOrderByCodeAsc();
        Map<Long, Long> counts = new LinkedHashMap<>();
        Map<Long, Map<String, String>> approvers = new LinkedHashMap<>();
        for (CompanyUnit u : units) {
            counts.put(u.getId(), userRepository.countByUnit_Id(u.getId()));
            Map<String, String> a = new LinkedHashMap<>();
            a.put("Giám đốc", personLabel(unitScope.directorNo(u), u));
            a.put("GĐ dịch vụ", personLabel(unitScope.serviceDirectorNo(u), u));
            a.put("Kế toán trưởng", personLabel(unitScope.chiefAccountantNo(u), u));
            approvers.put(u.getId(), a);
        }
        model.addAttribute("units", units);
        model.addAttribute("counts", counts);
        model.addAttribute("approvers", approvers);
        model.addAttribute("running", syncRunner.runningIds());
        model.addAttribute("total", counts.values().stream().mapToLong(Long::longValue).sum());
        return "units/list";
    }

    private String personLabel(String no, CompanyUnit unit) {
        if (no == null || no.isBlank() || no.startsWith("GD-")) {
            return "Chưa khai";
        }
        User p = userRepository.findByEmployeeNo(no).orElse(null);
        if (p == null) {
            return no + " (chưa có tài khoản)";
        }
        String s = no + " · " + (p.getFullName() == null ? "" : p.getFullName());
        if (!Objects.equals(unitScope.unitKey(p), unit.getId())) {
            s += " (thuộc đơn vị khác!)";
        }
        return s;
    }

    // =====================================================================
    // THÊM / SỬA
    // =====================================================================

    @GetMapping("/edit")
    public String edit(@RequestParam(required = false) Long id, Principal principal, Model model) {
        if (denied(principal)) {
            return "redirect:/?error=no-permission";
        }
        CompanyUnit u = id == null ? null : unitRepository.findById(id).orElse(null);
        if (u == null) {
            u = new CompanyUnit();
            u.setActive(true);
            u.setLegacy(false);
            u.setLateAfter(CompanyUnit.DEFAULT_LATE_AFTER);
            u.setViolationAfter(CompanyUnit.DEFAULT_VIOLATION_AFTER);
            u.setNoonSplit(CompanyUnit.DEFAULT_NOON);
            u.setEarlyBefore(CompanyUnit.DEFAULT_EARLY_BEFORE);
            u.setDeviceUsername("admin");
            u.setSyncFromDate(LocalDate.now().withDayOfMonth(1));
        }
        model.addAttribute("unit", u);
        model.addAttribute("isNew", u.getId() == null);
        model.addAttribute("userCount", u.getId() == null ? 0 : userRepository.countByUnit_Id(u.getId()));
        return "units/edit";
    }

    @PostMapping("/save")
    public String save(@RequestParam Map<String, String> form, Principal principal, Model model, RedirectAttributes ra) {
        if (denied(principal)) {
            return "redirect:/?error=no-permission";
        }
        Long id = parseLong(form.get("id"));
        CompanyUnit existing = id == null ? null : unitRepository.findById(id).orElse(null);
        CompanyUnit d = new CompanyUnit();
        d.setId(existing == null ? null : existing.getId());
        List<String> errors = new ArrayList<>();

        d.setCode(text(form.get("code")).toUpperCase());
        d.setName(text(form.get("name")));
        d.setActive(form.containsKey("active"));
        d.setLegacy(form.containsKey("legacy"));
        // NGƯỜI DUYỆT: HCNS đơn vị chọn ở trang "Chức vụ & phòng ban" (/hr/roles).
        // Form quản trị không còn các ô này -> giữ nguyên giá trị đang có.
        d.setDirectorNo(form.containsKey("directorNo") ? emptyToNull(form.get("directorNo"))
                : existing == null ? null : existing.getDirectorNo());
        d.setServiceDirectorNo(form.containsKey("serviceDirectorNo") ? emptyToNull(form.get("serviceDirectorNo"))
                : existing == null ? null : existing.getServiceDirectorNo());
        d.setChiefAccountantNo(form.containsKey("chiefAccountantNo") ? emptyToNull(form.get("chiefAccountantNo"))
                : existing == null ? null : existing.getChiefAccountantNo());

        // THẺ LƯƠNG
        d.setSlipCompany(emptyToNull(form.get("slipCompany")));
        d.setSlipPreparer(emptyToNull(form.get("slipPreparer")));
        d.setSlipPlace(emptyToNull(form.get("slipPlace")));

        String pd = text(form.get("payDay"));
        if (!pd.isEmpty()) {
            Long n = parseLong(pd);
            if (n == null || n < 1 || n > 28) {
                errors.add("Ngày chi lương phải từ 1 đến 28");
            } else {
                d.setPayDay(n.intValue());
            }
        }

        // HẠN XÁC NHẬN CÔNG (HCNS cấu hình ở Lịch làm việc; form không có ô = giữ nguyên)
        if (form.containsKey("confirmDays")) {
            String cd = text(form.get("confirmDays"));
            if (!cd.isEmpty()) {
                Long n = parseLong(cd);
                if (n == null || n < 0 || n > 31) {
                    errors.add("Hạn xác nhận công phải từ 0 đến 31 ngày (0 = không giới hạn)");
                } else {
                    d.setConfirmDays(n.intValue());
                }
            }
        } else {
            d.setConfirmDays(existing == null ? null : existing.getConfirmDays());
        }
        d.setDeviceUrl(emptyToNull(form.get("deviceUrl")));
        d.setDeviceUsername(emptyToNull(form.get("deviceUsername")));
        String pw = form.get("devicePassword");
        // để trống ô mật khẩu khi sửa = giữ mật khẩu cũ
        d.setDevicePassword(pw == null || pw.isEmpty() ? (existing == null ? null : existing.getDevicePassword()) : pw);

        // GIỜ CHẤM CÔNG: HCNS đơn vị cấu hình ở /attendance/schedule. Form không có ô = giữ nguyên (đơn vị mới: mặc định)
        d.setLateAfter(form.containsKey("lateAfter") ? time(form, "lateAfter", "Báo đi muộn sau", errors)
                : existing != null ? existing.getLateAfter() : CompanyUnit.DEFAULT_LATE_AFTER);
        d.setViolationAfter(form.containsKey("violationAfter") ? time(form, "violationAfter", "Tính vi phạm sau", errors)
                : existing != null ? existing.getViolationAfter() : CompanyUnit.DEFAULT_VIOLATION_AFTER);
        d.setNoonSplit(form.containsKey("noonSplit") ? time(form, "noonSplit", "Mốc chia ca", errors)
                : existing != null ? existing.getNoonSplit() : CompanyUnit.DEFAULT_NOON);
        d.setEarlyBefore(form.containsKey("earlyBefore") ? time(form, "earlyBefore", "Báo về sớm trước", errors)
                : existing != null ? existing.getEarlyBefore() : CompanyUnit.DEFAULT_EARLY_BEFORE);
        if (d.getLateAfter() != null && d.getNoonSplit() != null && !d.getLateAfter().isBefore(d.getNoonSplit())) {
            errors.add("Giờ báo đi muộn phải trước mốc chia ca sáng/chiều");
        }
        if (d.getNoonSplit() != null && d.getEarlyBefore() != null && !d.getNoonSplit().isBefore(d.getEarlyBefore())) {
            errors.add("Giờ báo về sớm phải sau mốc chia ca sáng/chiều");
        }

        // NGÀY LÀM TRONG TUẦN (wd1..wd7 = T2..CN, giá trị F/M/O). Form không có các ô này = giữ nguyên
        String wd = readWorkDays(form);
        if (wd == null) {
            d.setWorkDays(existing == null ? null : existing.getWorkDays());
        } else if (!CompanyUnit.validWorkDays(wd)) {
            errors.add("Phải có ít nhất 1 ngày đi làm trong tuần");
        } else {
            d.setWorkDays(wd);
        }

        // LỊCH KÉO CÔNG: kiểu, khung giờ, bước lặp, ngày trong tuần (form không có = giữ nguyên)
        if (form.containsKey("syncForm")) {
            String mode = CompanyUnit.MODE_INTERVAL.equals(form.get("syncMode")) ? CompanyUnit.MODE_INTERVAL : CompanyUnit.MODE_TIMES;
            d.setSyncMode(mode);
            StringBuilder days = new StringBuilder();
            for (int i = 1; i <= 7; i++) {
                days.append(form.containsKey("sd" + i) ? '1' : '0');
            }
            if (!days.toString().contains("1")) {
                errors.add("Chọn ít nhất 1 ngày kéo công trong tuần");
            }
            d.setSyncDays(days.toString().equals("1111111") ? null : days.toString());
            if (CompanyUnit.MODE_INTERVAL.equals(mode)) {
                LocalTime sf = time(form, "syncFrom", "Kéo từ giờ", errors);
                LocalTime stt = time(form, "syncTo", "Kéo đến giờ", errors);
                Long every = parseLong(form.get("syncEvery"));
                if (sf == null || stt == null) {
                    errors.add("Nhập khung giờ kéo công (từ giờ - đến giờ)");
                } else if (!sf.isBefore(stt)) {
                    errors.add("Giờ bắt đầu kéo phải trước giờ kết thúc");
                }
                if (every == null || every < 5 || every > 720) {
                    errors.add("Bước lặp từ 5 đến 720 phút");
                }
                d.setSyncFrom(sf);
                d.setSyncTo(stt);
                d.setSyncEvery(every == null ? null : every.intValue());
            } else {
                d.setSyncFrom(existing == null ? null : existing.getSyncFrom());
                d.setSyncTo(existing == null ? null : existing.getSyncTo());
                d.setSyncEvery(existing == null ? null : existing.getSyncEvery());
            }
        } else if (existing != null) {
            d.setSyncMode(existing.getSyncMode());
            d.setSyncFrom(existing.getSyncFrom());
            d.setSyncTo(existing.getSyncTo());
            d.setSyncEvery(existing.getSyncEvery());
            d.setSyncDays(existing.getSyncDays());
        }
        if (form.containsKey("syncTimes")) {
            String st = CompanyUnit.normalizeSyncTimes(form.get("syncTimes"));
            if (st == null) {
                errors.add("Giờ kéo công dạng 09:00, 12:30, 20:00 (tối đa 12 giờ / ngày)");
            } else {
                d.setSyncTimes(st.isEmpty() ? null : st);
            }
        } else {
            d.setSyncTimes(existing == null ? null : existing.getSyncTimes());
        }

        String from = text(form.get("syncFromDate"));
        if (!from.isEmpty()) {
            try {
                d.setSyncFromDate(LocalDate.parse(from));
            } catch (DateTimeParseException e) {
                errors.add("Ngày bắt đầu kéo chấm công không hợp lệ");
            }
        }

        if (!d.getCode().matches("[A-Z][A-Z0-9]{0,9}")) {
            errors.add("Mã đơn vị: 1-10 ký tự, chữ in hoa không dấu và số, bắt đầu bằng chữ (VD TH, SG, HN2)");
        } else {
            unitRepository.findByCodeIgnoreCase(d.getCode())
                    .filter(o -> !o.getId().equals(d.getId()))
                    .ifPresent(o -> errors.add("Mã đơn vị " + d.getCode() + " đã có"));
        }
        if (existing != null && !existing.getCode().equalsIgnoreCase(d.getCode()) && !existing.isLegacyUnit()
                && userRepository.countByUnit_Id(existing.getId()) > 0) {
            errors.add("Không đổi được mã đơn vị khi đã có nhân viên (mã nhân viên đang dùng tiền tố " + existing.getCode() + ")");
        }
        if (d.getName().isEmpty()) {
            errors.add("Chưa nhập tên đơn vị");
        }
        if (d.isLegacyUnit()) {
            unitRepository.findFirstByLegacyTrue()
                    .filter(o -> !o.getId().equals(d.getId()))
                    .ifPresent(o -> errors.add("Đơn vị gốc đang là " + o.getCode() + " - chỉ được 1 đơn vị gốc"));
        }
        // Giám đốc đơn vị: HCNS đơn vị chọn sau khi đã có nhân viên (trang Chức vụ & phòng ban).

        if (!errors.isEmpty()) {
            model.addAttribute("errors", errors);
            model.addAttribute("unit", d);
            model.addAttribute("isNew", d.getId() == null);
            model.addAttribute("userCount", d.getId() == null ? 0 : userRepository.countByUnit_Id(d.getId()));
            return "units/edit";
        }

        CompanyUnit target = existing != null ? existing : new CompanyUnit();
        if (existing == null) {
            target.setCreatedAt(LocalDateTime.now());
        }
        target.setCode(d.getCode());
        target.setName(d.getName());
        target.setActive(d.getActive());
        target.setLegacy(d.getLegacy());
        target.setLateAfter(d.getLateAfter());
        target.setViolationAfter(d.getViolationAfter());
        target.setNoonSplit(d.getNoonSplit());
        target.setEarlyBefore(d.getEarlyBefore());
        target.setDirectorNo(d.getDirectorNo());
        target.setServiceDirectorNo(d.getServiceDirectorNo());
        target.setChiefAccountantNo(d.getChiefAccountantNo());
        target.setSlipCompany(d.getSlipCompany());
        target.setSlipPreparer(d.getSlipPreparer());
        target.setSlipPlace(d.getSlipPlace());
        target.setPayDay(d.getPayDay());
        target.setConfirmDays(d.getConfirmDays());
        target.setWorkDays(d.getWorkDays());
        target.setDeviceUrl(d.getDeviceUrl());
        target.setDeviceUsername(d.getDeviceUsername());
        target.setDevicePassword(d.getDevicePassword());
        target.setSyncFromDate(d.getSyncFromDate());
        target.setSyncTimes(d.getSyncTimes());
        target.setSyncMode(d.getSyncMode());
        target.setSyncFrom(d.getSyncFrom());
        target.setSyncTo(d.getSyncTo());
        target.setSyncEvery(d.getSyncEvery());
        target.setSyncDays(d.getSyncDays());
        unitRepository.save(target);
        unitScope.refresh();

        List<String> warn = new ArrayList<>();
        checkPerson(target, target.getDirectorNo(), "Giám đốc", warn);
        checkPerson(target, target.getServiceDirectorNo(), "Giám đốc dịch vụ", warn);
        checkPerson(target, target.getChiefAccountantNo(), "Kế toán trưởng", warn);
        ra.addFlashAttribute("message", "Đã lưu đơn vị " + target.getLabel());
        if (!warn.isEmpty()) {
            ra.addFlashAttribute("warning", String.join(" ", warn));
        }
        return "redirect:/units";
    }

    private void checkPerson(CompanyUnit unit, String no, String role, List<String> warn) {
        if (no == null || no.isBlank()) {
            return;
        }
        User p = userRepository.findByEmployeeNo(no).orElse(null);
        if (p == null) {
            warn.add(role + " " + no + " chưa có tài khoản (kéo nhân viên từ máy hoặc tạo tài khoản trước).");
        } else if (!Objects.equals(unitScope.unitKey(p), unit.getId())) {
            warn.add(role + " " + no + " đang thuộc đơn vị khác - chuyển người này về đơn vị " + unit.getCode() + " ở trang Tài khoản & chia đơn vị.");
        }
    }

    // =====================================================================
    // MÁY CHẤM CÔNG
    // =====================================================================

    @PostMapping("/{id}/test")
    public String test(@PathVariable Long id, Principal principal, RedirectAttributes ra) {
        if (denied(principal)) {
            return "redirect:/?error=no-permission";
        }
        CompanyUnit u = unitRepository.findById(id).orElse(null);
        if (u == null || !u.isHasDevice()) {
            ra.addFlashAttribute("error", "Đơn vị chưa khai địa chỉ máy chấm công");
            return "redirect:/units";
        }
        String json;
        try {
            HikvisionApiClient.use(u.getDeviceUrl(), u.getDeviceUsername(), u.getDevicePassword());
            json = hikvisionApiClient.fetchUsers("1", 0, 1);
        } finally {
            HikvisionApiClient.clear();
        }
        boolean ok = json != null && (json.contains("UserInfoSearch") || json.contains("totalMatches"));
        u.setLastStatus(ok ? "OK" : "ERROR");
        u.setLastMessage(ok ? "Kết nối được máy " + u.getDeviceUrl()
                : "Không kết nối được máy " + u.getDeviceUrl() + " (máy tắt, sai IP, sai mật khẩu, hoặc máy chủ HRM không cùng mạng)");
        unitRepository.save(u);
        ra.addFlashAttribute(ok ? "message" : "error", u.getLabel() + ": " + u.getLastMessage());
        return "redirect:/units";
    }

    @PostMapping("/{id}/sync-users")
    public String syncUsers(@PathVariable Long id, Principal principal, RedirectAttributes ra) {
        if (denied(principal)) {
            return "redirect:/?error=no-permission";
        }
        CompanyUnit u = unitRepository.findById(id).orElse(null);
        if (u == null || !u.isHasDevice()) {
            ra.addFlashAttribute("error", "Đơn vị chưa khai địa chỉ máy chấm công");
            return "redirect:/units";
        }
        if (!start(id, () -> {
            int n = userSyncService.syncUsers(unitRepository.findById(id).orElseThrow());
            status(id, "OK", "Kéo nhân viên: thêm mới " + n + " người, bỏ qua " + userSyncService.getLastSkipped()
                    + " người đã có (giữ nguyên phòng ban, chức vụ, phép năm)");
        })) {
            ra.addFlashAttribute("error", "Máy " + u.getCode() + " đang đồng bộ, chờ xong rồi bấm lại");
        } else {
            ra.addFlashAttribute("message", "Đang kéo nhân viên từ máy " + u.getCode() + ", tải lại trang sau ít phút");
        }
        return "redirect:/units";
    }

    @PostMapping("/{id}/sync-attendance")
    public String syncAttendance(@PathVariable Long id, Principal principal, RedirectAttributes ra) {
        if (denied(principal)) {
            return "redirect:/?error=no-permission";
        }
        CompanyUnit u = unitRepository.findById(id).orElse(null);
        if (u == null || !u.isHasDevice()) {
            ra.addFlashAttribute("error", "Đơn vị chưa khai địa chỉ máy chấm công");
            return "redirect:/units";
        }
        if (!start(id, () -> attendanceService.syncAttendanceForUnit(id))) {
            ra.addFlashAttribute("error", "Máy " + u.getCode() + " đang đồng bộ, chờ xong rồi bấm lại");
        } else {
            ra.addFlashAttribute("message", "Đang kéo chấm công máy " + u.getCode() + ", tải lại trang sau ít phút");
        }
        return "redirect:/units";
    }

    private boolean start(Long id, Runnable job) {
        return syncRunner.start(id, job);   // dùng chung hàng đợi với lịch tự kéo công (không kéo 2 việc cùng lúc)
    }

    /** KÉO LẠI CÔNG theo khoảng ngày chọn (VD máy mất mạng 3 ngày) */
    @PostMapping("/{id}/sync-range")
    public String syncRange(@PathVariable Long id, @RequestParam String from, @RequestParam String to,
                            Principal principal, RedirectAttributes ra) {
        if (denied(principal)) {
            return "redirect:/?error=no-permission";
        }
        CompanyUnit u = unitRepository.findById(id).orElse(null);
        String err = rangeError(u, from, to);
        if (err != null) {
            ra.addFlashAttribute("error", err);
            return "redirect:/units";
        }
        LocalDate f = LocalDate.parse(from), t = LocalDate.parse(to);
        if (!syncRunner.startRange(id, f, t)) {
            ra.addFlashAttribute("error", "Máy " + u.getCode() + " đang đồng bộ, chờ xong rồi bấm lại");
        } else {
            ra.addFlashAttribute("message", "Đang kéo lại công máy " + u.getCode() + " từ " + f + " đến " + t + ", tải lại trang sau ít phút");
        }
        return "redirect:/units";
    }

    /** Kiểm tra khoảng ngày kéo lại công (tối đa 62 ngày, không quá hôm nay) */
    public static String rangeError(CompanyUnit u, String from, String to) {
        if (u == null || !u.isHasDevice()) {
            return "Đơn vị chưa khai địa chỉ máy chấm công";
        }
        try {
            LocalDate f = LocalDate.parse(from), t = LocalDate.parse(to);
            if (t.isBefore(f)) {
                return "\"Đến ngày\" phải sau \"Từ ngày\"";
            }
            if (f.plusDays(62).isBefore(t)) {
                return "Mỗi lần kéo tối đa 62 ngày";
            }
            if (f.isAfter(LocalDate.now())) {
                return "Không kéo được ngày tương lai";
            }
            return null;
        } catch (DateTimeParseException | NullPointerException e) {
            return "Ngày không hợp lệ";
        }
    }

    private void status(Long id, String st, String msg) {
        unitRepository.findById(id).ifPresent(u -> {
            u.setLastStatus(st);
            u.setLastMessage(msg.length() > 1000 ? msg.substring(0, 1000) : msg);
            u.setLastSyncAt(LocalDateTime.now());
            unitRepository.save(u);
        });
    }

    // =====================================================================

    private static LocalTime time(Map<String, String> form, String key, String label, List<String> errors) {
        String v = text(form.get(key));
        if (v.isEmpty()) {
            return null;
        }
        try {
            return LocalTime.parse(v.length() == 5 ? v : v.substring(0, Math.min(5, v.length())));
        } catch (DateTimeParseException e) {
            errors.add(label + " phải dạng 07:35");
            return null;
        }
    }

    /** Áp lịch kéo công từ form vào đơn vị (trang HCNS); ô nào không có trên form thì giữ nguyên */
    public static void applySyncForm(Map<String, String> form, CompanyUnit u, List<String> errors) {
        if (form.containsKey("syncForm")) {
            String mode = CompanyUnit.MODE_INTERVAL.equals(form.get("syncMode")) ? CompanyUnit.MODE_INTERVAL : CompanyUnit.MODE_TIMES;
            StringBuilder days = new StringBuilder();
            for (int i = 1; i <= 7; i++) {
                days.append(form.containsKey("sd" + i) ? '1' : '0');
            }
            if (!days.toString().contains("1")) {
                errors.add("Chọn ít nhất 1 ngày kéo công trong tuần");
                return;
            }
            if (CompanyUnit.MODE_INTERVAL.equals(mode)) {
                LocalTime sf = time(form, "syncFrom", "Kéo từ giờ", errors);
                LocalTime stt = time(form, "syncTo", "Kéo đến giờ", errors);
                Long every = parseLong(form.get("syncEvery"));
                if (sf == null || stt == null || !sf.isBefore(stt)) {
                    errors.add("Khung giờ kéo công: giờ bắt đầu phải trước giờ kết thúc");
                }
                if (every == null || every < 5 || every > 720) {
                    errors.add("Bước lặp từ 5 đến 720 phút");
                }
                if (!errors.isEmpty()) {
                    return;
                }
                u.setSyncFrom(sf);
                u.setSyncTo(stt);
                u.setSyncEvery(every.intValue());
            }
            u.setSyncMode(mode);
            u.setSyncDays(days.toString().equals("1111111") ? null : days.toString());
        }
        if (form.containsKey("syncTimes")) {
            String st = CompanyUnit.normalizeSyncTimes(form.get("syncTimes"));
            if (st == null) {
                errors.add("Giờ kéo công dạng 09:00, 12:30, 20:00 (tối đa 12 giờ / ngày)");
            } else {
                u.setSyncTimes(st.isEmpty() || st.equals(CompanyUnit.DEFAULT_SYNC_TIMES) ? null : st);
            }
        }
    }

    /** wd1..wd7 (T2..CN) -> "FFFFFMO"; null nếu form không có đủ 7 ô */
    public static String readWorkDays(Map<String, String> form) {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 7; i++) {
            String v = text(form.get("wd" + i)).toUpperCase();
            if (v.length() != 1 || "FMO".indexOf(v.charAt(0)) < 0) {
                return null;
            }
            b.append(v.charAt(0));
        }
        return b.toString();
    }

    private static String text(String s) {
        return s == null ? "" : s.trim();
    }

    private static String emptyToNull(String s) {
        String t = text(s);
        return t.isEmpty() ? null : t;
    }

    static Long parseLong(String s) {
        try {
            return s == null || s.isBlank() ? null : Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
