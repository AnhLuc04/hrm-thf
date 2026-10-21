package com.example.hrm.controller;

import com.example.hrm.entity.Announcement;
import com.example.hrm.entity.AnnouncementFile;
import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.entity.User;
import com.example.hrm.repository.AnnouncementFileRepository;
import com.example.hrm.repository.AnnouncementRepository;
import com.example.hrm.repository.CompanyUnitRepository;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.service.NewsFileStorage;
import com.example.hrm.service.UnitScope;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * BẢNG TIN theo đơn vị.
 *   Xem : tin toàn hệ thống + tin của đơn vị mình (tin gửi riêng 1 phòng: chỉ phòng đó và người đăng được xem)
 *   Đăng: HCNS / Hành chính, quản trị (role ADMIN), Giám đốc của đơn vị -> chỉ trong đơn vị mình
 *         quản trị hệ thống -> toàn hệ thống hoặc 1 đơn vị bất kỳ
 *   Ghim: HCNS, quản trị. Xoá: người đăng, HCNS cùng đơn vị, quản trị hệ thống.
 */
@Controller
public class NewsController {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.service.NotificationService notificationService;   // 🔔 chuông thông báo

    private final AnnouncementRepository repository;
    private final CompanyUnitRepository unitRepository;
    private final UserRepository userRepository;
    private final UnitScope unitScope;
    private final NewsFileStorage storage;                 // ảnh / file đính kèm
    private final AnnouncementFileRepository fileRepository;

    public NewsController(AnnouncementRepository repository, CompanyUnitRepository unitRepository,
                          UserRepository userRepository, UnitScope unitScope,
                          NewsFileStorage storage, AnnouncementFileRepository fileRepository) {
        this.repository = repository;
        this.unitRepository = unitRepository;
        this.userRepository = userRepository;
        this.unitScope = unitScope;
        this.storage = storage;
        this.fileRepository = fileRepository;
    }

    // =====================================================================
    // QUYỀN
    // =====================================================================

    static boolean isHr(User u) {
        String d = u == null || u.getDepartment() == null ? "" : u.getDepartment().trim();
        return d.equalsIgnoreCase("HCNS") || d.contains("Hành chính") || d.toLowerCase().contains("nhân sự");
    }

    boolean canPost(User u) {
        return u != null && (unitScope.isGlobalAdmin(u) || isHr(u) || UnitScope.isAdmin(u) || unitScope.isDirector(u));
    }

    boolean canPin(User u) {
        return u != null && (unitScope.isGlobalAdmin(u) || isHr(u) || UnitScope.isAdmin(u));
    }

    boolean canSee(User u, Announcement a) {
        if (u == null || a == null) {
            return false;
        }
        if (unitScope.isGlobalAdmin(u)) {
            return true;
        }
        if (a.getUnit() == null) {
            return true;
        }
        if (!Objects.equals(a.getUnit().getId(), unitScope.unitKey(u))) {
            return false;   // 🔒 tin của đơn vị khác
        }
        if (a.getDepartment() == null || a.getDepartment().isBlank()) {
            return true;
        }
        return a.getDepartment().trim().equalsIgnoreCase(u.getDepartment() == null ? "" : u.getDepartment().trim())
                || Objects.equals(a.getCreatedBy(), u.getUsername()) || canPost(u);
    }

    boolean canDelete(User u, Announcement a) {
        if (u == null) {
            return false;
        }
        if (unitScope.isGlobalAdmin(u) || Objects.equals(a.getCreatedBy(), u.getUsername())) {
            return true;
        }
        return (isHr(u) || UnitScope.isAdmin(u)) && a.getUnit() != null
                && Objects.equals(a.getUnit().getId(), unitScope.unitKey(u));
    }

    // =====================================================================
    // TRANG
    // =====================================================================

