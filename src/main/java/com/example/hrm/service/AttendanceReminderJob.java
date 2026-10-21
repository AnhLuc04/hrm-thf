package com.example.hrm.service;

import com.example.hrm.dto.AttendanceAlertDTO;
import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.entity.User;
import com.example.hrm.repository.AttendanceConfirmationRepository;
import com.example.hrm.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 🔔 NHẮC XÁC NHẬN CÔNG (chạy nền, mặc định 09:30 và 17:30 hằng ngày):
 *   1. Ngày có lỗi chấm công (đi muộn, về sớm, quên chấm, không chấm) mà chưa gửi giải trình
 *      -> báo nhân viên 1 lần cho mỗi ca lỗi, kèm hạn chót.
 *   2. Còn ≤ 1 ngày là hết hạn mà vẫn chưa gửi -> nhắc thêm 1 lần.
 * Chỉ xét các ngày còn trong hạn xác nhận của đơn vị (mặc định 7 ngày). Ngày đã khoá thì thôi nhắc.
 *
 * application.properties:
 *   hrm.notify.attendance-reminder=true          (false = tắt)
 *   hrm.notify.attendance-cron=0 30 9,17 * * *
 */
@Service
public class AttendanceReminderJob {

    private static final Logger log = LoggerFactory.getLogger(AttendanceReminderJob.class);
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd/MM");

    private final AttendanceService attendanceService;
    private final AttendanceConfirmationRepository confirmationRepository;
    private final UserRepository userRepository;
    private final UnitScope unitScope;
    private final ConfirmDeadlineService deadlineService;
    private final NotificationService notificationService;

    @Value("${hrm.notify.attendance-reminder:true}")
    private boolean enabled = true;

    public AttendanceReminderJob(AttendanceService attendanceService, AttendanceConfirmationRepository confirmationRepository,
                                 UserRepository userRepository, UnitScope unitScope, ConfirmDeadlineService deadlineService,
                                 NotificationService notificationService) {
        this.attendanceService = attendanceService;
        this.confirmationRepository = confirmationRepository;
        this.userRepository = userRepository;
        this.unitScope = unitScope;
        this.deadlineService = deadlineService;
        this.notificationService = notificationService;
    }

    @Scheduled(cron = "${hrm.notify.attendance-cron:0 30 9,17 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void run() {
        if (!enabled) {
            return;
        }
        LocalDate today = LocalDate.now(VN);
        int sent = 0;
        for (User u : userRepository.findAll()) {
            try {
                sent += remind(u, today);
            } catch (RuntimeException e) {
                log.warn("NHAC XAC NHAN CONG LOI ({}): {}", u.getEmployeeNo(), e.getMessage());
            }
        }
        log.info("NHAC XAC NHAN CONG: da gui {} thong bao", sent);
    }

    /** Nhắc 1 nhân viên; trả về số thông báo đã gửi */
    public int remind(User u, LocalDate today) {
        String no = u.getEmployeeNo();
        if (no == null || no.isBlank() || unitScope.isGlobalAdmin(u)) {
            return 0;
        }
        CompanyUnit unit = unitScope.unitOf(u);
        int days = deadlineService.days(unit);
        // xét lùi đúng số ngày còn hạn (0 = không giới hạn: xét 7 ngày gần nhất để không nhắc dồn quá nhiều)
        LocalDate from = today.minusDays(days > 0 ? days : 7);
        List<AttendanceAlertDTO> alerts = attendanceService.getAttendanceIssues(no, from, today);
        int sent = 0;
        for (AttendanceAlertDTO a : alerts) {
            if (a.getDate() == null || a.isLocked()) {
                continue;
            }
            // đã gửi giải trình cho ca này rồi (đang chờ / bị trả lại...) thì không nhắc
            if (a.getSessionType() != null
                    && confirmationRepository.existsByEmployeeNoAndAttendanceDateAndSessionType(no, a.getDate(), a.getSessionType())) {
                continue;
            }
            String key = a.getDate() + ":" + a.getSessionType();
            String what = AttendanceService.issueLabel(a.getIssueType()) + " " + AttendanceService.sessionLabel(a.getSessionType());
            String time = a.getCheckIn() != null ? " (vào " + a.getCheckIn().toString().substring(0, 5) + ")"
                    : a.getCheckOut() != null ? " (ra " + a.getCheckOut().toString().substring(0, 5) + ")" : "";
            String due = a.getDeadline() == null ? "Gửi giải trình để không bị tính thiếu công."
                    : "Gửi giải trình trước hết ngày " + a.getDeadline().format(DM)
                    + (a.getDaysLeft() != null ? " (còn " + a.getDaysLeft() + " ngày)." : ".");
            String link = "/attendance/confirm/" + no;
            if (notificationService.send(u.getId(), "ATTENDANCE",
                    "Ngày " + a.getDate().format(DM) + ": " + what + time, due, link, "ISSUE:" + key)) {
                sent++;
            }
            if (a.getDaysLeft() != null && a.getDaysLeft() <= 1
                    && notificationService.send(u.getId(), "ATTENDANCE",
                    "⏰ Sắp hết hạn giải trình ngày " + a.getDate().format(DM),
                    what + time + ". Hạn chót hết ngày " + a.getDeadline().format(DM) + ", quá hạn hệ thống tự khoá.",
                    link, "DUE:" + key)) {
                sent++;
            }
        }
        return sent;
    }
}
