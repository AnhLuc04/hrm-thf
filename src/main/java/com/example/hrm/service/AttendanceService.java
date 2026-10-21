package com.example.hrm.service;

import com.example.hrm.dto.AttendanceAlertDTO;
import com.example.hrm.entity.*;
import com.example.hrm.repository.*;
import com.example.hrm.util.HikvisionApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceService {

    private final HikvisionApiClient hikvisionApiClient;
    private final AttendanceRepository attendanceRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AttendanceConfirmationRepository confirmationRepository;
    private final HolidayConfigRepository holidayConfigRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveBalanceRepository leaveBalanceRepository; // Thêm để trừ phép năm
    private final CompanyUnitRepository unitRepository;          // ĐƠN VỊ: máy chấm công + giờ làm + người duyệt riêng
    private final UnitScope unitScope;
    private final ConfirmDeadlineService confirmDeadlineService;   // hạn xác nhận công N ngày, quá hạn tự khóa
    private final NotificationService notificationService;         // chuông thông báo: đơn mới / đã duyệt / trả lại
    private final UnitDepartmentRepository unitDepartmentRepository; // phòng ban + người duyệt do HCNS cấu hình
    private final ObjectMapper mapper = new ObjectMapper();

    /** Đơn vị đang được kéo chấm công trong luồng hiện tại (null = máy cũ / đơn vị gốc) */
    private final ThreadLocal<CompanyUnit> syncUnit = new ThreadLocal<>();

    @PersistenceContext
    private EntityManager entityManager;
    // =========================================================================
// 💡 FIX TRIỆT ĐỂ: TRANG DUYỆT ĐƠN CHO TỪNG PHÂN CẤP QUẢN LÝ VÀ GIÁM ĐỐC
// =========================================================================
    @Transactional(readOnly = true)
    public List<AttendanceConfirmation> getManagerPendingRequests(String username, LocalDate from, LocalDate to) {
        User manager = userRepository.findByEmployeeNo(username)
                .orElseGet(() -> userRepository.findByUsername(username).orElse(null));

        if (manager == null) {
            log.warn("Không tìm thấy thông tin tài khoản Quản lý với username/employeeNo: {}", username);
            return new ArrayList<>();
        }

        // 🌟 QUẢN TRỊ HỆ THỐNG: thấy đơn chờ duyệt của đơn vị đang chọn xem (hoặc mọi đơn vị)
        if (unitScope.isGlobalAdmin(manager)) {
            return confirmationRepository.findByAttendanceDateBetweenAndApprovalStatus(from, to, "PENDING").stream()
                    .filter(c -> {
                        User e = userRepository.findByEmployeeNo(c.getEmployeeNo()).orElse(null);
                        return e != null && unitScope.inScope(e);
                    }).toList();
        }

        String myNo = manager.getEmployeeNo() == null ? "" : manager.getEmployeeNo().trim();
        CompanyUnit myUnit = unitScope.unitOf(manager);

        List<AttendanceConfirmation> raw;

        // 🌟 GIÁM ĐỐC CỦA ĐƠN VỊ (bản cũ: mã 99999 hoặc username gdkd)
        if (unitScope.isDirector(manager)) {
            String key = unitScope.directorNo(myUnit);
            Map<Long, AttendanceConfirmation> m = new LinkedHashMap<>();
            for (AttendanceConfirmation c : confirmationRepository.findPendingRequestsByApprovedByKey(key, from, to)) {
                m.put(c.getId(), c);
            }
            if (!key.equalsIgnoreCase(myNo)) {
                for (AttendanceConfirmation c : confirmationRepository.findPendingRequestsByApprovedByKey(myNo, from, to)) {
                    m.put(c.getId(), c);
                }
            }
            boolean hasServiceDirector = !unitScope.serviceDirectorNo(myUnit).equalsIgnoreCase(key);
            // CHỈ BỎ DỊCH VỤ (giữ như bản cũ) khi đơn vị có Giám đốc dịch vụ riêng
            raw = m.values().stream().filter(confirm -> {
                User employee = userRepository.findByEmployeeNo(confirm.getEmployeeNo()).orElse(null);
                if (employee == null) return false;
                if (!hasServiceDirector) return true;
                String empDept = (employee.getDepartment() != null) ? employee.getDepartment().trim().toLowerCase() : "";
                return !empDept.contains("dịch vụ");
            }).toList();
        } else {
            // 🌟 Trưởng phòng thường (IT, MKT, KD1 -> KD6, Kế toán...) + Giám đốc dịch vụ / Kế toán trưởng
            String department = manager.getDepartment();
            // HCNS đã giao phòng này cho người khác duyệt -> trưởng phòng không duyệt thay nữa (chỉ đơn đích danh bên dưới)
            String deptApprover = configuredApprover(unitScope.unitKey(manager), department);
            List<AttendanceConfirmation> deptList = deptApprover != null && !deptApprover.equalsIgnoreCase(myNo)
                    ? List.of()
                    : confirmationRepository.findPendingRequestsForManagerByMonth(department, from, to);

            // BẢO MẬT NÂNG CAO: Trưởng phòng thường chỉ nhìn thấy đơn của nhân viên cấp dưới
            Map<Long, AttendanceConfirmation> m = new LinkedHashMap<>();
            deptList.stream().filter(confirm -> {
                User employee = userRepository.findByEmployeeNo(confirm.getEmployeeNo()).orElse(null);
                if (employee == null) return false;

                String position = (employee.getDepartmentManager() != null) ? employee.getDepartmentManager().toString().trim().toLowerCase() : "";
                boolean isEmpManager = position.contains("trưởng phòng") || position.equals("1") || position.equals("true");

                return !isEmpManager; // Chỉ giữ lại đơn của nhân viên thường
            }).forEach(c -> m.put(c.getId(), c));

            // Đơn được định tuyến ĐÍCH DANH tới người này (Giám đốc dịch vụ, Kế toán trưởng, trưởng phòng, người HCNS giao)
            // CHỈ GIỮ khi hiện tại người này vẫn đúng là người duyệt của nhân viên đó: nhân viên đã đổi phòng / phòng đổi
            // trưởng phòng thì đơn cũ không còn hiện ở người duyệt cũ nữa (được chuyển sang người mới).
            if (!myNo.isEmpty()) {
                for (AttendanceConfirmation c : confirmationRepository.findPendingRequestsByApprovedByKey(myNo, from, to)) {
                    User e = userRepository.findByEmployeeNo(c.getEmployeeNo()).orElse(null);
                    if (e != null && myNo.equalsIgnoreCase(approverFor(e))) {
                        m.put(c.getId(), c);
                    }
                }
            }
            raw = new ArrayList<>(m.values());
        }

        // 🔒 ĐƠN VỊ: không bao giờ thấy đơn của đơn vị khác (phòng cùng tên ở 4 công ty)
        Long myKey = unitScope.unitKey(manager);
        return raw.stream().filter(c -> {
            User e = userRepository.findByEmployeeNo(c.getEmployeeNo()).orElse(null);
            return e != null && Objects.equals(unitScope.unitKey(e), myKey);
        }).toList();
    }

    /**
     * 🔒 Người này có được duyệt / từ chối / trả lại đơn này không.
     * Chặn việc gõ thẳng /attendance/manager/approve/{id} để duyệt đơn ngoài quyền hoặc của đơn vị khác.
     */
    @Transactional(readOnly = true)
    public boolean canDecide(String login, Long confirmId) {
        User manager = userRepository.findByEmployeeNo(login)
                .orElseGet(() -> userRepository.findByUsername(login).orElse(null));
        AttendanceConfirmation c = confirmationRepository.findById(confirmId).orElse(null);
        if (manager == null || c == null || c.getAttendanceDate() == null) {
            return false;
        }
        if (c.getEmployeeNo() != null && c.getEmployeeNo().equalsIgnoreCase(manager.getEmployeeNo())) {
            return false;   // không tự duyệt đơn của mình
        }
        if (unitScope.isGlobalAdmin(manager)) {
            User e = userRepository.findByEmployeeNo(c.getEmployeeNo()).orElse(null);
            return e != null;
        }
        // Nhân viên thường không duyệt được (bản cũ chỉ ẩn trang, gõ đường dẫn vẫn duyệt được)
        boolean managerRole = Boolean.TRUE.equals(manager.getDepartmentManager()) || unitScope.isUnitApprover(manager)
                || UnitScope.isAdmin(manager) || isConfiguredApprover(manager);
        if (!managerRole) {
            return false;
        }
        if ("PENDING".equalsIgnoreCase(c.getApprovalStatus())) {
            LocalDate d = c.getAttendanceDate();
            return getManagerPendingRequests(login, d, d).stream().anyMatch(x -> x.getId().equals(confirmId));
        }
        return false;
    }

    /**
     * 🌟 BỔ SUNG HÀM LẤY TÊN HIỂN THỊ (Sửa lỗi báo thiếu hàm ở Controller)
     */
    @Transactional(readOnly = true)
    public String getEmployeeNameByNo(String employeeNo) {
        if (employeeNo == null || employeeNo.isBlank()) {
            return "";
        }
        return userRepository.findByEmployeeNo(employeeNo)
                .map(User::getFullName)
                .orElse("");
    }

//    @Transactional
//    public void syncAttendance() {
//        try {
//            LocalDate fromDate = LocalDate.now().withDayOfMonth(1);
//            LocalDate toDate = LocalDate.now();
//
//            for (LocalDate current = fromDate; !current.isAfter(toDate); current = current.plusDays(1)) {
//                String startTime = current.atStartOfDay().atOffset(ZoneOffset.ofHours(7)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
//                String endTime = current.atTime(23, 59, 59).atOffset(ZoneOffset.ofHours(7)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
//
//                int position = 0;
//                int pageSize = 100;
//
//                while (true) {
//                    String json = hikvisionApiClient.fetchAttendance(startTime, endTime, position, pageSize);
//                    if (json == null || json.isEmpty()) break;
//
//                    JsonNode root = mapper.readTree(json);
//                    JsonNode events = root.path("AcsEvent").path("InfoList");
//
//                    if (!events.isArray() || events.size() == 0) break;
//
//                    for (JsonNode node : events) {
//                        try {
//                            String employeeNo = node.path("employeeNoString").asText();
//                            String fullName = node.path("name").asText();
//                            String time = node.path("time").asText();
//
//                            if (employeeNo == null || employeeNo.isBlank()) continue;
//
//                            User user = userRepository.findByEmployeeNo(employeeNo).orElseGet(() -> {
//                                User newUser = User.builder()
//                                        .username(employeeNo)
//                                        .employeeNo(employeeNo)
//                                        .fullName(fullName)
//                                        .password(passwordEncoder.encode("123456"))
//                                        .role("USER")
//                                        .departmentManager(false)
//                                        .build();
//                                return userRepository.save(newUser);
//                            });
//
//                            LocalDateTime checkTime = OffsetDateTime.parse(time).toLocalDateTime();
//                            Attendance attendance = attendanceRepository.findByUserAndDate(user, checkTime.toLocalDate())
//                                    .orElseGet(() -> {
//                                        Attendance a = new Attendance();
//                                        a.setUser(user);
//                                        a.setDate(checkTime.toLocalDate());
//                                        return a;
//                                    });
//
//                            if (attendance.getCheckInTime() == null || checkTime.isBefore(attendance.getCheckInTime())) {
//                                attendance.setCheckInTime(checkTime);
//                            }
//
//                            if (attendance.getCheckOutTime() == null || checkTime.isAfter(attendance.getCheckOutTime())) {
//                                attendance.setCheckOutTime(checkTime);
//                            }
//
//                            if (attendance.getCheckInTime().equals(attendance.getCheckOutTime())) {
//                                attendance.setStatus(AttendanceStatus.HALF_DAY);
//                                attendance.setWorkDay(0.5);
//                            } else {
//                                attendance.setStatus(AttendanceStatus.FULL_DAY);
//                                attendance.setWorkDay(1.0);
//                            }
//
//                            attendanceRepository.save(attendance);
//                        } catch (Exception ex) {
//                            log.error("LỖI EVENT LẺ", ex);
//                        }
//                    }
//                    position += events.size();
//                    Thread.sleep(100);
//                }
//            }
//        } catch (Exception ex) {
//            log.error("SYNC ERROR", ex);
//        }
//    }
/**
 * Kéo chấm công MỌI ĐƠN VỊ có khai máy (mỗi đơn vị 1 máy Hikvision riêng).
 * Chưa có đơn vị nào: chạy máy cũ như trước.
 */
public void syncAttendance() {
    List<CompanyUnit> units = unitRepository.findAllByOrderByCodeAsc().stream()
            .filter(u -> u.isEnabled() && u.isHasDevice()).toList();
    if (units.isEmpty()) {
        syncAttendanceForUnit(null);
        return;
    }
    for (CompanyUnit unit : units) {
        syncAttendanceForUnit(unit.getId());
    }
}

/** Kéo chấm công 1 đơn vị (unitId null = máy cũ, không theo đơn vị). Trả số ngày lỗi. */
public int syncAttendanceForUnit(Long unitId) {
    return syncAttendanceForUnit(unitId, null, null);
}

/**
 * KÉO LẠI CÔNG THEO KHOẢNG NGÀY (quản trị / HCNS chọn từ ngày - đến ngày).
 * from / to null = như cũ (từ mốc lần kéo thành công trước đến hôm nay).
 */
public int syncAttendanceForUnit(Long unitId, LocalDate from, LocalDate to) {
    CompanyUnit unit = unitId == null ? null : unitRepository.findById(unitId).orElse(null);
    if (unit != null) {
        HikvisionApiClient.use(unit.getDeviceUrl(), unit.getDeviceUsername(), unit.getDevicePassword());
        syncUnit.set(unit);
    }
    LocalDate today0 = LocalDate.now(ZoneOffset.ofHours(7));
    boolean reachesToday = to == null || !to.isBefore(today0);
    int failedDays = 0;
    try {
        failedDays = from == null ? syncDays(unit) : syncDays(unit, from, to == null || to.isAfter(today0) ? today0 : to);
    } finally {
        HikvisionApiClient.clear();
        syncUnit.remove();
    }
    if (unit != null) {
        CompanyUnit fresh = unitRepository.findById(unit.getId()).orElse(unit);
        fresh.setLastSyncAt(LocalDateTime.now());
        fresh.setLastStatus(failedDays == 0 ? "OK" : "ERROR");
        fresh.setLastMessage(failedDays == 0 ? "Kéo chấm công xong" : "Có " + failedDays + " ngày lỗi khi kéo chấm công, xem log");
        if (failedDays == 0 && reachesToday && from == null) {
            fresh.setLastSyncDate(LocalDate.now(ZoneOffset.ofHours(7)));
        }
        // có ngày lỗi: giữ nguyên lastSyncDate, lần sau kéo lại từ mốc cũ
        unitRepository.save(fresh);
    }
    return failedDays;
}

private int syncDays(CompanyUnit unit) {
    return syncDays(unit, null, null);
}

private int syncDays(CompanyUnit unit, LocalDate rangeFrom, LocalDate rangeTo) {
    ZoneOffset zoneOffset = ZoneOffset.ofHours(7);

    LocalDate today = rangeTo != null ? rangeTo : LocalDate.now(zoneOffset);

    // Sync từ ngày 25/08/2026 (bản cũ). Mỗi đơn vị có ngày bắt đầu riêng; lần sau chỉ kéo lùi 2 ngày
    // trước lần kéo thành công gần nhất, không kéo lại từ đầu mỗi lần.
    LocalDate fromDate = LocalDate.of(2026, 8, 25);
    if (unit != null) {
        if (unit.getSyncFromDate() != null) {
            fromDate = unit.getSyncFromDate();
        }
        if (unit.getLastSyncDate() != null && unit.getLastSyncDate().minusDays(2).isAfter(fromDate)) {
            fromDate = unit.getLastSyncDate().minusDays(2);
        }
    }
    if (rangeFrom != null) {
        fromDate = rangeFrom;   // kéo lại theo khoảng ngày được chọn
    }
    int failed = 0;

    log.info("=================================================");
    log.info("BẮT ĐẦU SYNC CHẤM CÔNG TỪ {} ĐẾN {}", fromDate, today);
    log.info("=================================================");

    for (LocalDate current = fromDate;
         !current.isAfter(today);
         current = current.plusDays(1)) {

        try {
            syncAttendanceByDate(current, LocalDate.now(zoneOffset), zoneOffset);   // "hôm nay" thật: ngày đang chạy lấy đến giờ hiện tại

        } catch (Exception ex) {

            /*
             * QUAN TRỌNG:
             * Ngày nào lỗi thì ghi log ngày đó,
             * KHÔNG dừng toàn bộ các ngày phía sau.
             */
            log.error("❌ LỖI SYNC NGÀY {}", current, ex);
            failed++;
        }
    }

    log.info("=================================================");
    log.info("✅ HOÀN TẤT SYNC CHẤM CÔNG TỪ {} ĐẾN {} [{}]", fromDate, today, unit == null ? "máy cũ" : unit.getCode());
    log.info("=================================================");
    return failed;
}


    private void syncAttendanceByDate(
            LocalDate current,
            LocalDate today,
            ZoneOffset zoneOffset) throws Exception {

        /*
         * Từ 00:00:00 của ngày cần lấy.
         */
        String startTime = current
                .atStartOfDay()
                .atOffset(zoneOffset)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        /*
         * Ngày quá khứ:
         * 00:00:00 -> 23:59:59
         *
         * Ngày hôm nay:
         * 00:00:00 -> thời điểm hiện tại
         */
        String endTime;

        if (current.equals(today)) {

            endTime = OffsetDateTime.now(zoneOffset)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        } else {

            endTime = current
                    .atTime(23, 59, 59)
                    .atOffset(zoneOffset)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }

        log.info(
                "🔄 SYNC NGÀY {} | {} -> {}",
                current,
                startTime,
                endTime
        );

        int position = 0;
        int pageSize = 100;
        int totalProcessed = 0;

        while (true) {

            String json;

            try {

                json = hikvisionApiClient.fetchAttendance(
                        startTime,
                        endTime,
                        position,
                        pageSize
                );

            } catch (Exception ex) {

                /*
                 * Không nuốt lỗi API.
                 * Throw ra ngoài để syncAttendance()
                 * biết chính xác ngày nào bị lỗi.
                 */
                log.error(
                        "❌ HIKVISION API LỖI NGÀY {} | position={}",
                        current,
                        position,
                        ex
                );

                throw ex;
            }

            if (json == null || json.isBlank()) {

                log.warn(
                        "⚠️ HIKVISION KHÔNG TRẢ DỮ LIỆU NGÀY {} | position={}",
                        current,
                        position
                );

                // Máy tắt / sai mật khẩu: HikvisionApiClient trả null. Báo lỗi ngày này để lần sau kéo lại,
                // không coi là "ngày không có ai quẹt" (vì lần sau chỉ kéo lùi 2 ngày).
                throw new IllegalStateException("Máy chấm công " + HikvisionApiClient.currentUrl() + " không trả lời");
            }

            JsonNode root = mapper.readTree(json);
            JsonNode acsEvent = root.path("AcsEvent");
            JsonNode events = acsEvent.path("InfoList");

            /*
             * Một số thiết bị Hikvision có totalMatches.
             * Nếu không có thì trả -1.
             */
            int totalMatches = acsEvent
                    .path("totalMatches")
                    .asInt(-1);

            if (!events.isArray() || events.isEmpty()) {

                log.info(
                        "ℹ️ NGÀY {} KHÔNG CÒN EVENT | position={} | totalMatches={}",
                        current,
                        position,
                        totalMatches
                );

                break;
            }

            int currentPageCount = events.size();

            log.info(
                    "📄 NGÀY {} | position={} | nhận={} | totalMatches={}",
                    current,
                    position,
                    currentPageCount,
                    totalMatches
            );

            for (JsonNode node : events) {

                try {

                    processAttendanceEvent(node, zoneOffset);
                    totalProcessed++;

                } catch (Exception ex) {

                    String empNo = node
                            .path("employeeNoString")
                            .asText("");

                    String eventTime = node
                            .path("time")
                            .asText("");

                    /*
                     * Event nào lỗi chỉ bỏ EVENT đó,
                     * không bỏ cả ngày.
                     */
                    log.error(
                            "❌ LỖI EVENT | ngày={} | employeeNo={} | time={}",
                            current,
                            empNo,
                            eventTime,
                            ex
                    );
                }
            }

            position += currentPageCount;

            /*
             * Ưu tiên totalMatches nếu Hikvision trả trường này.
             */
            if (totalMatches >= 0) {

                if (position >= totalMatches) {
                    break;
                }

            } else {

                /*
                 * Fallback khi thiết bị không trả totalMatches.
                 */
                if (currentPageCount < pageSize) {
                    break;
                }
            }

            try {

                Thread.sleep(100);

            } catch (InterruptedException ex) {

                Thread.currentThread().interrupt();

                log.warn(
                        "⚠️ SYNC BỊ INTERRUPT NGÀY {} | position={}",
                        current,
                        position
                );

                break;
            }
        }

        log.info(
                "✅ SYNC XONG NGÀY {} | tổng event xử lý={}",
                current,
                totalProcessed
        );
    }
    private void processAttendanceEvent(
            JsonNode node,
            ZoneOffset zoneOffset) {

        String employeeNo = node
                .path("employeeNoString")
                .asText("");

        String fullName = node
                .path("name")
                .asText("");

        String time = node
                .path("time")
                .asText("");

        /*
         * Không có mã nhân viên thì bỏ qua.
         */
        if (employeeNo == null || employeeNo.isBlank()) {
            return;
        }

        /*
         * Không có thời gian thì event không sử dụng được.
         */
        if (time == null || time.isBlank()) {
            log.warn(
                    "⚠️ EVENT KHÔNG CÓ TIME | employeeNo={}",
                    employeeNo
            );
            return;
        }

        /*
         * Tìm nhân viên.
         * Nếu chưa tồn tại thì tạo mới.
         */
        CompanyUnit unit = syncUnit.get();
        String deviceCode = employeeNo.trim();
        // Đơn vị gốc giữ mã như máy; đơn vị khác: mã đơn vị + mã trên máy (SG + 41 = SG41)
        String hrmNo = unit == null ? deviceCode : unit.employeeNoFor(deviceCode);

        User found = userRepository.findByEmployeeNo(hrmNo).orElse(null);
        if (found != null && unit != null && found.getUnit() != null
                && !Objects.equals(found.getUnit().getId(), unit.getId())) {
            // mã này đã thuộc đơn vị khác -> bỏ qua, không ghi nhầm công
            log.warn("⚠️ BỎ QUA EVENT [{}] mã {} đang thuộc đơn vị {}", unit.getCode(), hrmNo, found.getUnit().getCode());
            return;
        }
        User user = found != null ? found : userRepository.save(User.builder()
                .username(hrmNo)
                .employeeNo(hrmNo)
                .attendanceCode(deviceCode)
                .fullName(fullName)
                .password(passwordEncoder.encode("123456"))
                .role("USER")
                .departmentManager(false)
                .unit(unit != null ? unit : unitScope.legacyUnit())
                .build());

        /*
         * Parse thời gian Hikvision và chuyển đúng về GMT+7.
         */
        LocalDateTime checkTime = OffsetDateTime
                .parse(time)
                .withOffsetSameInstant(zoneOffset)
                .toLocalDateTime();

        LocalDate attendanceDate = checkTime.toLocalDate();

        /*
         * Mỗi nhân viên / mỗi ngày chỉ có một Attendance.
         */
        Attendance attendance = attendanceRepository
                .findByUserAndDate(user, attendanceDate)
                .orElseGet(() -> {

                    Attendance newAttendance = new Attendance();
                    newAttendance.setUser(user);
                    newAttendance.setDate(attendanceDate);

                    return newAttendance;
                });

        /*
         * CHECK-IN = lần quẹt sớm nhất trong ngày.
         */
        if (attendance.getCheckInTime() == null
                || checkTime.isBefore(attendance.getCheckInTime())) {

            attendance.setCheckInTime(checkTime);
        }

        /*
         * CHECK-OUT = lần quẹt muộn nhất trong ngày.
         */
        if (attendance.getCheckOutTime() == null
                || checkTime.isAfter(attendance.getCheckOutTime())) {

            attendance.setCheckOutTime(checkTime);
        }

        /*
         * Chỉ có 1 lần quẹt:
         * check-in = check-out.
         */
        if (attendance.getCheckInTime() != null
                && attendance.getCheckOutTime() != null
                && attendance.getCheckInTime()
                .equals(attendance.getCheckOutTime())) {

            attendance.setStatus(AttendanceStatus.HALF_DAY);
            attendance.setWorkDay(0.5);

        } else {

            attendance.setStatus(AttendanceStatus.FULL_DAY);
            attendance.setWorkDay(1.0);
        }

        attendanceRepository.save(attendance);
    }
    @Transactional(readOnly = true)
    public List<AttendanceAlertDTO> getAttendanceIssues(String employeeNo, LocalDate from, LocalDate to) {
        List<Attendance> attendances = attendanceRepository.findByUser_EmployeeNoAndDateBetween(employeeNo, from, to);
        List<LocalDate> holidays = holidayConfigRepository.findByHolidayDateBetween(from, to)
                .stream().map(HolidayConfig::getHolidayDate).toList();
        List<LeaveRequest> approvedLeaves = leaveRequestRepository.findByEmployeeNoAndStatus(employeeNo, "APPROVED");

        List<AttendanceConfirmation> approvedConfirms = confirmationRepository
                .findByEmployeeNoAndAttendanceDateBetweenAndApprovalStatus(employeeNo, from, to, "APPROVED");

        Set<String> approvedConfirmKeys = new HashSet<>();
        for (AttendanceConfirmation conf : approvedConfirms) {
            String session = conf.getSessionType();
            if ("ALL".equalsIgnoreCase(session)) {
                approvedConfirmKeys.add(conf.getAttendanceDate() + "_MORNING");
                approvedConfirmKeys.add(conf.getAttendanceDate() + "_AFTERNOON");
            } else if (session != null) {
                approvedConfirmKeys.add(conf.getAttendanceDate() + "_" + session.toUpperCase());
            }
        }

        List<AttendanceAlertDTO> result = new ArrayList<>();
        // GIỜ LÀM THEO ĐƠN VỊ của nhân viên (đơn vị gốc mặc định 07:35 / 12:00 / 17:00 như bản cũ)
        CompanyUnit unit = userRepository.findByEmployeeNo(employeeNo).map(unitScope::unitOf).orElse(unitScope.legacyUnit());
        LocalTime morningStartDeadline = (unit != null ? unit.lateAfterOrDefault() : CompanyUnit.DEFAULT_LATE_AFTER).withSecond(59);
        LocalTime noonSplit = unit != null ? unit.noonOrDefault() : CompanyUnit.DEFAULT_NOON;
        LocalTime afternoonEndDeadline = unit != null ? unit.earlyBeforeOrDefault() : CompanyUnit.DEFAULT_EARLY_BEFORE;

        LocalDate today = LocalDate.now();
        LocalTime nowTime = LocalTime.now();

        for (LocalDate current = from; !current.isAfter(to); current = current.plusDays(1)) {
            // LỊCH LÀM VIỆC ĐƠN VỊ (HCNS cấu hình): ngày nghỉ không báo lỗi; ngày làm sáng chỉ xét ca sáng
            if (CompanyUnit.isOffDay(unit, current.getDayOfWeek())) continue;
            final boolean morningOnly = CompanyUnit.isMorningOnly(unit, current.getDayOfWeek());

            final LocalDate finalCurrent = current;

            if (holidays.contains(finalCurrent) || approvedLeaves.stream().anyMatch(l ->
                    !finalCurrent.isBefore(l.getFromDate()) && !finalCurrent.isAfter(l.getToDate()))) {
                continue;
            }

            Attendance attendance = attendances.stream()
                    .filter(a -> a.getDate() != null && a.getDate().equals(finalCurrent))
                    .findFirst().orElse(null);

            String morningKey = finalCurrent + "_MORNING";
            String afternoonKey = finalCurrent + "_AFTERNOON";

            if (attendance == null) {
                if (!approvedConfirmKeys.contains(morningKey)) {
                    if (finalCurrent.isBefore(today) || (finalCurrent.equals(today) && nowTime.isAfter(morningStartDeadline))) {
                        result.add(createAlert(employeeNo, finalCurrent, "ABSENT", "Không chấm công ca Sáng", "MORNING", null, null));
                    }
                }
                if (!morningOnly && !approvedConfirmKeys.contains(afternoonKey) && finalCurrent.isBefore(today)) {
                    result.add(createAlert(employeeNo, finalCurrent, "ABSENT", "Không chấm công ca Chiều", "AFTERNOON", null, null));
                }
                continue;
            }

            LocalTime rawCheckIn = attendance.getCheckInTime() != null ? attendance.getCheckInTime().toLocalTime() : null;
            LocalTime rawCheckOut = attendance.getCheckOutTime() != null ? attendance.getCheckOutTime().toLocalTime() : null;

            LocalTime actualMorningCheckIn = null;
            LocalTime actualAfternoonCheckOut = null;

            if (rawCheckIn != null) {
                if (rawCheckIn.isBefore(noonSplit)) actualMorningCheckIn = rawCheckIn;
                else actualAfternoonCheckOut = rawCheckIn;
            }
            if (rawCheckOut != null) {
                if (rawCheckOut.isAfter(noonSplit)) {
                    if (actualAfternoonCheckOut == null || rawCheckOut.isAfter(actualAfternoonCheckOut)) {
                        actualAfternoonCheckOut = rawCheckOut;
                    }
                } else {
                    if (actualMorningCheckIn == null || rawCheckOut.isBefore(actualMorningCheckIn)) {
                        actualMorningCheckIn = rawCheckOut;
                    }
                }
            }

            long swipeDurationMinutes = (rawCheckIn != null && rawCheckOut != null && !rawCheckIn.equals(rawCheckOut))
                    ? Duration.between(rawCheckIn, rawCheckOut).toMinutes() : 0;

            if (swipeDurationMinutes > 0 && swipeDurationMinutes < 5) {
                if (rawCheckIn.isAfter(noonSplit)) {
                    if (!approvedConfirmKeys.contains(morningKey)) {
                        result.add(createAlert(employeeNo, finalCurrent, "ABSENT", "Sáng không chấm công (Chiều quẹt lặp)", "MORNING", null, null));
                    }
                    continue;
                }
                if (rawCheckIn.isBefore(noonSplit)) {
                    if (!morningOnly && !approvedConfirmKeys.contains(afternoonKey) && finalCurrent.isBefore(today)) {
                        result.add(createAlert(employeeNo, finalCurrent, "NO_CHECKOUT", "Chấm ca Sáng, ca Chiều không chấm công", "AFTERNOON", null, null));
                    }
                    continue;
                }
            }

            if (!approvedConfirmKeys.contains(morningKey)) {
                if (actualMorningCheckIn == null) {
                    if (finalCurrent.isBefore(today) || (finalCurrent.equals(today) && nowTime.isAfter(morningStartDeadline))) {
                        result.add(createAlert(employeeNo, finalCurrent, "ABSENT", "Không chấm công ca Sáng", "MORNING", null, null));
                    }
                } else if (actualMorningCheckIn.isAfter(morningStartDeadline)) {
                    result.add(createAlert(employeeNo, finalCurrent, "LATE", "Đi muộn ca Sáng", "MORNING", actualMorningCheckIn, null));
                }
            }

            if (!morningOnly && !approvedConfirmKeys.contains(afternoonKey)) {
                if (actualAfternoonCheckOut == null) {
                    if (finalCurrent.isBefore(today)) {
                        result.add(createAlert(employeeNo, finalCurrent, "NO_CHECKOUT", "Không chấm công ca Chiều (Quên checkout)", "AFTERNOON", null, null));
                    }
                } else if (actualAfternoonCheckOut.isBefore(afternoonEndDeadline)) {
                    if (finalCurrent.isBefore(today) || (finalCurrent.equals(today) && nowTime.isAfter(afternoonEndDeadline))) {
                        result.add(createAlert(employeeNo, finalCurrent, "EARLY_LEAVE", "Về sớm ca Chiều", "AFTERNOON", null, actualAfternoonCheckOut));
                    }
                }
            }
        }
        // HẠN XÁC NHẬN: gắn hạn chót / đã khóa cho từng ngày lỗi
        confirmDeadlineService.annotate(employeeNo, result);
        return result;
    }

    private AttendanceAlertDTO createAlert(String empNo, LocalDate date, String type, String desc, String session, LocalTime in, LocalTime out) {
        return AttendanceAlertDTO.builder()
                .employeeNo(empNo).date(date).issueType(type).description(desc).sessionType(session).checkIn(in).checkOut(out)
                .build();
    }

    @Transactional
    public void saveConfirm(String employeeNo, LocalDate date, String confirmStatus,
                            String note, String issueType, String sessionType) {

        // 🔒 HẠN XÁC NHẬN: quá N ngày kể từ ngày lỗi thì không tạo đơn được nữa (HCNS mở lại được)
        confirmDeadlineService.requireOpen(employeeNo, date);

        User employee = userRepository.findByEmployeeNo(employeeNo)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên có mã: " + employeeNo));

        String managerEmployeeNo = approverFor(employee);

        AttendanceConfirmation confirmation = AttendanceConfirmation.builder()
                .employeeNo(employeeNo)
                .attendanceDate(date)
                .issueType(issueType)
                .confirmStatus(confirmStatus)
                .sessionType(sessionType)
                .note(note)
                .approvalStatus("PENDING")
                .approvedBy(managerEmployeeNo)
                .createdAt(LocalDateTime.now())
                .build();

        confirmationRepository.save(confirmation);

        // 🔔 người duyệt: có đơn giải trình mới (1 thông báo / nhân viên / ngày lỗi)
        notificationService.sendToEmployeeNo(managerEmployeeNo, "CONFIRM",
                "Đơn giải trình công mới: " + nameOf(employee),
                "Ngày " + dm(date) + " · " + sessionLabel(sessionType) + " · " + issueLabel(issueType)
                        + (note == null || note.isBlank() ? "" : " · \"" + note.trim() + "\""),
                "/attendance/manager/attendance-confirms", "CF-NEW:" + employeeNo + ":" + date);
    }

    // ------------------------------------------------------------- 🔔 thông báo đơn giải trình
    private static final java.time.format.DateTimeFormatter DM = java.time.format.DateTimeFormatter.ofPattern("dd/MM");

    static String dm(LocalDate d) {
        return d == null ? "" : d.format(DM);
    }

    static String nameOf(User u) {
        return (u.getFullName() == null ? "" : u.getFullName()) + " (" + u.getEmployeeNo() + ")";
    }

    static String sessionLabel(String s) {
        return "MORNING".equalsIgnoreCase(s) ? "ca Sáng" : "AFTERNOON".equalsIgnoreCase(s) ? "ca Chiều" : "cả ngày";
    }

    static String issueLabel(String t) {
        if (t == null) return "";
        return switch (t.toUpperCase()) {
            case "LATE" -> "Đi muộn";
            case "EARLY_LEAVE" -> "Về sớm";
            case "NO_CHECKOUT" -> "Quên chấm ra";
            case "ABSENT" -> "Không chấm công";
            case "LATE_EARLY" -> "Đi muộn / về sớm";
            default -> t;
        };
    }

    /** Báo nhân viên kết quả xử lý đơn */
    private void notifyResult(AttendanceConfirmation c, String title, String extra) {
        notificationService.sendToEmployeeNo(c.getEmployeeNo(), "CONFIRM", title,
                "Ngày " + dm(c.getAttendanceDate()) + " · " + sessionLabel(c.getSessionType()) + " · " + issueLabel(c.getIssueType())
                        + (extra == null || extra.isBlank() ? "" : " · " + extra),
                "/attendance/confirm/" + c.getEmployeeNo(), null);
    }

    /**
     * NGƯỜI DUYỆT đơn xác nhận công của 1 nhân viên (logic cũ giữ nguyên, tách thành hàm để dùng lại):
     * 99999 / 88888 / 77777 của bản cũ = Giám đốc / GĐ dịch vụ / Kế toán trưởng CỦA ĐƠN VỊ nhân viên;
     * trưởng phòng tìm trong CÙNG đơn vị (4 công ty có phòng trùng tên).
     */
    public String approverFor(User employee) {
        String employeeNo = employee.getEmployeeNo();
        String managerEmployeeNo;
        String dept = (employee.getDepartment() != null) ? employee.getDepartment().trim() : "";
        String role = (employee.getRole() != null) ? employee.getRole().trim() : "";
        String username = (employee.getUsername() != null) ? employee.getUsername().trim() : "";

        CompanyUnit unit = unitScope.unitOf(employee);
        String directorNo = unitScope.directorNo(unit);

        // ƯU TIÊN: người duyệt của phòng do HCNS cấu hình (trừ khi chính người đó gửi đơn -> theo quy tắc cũ)
        String configured = configuredApprover(unitScope.unitKey(employee), dept);
        if (configured != null && !configured.equalsIgnoreCase(employeeNo == null ? "" : employeeNo.trim())) {
            return configured;
        }

        if (dept.startsWith("Kinh Doanh") && Boolean.TRUE.equals(employee.getDepartmentManager())) {
            managerEmployeeNo = directorNo;
        }
        else if ("ADMIN".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(dept) || "admin".equalsIgnoreCase(username)) {
            managerEmployeeNo = directorNo;
        }
        else if (dept.contains("Dịch Vụ")) {
            managerEmployeeNo = unitScope.serviceDirectorNo(unit);
        }
        else if (dept.contains("Kế Toán")) {
            managerEmployeeNo = unitScope.chiefAccountantNo(unit);
        }
        else {
            Long unitKey = unitScope.unitKey(employee);
            managerEmployeeNo = userRepository.findDepartmentManagers(dept).stream()
                    .filter(m -> Objects.equals(unitScope.unitKey(m), unitKey))
                    .filter(m -> m.getEmployeeNo() != null && !m.getEmployeeNo().equalsIgnoreCase(employeeNo))
                    .map(User::getEmployeeNo)
                    .findFirst()
                    .orElse(directorNo);
        }
        return managerEmployeeNo;
    }

    /** Người này được HCNS giao duyệt ít nhất 1 phòng (để thấy mục Phê duyệt giải trình) */
    public boolean isConfiguredApprover(User u) {
        if (u == null || u.getEmployeeNo() == null || u.getEmployeeNo().isBlank()) {
            return false;
        }
        try {
            Long key = unitScope.unitKey(u);
            return key != null && unitDepartmentRepository.findByUnitIdOrderByNameAsc(key).stream()
                    .anyMatch(d -> d.getApproverNo() != null && d.getApproverNo().trim().equalsIgnoreCase(u.getEmployeeNo().trim()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Mã NV người duyệt HCNS gán cho phòng (null = chưa gán) */
    public String configuredApprover(Long unitKey, String dept) {
        if (unitKey == null || dept == null || dept.isBlank()) {
            return null;
        }
        try {
            String key = com.example.hrm.entity.UnitDepartment.norm(dept);
            return unitDepartmentRepository.findByUnitIdOrderByNameAsc(unitKey).stream()
                    .filter(d -> com.example.hrm.entity.UnitDepartment.norm(d.getName()).equals(key))
                    .map(com.example.hrm.entity.UnitDepartment::getApproverNo)
                    .filter(no -> no != null && !no.isBlank())
                    .map(String::trim)
                    .findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;   // bảng chưa có: theo quy tắc cũ
        }
    }

    /**
     * HCNS đổi Giám đốc / trưởng phòng / phòng ban: đơn xác nhận ĐANG CHỜ (PENDING / RETURNED) của những người này
     * chuyển sang người duyệt mới, để không kẹt ở người cũ. Đơn đã duyệt / từ chối giữ nguyên.
     */
    @Transactional
    public int rerouteOpenConfirmations(Collection<User> staff) {
        int changed = 0;
        for (User u : staff) {
            if (u == null || u.getEmployeeNo() == null || u.getEmployeeNo().isBlank()) {
                continue;
            }
            String to = approverFor(u);
            changed += entityManager.createQuery(
                            "UPDATE AttendanceConfirmation c SET c.approvedBy = :to "
                                    + "WHERE c.employeeNo = :no AND c.approvalStatus IN ('PENDING', 'RETURNED') "
                                    + "AND (c.approvedBy IS NULL OR c.approvedBy <> :to)")
                    .setParameter("to", to)
                    .setParameter("no", u.getEmployeeNo())
                    .executeUpdate();
        }
        return changed;
    }

    @Transactional
    public void approveConfirm(Long id, String approver) {
        AttendanceConfirmation confirm = confirmationRepository.findById(id).orElseThrow();
        confirm.setApprovalStatus("APPROVED");
        confirm.setApprovedBy(approver);
        confirm.setApprovedAt(LocalDateTime.now());
        confirmationRepository.save(confirm);
        notifyResult(confirm, "Giải trình ngày " + dm(confirm.getAttendanceDate()) + " đã được DUYỆT", null);   // 🔔

        User user = userRepository.findByEmployeeNo(confirm.getEmployeeNo()).orElseThrow();
        Attendance attendance = attendanceRepository.findByUserAndDate(user, confirm.getAttendanceDate())
                .orElseGet(() -> {
                    Attendance a = new Attendance();
                    a.setUser(user);
                    a.setDate(confirm.getAttendanceDate());
                    a.setWorkDay(0.0);
                    a.setConfirmedWork(0.0);
                    return a;
                });

        double addedWork = 0.0;
        String statusResult = "HALF_DAY";

        switch (confirm.getConfirmStatus()) {
            case "PRESENT" -> {
                addedWork = 0.5;
                statusResult = "PRESENT";
            }
            case "ANNUAL_LEAVE" -> {
                addedWork = 0.5;
                statusResult = "ANNUAL_LEAVE";

                // 🌟 FIX QUAN TRỌNG: Trừ số dư phép năm thực tế khi được duyệt đơn giải trình phép
                LeaveBalance balance = leaveBalanceRepository.findByEmployeeNo(confirm.getEmployeeNo());
                if (balance != null) {
                    balance.setRemaining(balance.getRemaining() - 0.5);
                    leaveBalanceRepository.save(balance);
                    log.info("📉 Đã trừ 0.5 ngày phép của nhân viên: {}", confirm.getEmployeeNo());
                }
            }
            case "HALF_DAY" -> {
                addedWork = 0.25;
                statusResult = "HALF_DAY";
            }
            case "UNPAID_LEAVE" -> {
                addedWork = 0.0;
                statusResult = "UNEXCUSED_LEAVE";
            }
        }

        double currentWork = attendance.getWorkDay() != null ? attendance.getWorkDay() : 0.0;
        double totalWork = Math.min(1.0, currentWork + addedWork);

        attendance.setWorkDay(totalWork);
        attendance.setConfirmedWork(totalWork);

        if (totalWork >= 1.0) {
            attendance.setStatus(statusResult.equals("ANNUAL_LEAVE") ? AttendanceStatus.ANNUAL_LEAVE : AttendanceStatus.FULL_DAY);
        } else if (totalWork > 0) {
            attendance.setStatus(AttendanceStatus.HALF_DAY);
        } else {
            attendance.setStatus(AttendanceStatus.UNEXCUSED_LEAVE);
        }

        attendanceRepository.save(attendance);
    }

    @Transactional
    public void rejectConfirm(Long id, String approver) {
        AttendanceConfirmation confirm = confirmationRepository.findById(id).orElseThrow();
        confirm.setApprovalStatus("REJECTED");
        confirm.setApprovedBy(approver);
        confirm.setApprovedAt(LocalDateTime.now());
        confirmationRepository.save(confirm);
        notifyResult(confirm, "Giải trình ngày " + dm(confirm.getAttendanceDate()) + " bị TỪ CHỐI", null);   // 🔔
    }

    /**
     * Manager trả đơn lại, yêu cầu nhân viên bổ sung thông tin.
     * Lưu ý: KHÔNG ghi đè approvedBy ở đây, vì field này đang được dùng làm
     * khóa định tuyến đơn tới đúng quản lý phụ trách (xem saveConfirm() +
     * getManagerPendingRequests()). Nếu ghi đè, khi nhân viên gửi lại đơn,
     * hệ thống sẽ không còn biết định tuyến đơn cho đúng quản lý nữa.
     */
    @Transactional
    public void returnForMoreInfo(Long id, String approverNo, String managerComment) {
        AttendanceConfirmation confirm = confirmationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn giải trình số: " + id));

        if (!"PENDING".equalsIgnoreCase(confirm.getApprovalStatus())) {
            throw new IllegalStateException("Chỉ có thể trả lại đơn đang ở trạng thái chờ duyệt!");
        }

        confirm.setApprovalStatus("RETURNED");
        confirm.setManagerComment(managerComment);
        confirm.setApprovedAt(LocalDateTime.now()); // mốc thời gian xử lý gần nhất
        confirmationRepository.save(confirm);
        notifyResult(confirm, "Giải trình ngày " + dm(confirm.getAttendanceDate()) + " bị TRẢ LẠI, cần bổ sung",
                managerComment == null || managerComment.isBlank() ? null : "Quản lý ghi: " + managerComment.trim());   // 🔔

        log.info("↩️ Quản lý {} đã trả lại đơn giải trình ID {} của NV {} để bổ sung thông tin",
                approverNo, id, confirm.getEmployeeNo());
    }

    /**
     * Nhân viên sửa lại đơn bị trả về (RETURNED) và gửi lại để chờ duyệt (PENDING).
     * approvedBy giữ nguyên (đã trỏ đúng quản lý phụ trách từ lúc tạo đơn ban đầu).
     */
    @Transactional
    public void resubmitConfirm(Long id, String employeeNo, String confirmStatus,
                                String note, String issueType, String sessionType) {
        AttendanceConfirmation confirm = confirmationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn giải trình số: " + id));

        if (!confirm.getEmployeeNo().equalsIgnoreCase(employeeNo)) {
            throw new IllegalStateException("Không có quyền sửa đơn này!");
        }
        if (!"RETURNED".equalsIgnoreCase(confirm.getApprovalStatus())) {
            throw new IllegalStateException("Đơn không ở trạng thái bị trả lại, không thể gửi lại!");
        }

        confirm.setConfirmStatus(confirmStatus);
        confirm.setNote(note);
        confirm.setIssueType(issueType);
        confirm.setSessionType(sessionType);
        confirm.setApprovalStatus("PENDING");
        confirm.setManagerComment(null);
        // approvedBy giữ nguyên để không phá hỏng định tuyến tới đúng quản lý
        confirmationRepository.save(confirm);
        userRepository.findByEmployeeNo(employeeNo).ifPresent(emp ->   // 🔔 người duyệt: đơn đã bổ sung
                notificationService.sendToEmployeeNo(confirm.getApprovedBy(), "CONFIRM",
                        "Đơn giải trình đã bổ sung: " + nameOf(emp),
                        "Ngày " + dm(confirm.getAttendanceDate()) + " · " + sessionLabel(sessionType) + " · " + issueLabel(issueType),
                        "/attendance/manager/attendance-confirms", null));

        log.info("📤 Nhân viên {} đã sửa và gửi lại đơn giải trình ID {}", employeeNo, id);
    }

    // 🌟 HOÀN THIỆN HÀM XÓA ĐƠN ĐANG CHỜ DUYỆT (BỊ CẮT CỤT PHÍA TRÊN)
    @Transactional
    public void deletePendingConfirm(Long id, String employeeNo) {
        AttendanceConfirmation confirm = confirmationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn giải trình số: " + id));

        // Bảo mật: Chỉ cho phép xóa khi đơn thuộc đúng nhân viên đó và trạng thái vẫn là PENDING
        if (confirm.getEmployeeNo().equalsIgnoreCase(employeeNo) && "PENDING".equalsIgnoreCase(confirm.getApprovalStatus())) {
            confirmationRepository.delete(confirm);
            log.info("🗑️ Nhân viên {} đã tự xóa đơn giải trình thành công (ID: {})", employeeNo, id);
        } else {
            throw new IllegalStateException("Không thể xóa đơn đã xử lý hoặc đơn không thuộc quyền sở hữu!");
        }
    }
}