    @GetMapping("/news")
    public String list(Model model) {
        User me = unitScope.currentUser();
        if (me == null) {
            return "redirect:/login";
        }
        LocalDate today = LocalDate.now(VN);
        Long view = unitScope.isGlobalAdmin(me) ? unitScope.scopeUnitId() : null;
        List<Announcement> news = repository.findTop300ByOrderByPinnedDescCreatedAtDesc().stream()
                .filter(a -> canSee(me, a))
                .filter(a -> view == null || a.getUnit() == null || Objects.equals(a.getUnit().getId(), view))
                .filter(a -> !a.isExpired(today) || canDelete(me, a))
                .toList();

        boolean global = unitScope.isGlobalAdmin(me);
        CompanyUnit myUnit = unitScope.unitOf(me);
        TreeSet<String> depts = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (User u : global ? userRepository.findAll() : unitScope.users()) {
            if (u.getDepartment() != null && !u.getDepartment().isBlank()) {
                depts.add(u.getDepartment().trim());
            }
        }
        model.addAttribute("news", news);
        model.addAttribute("today", today);
        model.addAttribute("canPost", canPost(me));
        model.addAttribute("canPin", canPin(me));
        model.addAttribute("isGlobal", global);
        model.addAttribute("units", global ? unitRepository.findAllByOrderByCodeAsc() : List.of());
        model.addAttribute("myUnit", myUnit);
        model.addAttribute("departments", depts);
        model.addAttribute("canDelete", (Predicate<Announcement>) a -> canDelete(me, a));
        model.addAttribute("fileAccept", NewsFileStorage.ACCEPT);
        model.addAttribute("maxFiles", NewsFileStorage.MAX_FILES);
        return "news";
    }

    @PostMapping("/news")
    public String post(@RequestParam String title,
                       @RequestParam(required = false) String body,
                       @RequestParam(required = false) String unitId,
                       @RequestParam(required = false) String department,
                       @RequestParam(required = false) String pinned,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresOn,
                       @RequestParam(value = "files", required = false) List<MultipartFile> files,
                       RedirectAttributes ra) {
        User me = unitScope.currentUser();
        if (!canPost(me)) {
            ra.addFlashAttribute("error", "Chỉ HCNS, Ban giám đốc và quản trị được đăng tin");
            return "redirect:/news";
        }
        String t = title == null ? "" : title.trim();
        if (t.isEmpty() || t.length() > 200) {
            ra.addFlashAttribute("error", "Tiêu đề từ 1 đến 200 ký tự");
            return "redirect:/news";
        }
        // ẢNH / FILE: kiểm tra hết trước, lỗi 1 file thì không đăng gì (tránh tin thiếu ảnh)
        List<MultipartFile> uploads = files == null ? List.of()
                : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (uploads.size() > NewsFileStorage.MAX_FILES) {
            ra.addFlashAttribute("error", "Tối đa " + NewsFileStorage.MAX_FILES + " ảnh / file cho 1 tin");
            return "redirect:/news";
        }
        for (MultipartFile f : uploads) {
            String err = storage.validate(f);
            if (err != null) {
                ra.addFlashAttribute("error", err);
                return "redirect:/news";
            }
        }

        Announcement a = new Announcement();
        a.setTitle(t);
        a.setBody(body == null ? "" : body.trim());
        if (unitScope.isGlobalAdmin(me)) {
            Long id = CompanyUnitController.parseLong(unitId);
            a.setUnit(id == null ? null : unitRepository.findById(id).orElse(null));   // trống = toàn hệ thống
        } else {
            a.setUnit(unitScope.unitOf(me));                                            // 🔒 chỉ đơn vị mình
        }
        a.setDepartment(a.getUnit() == null || department == null || department.isBlank() ? null : department.trim());
        a.setPinned(pinned != null && canPin(me));
        a.setExpiresOn(expiresOn);
        a.setCreatedBy(me.getUsername());
        a.setCreatedByName(me.getFullName());
        a.setCreatedAt(LocalDateTime.now(VN));

        List<String> saved = new ArrayList<>();
        try {
            for (MultipartFile f : uploads) {
                NewsFileStorage.Stored st = storage.save(f);
                saved.add(st.storedName());
                AnnouncementFile af = new AnnouncementFile();
                af.setAnnouncement(a);
                af.setOriginalName(st.originalName());
                af.setStoredName(st.storedName());
                af.setContentType(st.contentType());
                af.setSizeBytes(st.size());
                af.setImage(st.image());
                a.getFiles().add(af);
            }
            repository.save(a);
        } catch (IOException | RuntimeException e) {
            saved.forEach(storage::delete);   // không để file mồ côi trên đĩa
            ra.addFlashAttribute("error", "Không lưu được tin: " + e.getMessage());
            return "redirect:/news";
        }
        // 🔔 báo người được xem tin: toàn hệ thống / đơn vị / riêng 1 phòng
        int notified = 0;
        try {
            Long unitKey = a.getUnit() == null ? null : a.getUnit().getId();
            String dept = a.getDepartment();
            List<User> audience = userRepository.findAll().stream()
                    .filter(u -> !java.util.Objects.equals(u.getId(), me.getId()))
                    .filter(u -> unitKey == null || java.util.Objects.equals(unitScope.unitKey(u), unitKey))
                    .filter(u -> dept == null || (u.getDepartment() != null && u.getDepartment().trim().equalsIgnoreCase(dept)))
                    .toList();
            notified = notificationService.sendAll(audience, "NEWS", "Bảng tin: " + t,
                    a.getBody() == null || a.getBody().isBlank() ? (me.getFullName() + " đăng tin mới") : a.getBody(),
                    "/news", "NEWS:" + a.getId());
        } catch (RuntimeException ignore) {
            // thông báo lỗi không làm hỏng việc đăng tin
        }
        ra.addFlashAttribute("message", "Đã đăng tin \"" + t + "\" tới " + a.getScopeLabel()
                + (notified > 0 ? " (đã báo " + notified + " người)" : "")
                + (uploads.isEmpty() ? "" : " kèm " + uploads.size() + " ảnh / file"));
        return "redirect:/news";
    }

