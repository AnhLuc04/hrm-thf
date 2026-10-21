package com.example.hrm.service;

import com.example.hrm.dto.AttendanceAlertDTO;
import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.entity.ConfirmExtension;
import com.example.hrm.entity.User;
import com.example.hrm.repository.ConfirmExtensionRepository;
import com.example.hrm.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * HẠN XÁC NHẬN CÔNG.
 *   Ngày lỗi chấm công (đi muộn, về sớm, quên chấm, không chấm) phải gửi giải trình trong N ngày
 *   (N theo đơn vị, mặc định 7). Muộn ngày 09/10 -> gửi được đến hết ngày 16/10; từ 17/10 tự khóa,
 *   không còn để dồn đến cuối tháng mới xác nhận.
 *   HCNS / quản trị / Giám đốc đơn vị mở lại được cho từng nhân viên (ConfirmExtension).
 *
 * hrm.confirm-deadline.start (VD 2026-10-10): ngày lỗi TRƯỚC mốc này tính hạn từ mốc,
 * để hôm đưa lên không khóa ngay các ngày cũ trong tháng. Trống = tính từ ngày lỗi.
 */
@Service
public class ConfirmDeadlineService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ConfirmExtensionRepository extensionRepository;
    private final UserRepository userRepository;
    private final UnitScope unitScope;
    private final LocalDate start;

    public ConfirmDeadlineService(ConfirmExtensionRepository extensionRepository, UserRepository userRepository,
                                  UnitScope unitScope,
                                  @Value("${hrm.confirm-deadline.start:}") String start) {
        this.extensionRepository = extensionRepository;
        this.userRepository = userRepository;
        this.unitScope = unitScope;
        LocalDate s = null;
        try {
            s = start == null || start.isBlank() ? null : LocalDate.parse(start.trim());
        } catch (RuntimeException ignore) {
            // cấu hình sai -> coi như không có mốc
        }
        this.start = s;
    }

    /** Trạng thái hạn của 1 ngày lỗi */
    public record State(LocalDate deadline, boolean locked, int daysLeft, boolean extended) {
        static final State NONE = new State(null, false, -1, false);
    }

    public LocalDate today() {
        return LocalDate.now(VN);
    }

    public int days(CompanyUnit unit) {
        return unit == null ? CompanyUnit.DEFAULT_CONFIRM_DAYS : unit.confirmDaysOrDefault();
    }

    /** Hạn chót (hết ngày này) theo quy định đơn vị, null = không giới hạn */
    public LocalDate deadline(CompanyUnit unit, LocalDate date) {
        int n = days(unit);
        if (n <= 0 || date == null) {
            return null;
        }
        LocalDate base = start != null && date.isBefore(start) ? start : date;
        return base.plusDays(n);
    }

    public State state(User employee, LocalDate date, List<ConfirmExtension> extensions, LocalDate today) {
        LocalDate dl = deadline(employee == null ? unitScope.legacyUnit() : unitScope.unitOf(employee), date);
        if (dl == null) {
            return State.NONE;
        }
        LocalDate effective = dl;
        boolean extended = false;
        for (ConfirmExtension e : extensions) {
            if (e.covers(date) && e.getOpenUntil().isAfter(effective)) {
                effective = e.getOpenUntil();
                extended = true;
            }
        }
        return new State(effective, today.isAfter(effective), (int) ChronoUnit.DAYS.between(today, effective), extended);
    }

    public State state(String employeeNo, LocalDate date) {
        LocalDate today = today();
        User u = userRepository.findByEmployeeNo(employeeNo).orElse(null);
        return state(u, date, extensionRepository.findByEmployeeNoAndOpenUntilGreaterThanEqual(employeeNo, today), today);
    }

    /** Gọi trước khi tạo đơn giải trình: quá hạn thì báo lỗi */
    public void requireOpen(String employeeNo, LocalDate date) {
        State st = state(employeeNo, date);
        if (st.locked()) {
            throw new IllegalStateException("Quá hạn xác nhận công ngày " + date.format(DMY)
                    + " (hạn hết ngày " + st.deadline().format(DMY) + "). Liên hệ HCNS để được mở lại.");
        }
    }

    /** Gắn hạn / khóa vào danh sách lỗi chấm công để trang giải trình hiển thị */
    public void annotate(String employeeNo, List<AttendanceAlertDTO> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return;
        }
        LocalDate today = today();
        User u = userRepository.findByEmployeeNo(employeeNo).orElse(null);
        List<ConfirmExtension> ext = extensionRepository.findByEmployeeNoAndOpenUntilGreaterThanEqual(employeeNo, today);
        for (AttendanceAlertDTO a : alerts) {
            State st = state(u, a.getDate(), ext, today);
            a.setDeadline(st.deadline());
            a.setLocked(st.locked());
            a.setDaysLeft(st.deadline() == null ? null : st.daysLeft());
            a.setExtended(st.extended());
        }
    }

    // ================================================================== MỞ LẠI

    @Transactional
    public ConfirmExtension grant(User by, User employee, LocalDate from, LocalDate to, LocalDate openUntil, String reason) {
        if (from == null || to == null || openUntil == null) {
            throw new IllegalArgumentException("Thiếu ngày");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("\"Đến ngày\" phải sau \"Từ ngày\"");
        }
        LocalDate today = today();
        if (openUntil.isBefore(today)) {
            throw new IllegalArgumentException("Hạn mới phải từ hôm nay trở đi");
        }
        if (openUntil.isAfter(today.plusDays(31))) {
            throw new IllegalArgumentException("Chỉ mở lại tối đa 31 ngày");
        }
        ConfirmExtension e = new ConfirmExtension();
        e.setEmployeeNo(employee.getEmployeeNo());
        e.setFromDate(from);
        e.setToDate(to);
        e.setOpenUntil(openUntil);
        e.setReason(reason == null ? null : reason.trim().substring(0, Math.min(300, reason.trim().length())));
        e.setGrantedBy(by.getUsername());
        e.setGrantedByName(by.getFullName());
        e.setCreatedAt(LocalDateTime.now(VN));
        return extensionRepository.save(e);
    }
}
