package com.example.hrm.service;

import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.repository.CompanyUnitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * KÉO CÔNG TỪ MÁY CHẤM CÔNG THEO GIỜ CÀI ĐẶT CỦA TỪNG ĐƠN VỊ (thay 2 lịch cố định 09:00 / 20:00 của bản cũ;
 * đơn vị chưa cài giờ thì vẫn 09:00 và 20:00 như cũ).
 * Mỗi phút kiểm tra 1 lần; đến giờ nào thì kéo đơn vị đó. Các lần kéo chạy nền, mỗi lúc 1 việc (máy chấm công yếu).
 *
 *   hrm.sync.enabled=true   (false = tắt tự kéo; vẫn bấm kéo tay được)
 */
@Service
public class UnitSyncRunner {

    private static final Logger log = LoggerFactory.getLogger(UnitSyncRunner.class);
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    /** khoá cho "máy cũ" (chưa có đơn vị nào) */
    private static final Long LEGACY = -1L;

    private final AttendanceService attendanceService;
    private final CompanyUnitRepository unitRepository;

    @Value("${hrm.sync.enabled:true}")
    private boolean enabled = true;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "unit-sync");
        t.setDaemon(true);
        return t;
    });
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    /** đơn vị -> "yyyy-MM-dd HH:mm" lần tự kéo gần nhất (không chạy 2 lần trong 1 phút) */
    private final Map<Long, String> lastSlot = new ConcurrentHashMap<>();

    public UnitSyncRunner(AttendanceService attendanceService, CompanyUnitRepository unitRepository) {
        this.attendanceService = attendanceService;
        this.unitRepository = unitRepository;
    }

    public boolean isRunning(Long unitId) {
        return running.contains(unitId == null ? LEGACY : unitId);
    }

    public Set<Long> runningIds() {
        return Set.copyOf(running);
    }

    /** Chạy 1 việc nền cho đơn vị; false nếu đơn vị đó đang có việc chạy */
    public boolean start(Long unitId, Runnable job) {
        Long key = unitId == null ? LEGACY : unitId;
        if (!running.add(key)) {
            return false;
        }
        executor.submit(() -> {
            try {
                job.run();
            } catch (RuntimeException e) {
                log.error("DONG BO DON VI {} LOI", unitId, e);
                if (unitId != null) {
                    unitRepository.findById(unitId).ifPresent(u -> {
                        u.setLastStatus("ERROR");
                        String m = "Lỗi: " + e.getMessage();
                        u.setLastMessage(m.length() > 1000 ? m.substring(0, 1000) : m);
                        u.setLastSyncAt(LocalDateTime.now(VN));
                        unitRepository.save(u);
                    });
                }
            } finally {
                running.remove(key);
            }
        });
        return true;
    }

    /** Kéo lại công theo khoảng ngày (nền) */
    public boolean startRange(Long unitId, LocalDate from, LocalDate to) {
        return start(unitId, () -> attendanceService.syncAttendanceForUnit(unitId, from, to));
    }

    /** Mỗi phút: đơn vị nào tới giờ kéo công thì kéo */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Ho_Chi_Minh")
    public void tick() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(VN);
        String hm = now.toLocalTime().format(HM);
        String slot = now.toLocalDate() + " " + hm;
        List<CompanyUnit> units = unitRepository.findAllByOrderByCodeAsc().stream()
                .filter(u -> u.isEnabled() && u.isHasDevice()).toList();
        if (units.isEmpty()) {
            // chưa khai đơn vị nào: máy cũ, giờ cũ
            if (List.of(CompanyUnit.DEFAULT_SYNC_TIMES.split(",")).contains(hm) && !slot.equals(lastSlot.put(LEGACY, slot))) {
                log.info("TU KEO CONG (may cu) luc {}", hm);
                start(null, () -> attendanceService.syncAttendanceForUnit(null));
            }
            return;
        }
        for (CompanyUnit u : units) {
            if (u.isSyncSlot(now) && !slot.equals(lastSlot.put(u.getId(), slot))) {
                log.info("TU KEO CONG don vi {} luc {}", u.getCode(), hm);
                Long id = u.getId();
                if (!start(id, () -> attendanceService.syncAttendanceForUnit(id))) {
                    log.info("Don vi {} dang keo cong, bo qua lan {}", u.getCode(), hm);
                }
            }
        }
    }

    /** Lần kéo tiếp theo của đơn vị (để hiển thị) */
    public static String nextTime(CompanyUnit u) {
        if (u == null) {
            return "—";
        }
        LocalDateTime now = LocalDateTime.now(VN);
        LocalDateTime n = u.nextSyncAfter(now);
        if (n == null) {
            return "chưa có lịch";
        }
        String hm = n.toLocalTime().format(HM);
        return n.toLocalDate().equals(now.toLocalDate()) ? hm + " hôm nay"
                : n.toLocalDate().equals(now.toLocalDate().plusDays(1)) ? hm + " ngày mai"
                : hm + " " + n.toLocalDate().format(DateTimeFormatter.ofPattern("dd/MM"));
    }
}