    @PostMapping("/news/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        User me = unitScope.currentUser();
        repository.findById(id).ifPresent(a -> {
            if (canDelete(me, a)) {
                List<String> stored = a.getFiles().stream().map(AnnouncementFile::getStoredName).toList();
                repository.delete(a);
                stored.forEach(storage::delete);
                ra.addFlashAttribute("message", "Đã xoá tin \"" + a.getTitle() + "\"");
            } else {
                ra.addFlashAttribute("error", "Bạn không xoá được tin này");
            }
        });
        return "redirect:/news";
    }

    /**
     * Tải / xem ảnh, file đính kèm. Cùng quyền với tin: tin của đơn vị khác, tin gửi riêng phòng khác,
     * tin đã hết hạn (với người không quản lý tin) đều trả 404.
     */
    @GetMapping("/news/file/{id}")
    public ResponseEntity<Resource> file(@PathVariable Long id,
                                         @RequestParam(required = false) String download) {
        User me = unitScope.currentUser();
        AnnouncementFile f = me == null ? null : fileRepository.findById(id).orElse(null);
        if (f == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Announcement a = f.getAnnouncement();
        LocalDate today = LocalDate.now(VN);
        if (!canSee(me, a) || (a.isExpired(today) && !canDelete(me, a))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Path p = storage.resolve(f.getStoredName());
        if (p == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        boolean inline = download == null && (f.isPicture() || "PDF".equals(f.getExt()));
        ContentDisposition cd = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(f.getOriginalName(), StandardCharsets.UTF_8).build();
        MediaType type;
        try {
            type = MediaType.parseMediaType(f.getContentType() == null ? "application/octet-stream" : f.getContentType());
        } catch (RuntimeException e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .body(new FileSystemResource(p));
    }
}
