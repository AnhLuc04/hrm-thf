package com.example.hrm.service;

import com.example.hrm.dto.AttendanceReportRow;
import com.example.hrm.entity.Attendance;
import com.example.hrm.entity.AttendanceConfirmation;
import com.example.hrm.entity.HolidayConfig;
import com.example.hrm.repository.AttendanceConfirmationRepository;
import com.example.hrm.repository.AttendanceRepository;
import com.example.hrm.repository.HolidayConfigRepository;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceReportService {

    private final AttendanceRepository attendanceRepository;
    private final AttendanceConfirmationRepository confirmationRepository;
    private final HolidayConfigRepository holidayConfigRepository;
    private final UserRepository userRepository;
    private final UnitScope unitScope;

    // THAY ĐỔI: Thêm tham số filterEmployeeNo để phân quyền (null nếu lấy hết, có mã nếu lấy cá nhân)
    public List<AttendanceReportRow> buildMonthlyReport(String month, String filterEmployeeNo) {
        return buildMonthlyReport(month, filterEmployeeNo, null);
    }

    /**
     * @param scopeEmpNos ĐƠN VỊ: chỉ giữ các mã nhân viên này (viết hoa); null = không lọc
     */
    public List<AttendanceReportRow> buildMonthlyReport(String month, String filterEmployeeNo, Set<String> scopeEmpNos) {
        YearMonth ym = YearMonth.parse(month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        List<Attendance> list = attendanceRepository.findByDateBetween(start, end);
        List<AttendanceConfirmation> approvedConfirms = confirmationRepository
                .findByAttendanceDateBetweenAndApprovalStatus(start, end, "APPROVED");

        List<HolidayConfig> holidays = holidayConfigRepository.findByHolidayDateBetween(start, end);
        Map<LocalDate, Double> holidayMapConfig = holidays.stream()
                .collect(java.util.stream.Collectors.toMap(HolidayConfig::getHolidayDate, HolidayConfig::getWageMultiplier, (v1, v2) -> v1));

        Map<String, AttendanceReportRow> reportMap = new java.util.LinkedHashMap<>();
        DateTimeFormatter tf = DateTimeFormatter.ofPattern("HH:mm");

        // BƯỚC 1 & 2: KHỞI TẠO DTO CHUẨN XÁC VÀ ÁP DỤNG BỘ LỌC PHÂN QUYỀN
        for (Attendance a : list) {
            if (a.getUser() == null || a.getUser().getEmployeeNo() == null) continue;
            String empNo = a.getUser().getEmployeeNo();

            // Nếu lọc theo cá nhân mà không trùng mã -> Bỏ qua
            if (filterEmployeeNo != null && !filterEmployeeNo.equalsIgnoreCase(empNo)) continue;
            if (scopeEmpNos != null && !scopeEmpNos.contains(empNo.trim().toUpperCase())) continue;   // khác đơn vị

            String fullName = a.getUser().getFullName();
            String department = a.getUser().getDepartment(); // ← SỬA: lấy phòng ban từ User
            reportMap.computeIfAbsent(empNo, k -> {
                AttendanceReportRow row = new AttendanceReportRow(empNo, fullName);
                row.setDepartment(department); // ← SỬA: gán phòng ban vào row
                return row;
            });
        }

        for (AttendanceConfirmation confirm : approvedConfirms) {
            String empNo = confirm.getEmployeeNo();
            if (empNo == null) continue;

            // Nếu lọc theo cá nhân mà không trùng mã -> Bỏ qua
            if (filterEmployeeNo != null && !filterEmployeeNo.equalsIgnoreCase(empNo)) continue;
            if (scopeEmpNos != null && !scopeEmpNos.contains(empNo.trim().toUpperCase())) continue;   // khác đơn vị

            reportMap.computeIfAbsent(empNo, k -> {
                String fullName = (confirm.getUser() != null) ? confirm.getUser().getFullName() : empNo;
                String department = (confirm.getUser() != null) ? confirm.getUser().getDepartment() : null; // ← SỬA
                AttendanceReportRow row = new AttendanceReportRow(empNo, fullName);
                row.setDepartment(department); // ← SỬA: gán phòng ban vào row
                return row;
            });
        }

        // BƯỚC 3: Phân tích dữ liệu quẹt thẻ thực tế
        Map<String, List<LocalDateTime>> grouped = new java.util.LinkedHashMap<>();
        for (Attendance a : list) {
            if (a.getUser() == null || a.getUser().getEmployeeNo() == null) continue;
            String empNo = a.getUser().getEmployeeNo();

            // Áp dụng bộ lọc cá nhân cho khâu map giờ quẹt
            if (filterEmployeeNo != null && !filterEmployeeNo.equalsIgnoreCase(empNo)) continue;
            if (scopeEmpNos != null && !scopeEmpNos.contains(empNo.trim().toUpperCase())) continue;   // khác đơn vị

            String key = empNo + "_" + a.getDate();
            grouped.putIfAbsent(key, new java.util.ArrayList<>());
            if (a.getCheckInTime() != null) grouped.get(key).add(a.getCheckInTime());
            if (a.getCheckOutTime() != null) grouped.get(key).add(a.getCheckOutTime());
        }

        Map<String, LocalDateTime> earliestCheckInMap = new HashMap<>();

        for (String key : grouped.keySet()) {
            List<LocalDateTime> times = grouped.get(key);
            if (times == null || times.isEmpty()) continue;
            times.sort(LocalDateTime::compareTo);
            String employeeNo = key.split("_")[0];
            LocalDate date = times.get(0).toLocalDate();

            String morning = "-";
            String afternoon = "-";

            if (times.size() == 1) {
                if (times.get(0).getHour() >= 13) {
                    afternoon = times.get(0).format(tf);
                } else {
                    morning = times.get(0).format(tf);
                    earliestCheckInMap.put(key, times.get(0));
                }
            } else {
                if (times.get(0).getHour() < 13) {
                    morning = times.get(0).format(tf);
                    earliestCheckInMap.put(key, times.get(0));
                }
                if (times.get(times.size() - 1).getHour() >= 13) {
                    afternoon = times.get(times.size() - 1).format(tf);
                }
            }

            // 🌟 THÊM MỚI: tính tổng số PHÚT đã làm thực tế trong ngày, ghép theo từng cặp quẹt liên tiếp
            // (quẹt 1<->2 = phiên làm việc 1, quẹt 3<->4 = phiên làm việc 2,...) để tự động loại trừ
            // khoảng nghỉ trưa nếu có quẹt ra/vào lúc nghỉ trưa. Dùng cho tính công tăng ca Chủ Nhật/Lễ theo giờ.
            long workedMinutes = 0L;
            for (int p = 0; p + 1 < times.size(); p += 2) {
                workedMinutes += java.time.Duration.between(times.get(p), times.get(p + 1)).toMinutes();
            }

            AttendanceReportRow row = reportMap.get(employeeNo);
            if (row != null) {
                Map<String, Object> cell = row.getDailyData().computeIfAbsent(String.valueOf(date.getDayOfMonth()), k -> new java.util.HashMap<>());
                cell.put("morning", morning);
                cell.put("afternoon", afternoon);
                cell.put("workedMinutes", workedMinutes); // 🌟 THÊM MỚI
            }
        }

        // BƯỚC 4: Đè đơn giải trình đã duyệt
        for (AttendanceConfirmation confirm : approvedConfirms) {
            String empNo = confirm.getEmployeeNo();
            if (empNo == null || confirm.getAttendanceDate() == null) continue;

            // Áp dụng bộ lọc cá nhân cho khâu đè đơn giải trình
            if (filterEmployeeNo != null && !filterEmployeeNo.equalsIgnoreCase(empNo)) continue;
            if (scopeEmpNos != null && !scopeEmpNos.contains(empNo.trim().toUpperCase())) continue;   // khác đơn vị

            int dayAttr = confirm.getAttendanceDate().getDayOfMonth();
            AttendanceReportRow row = reportMap.get(empNo);

            if (row != null) {
                Map<String, Object> cell = row.getDailyData().computeIfAbsent(String.valueOf(dayAttr), k -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("morning", "-");
                    m.put("afternoon", "-");
                    return m;
                });

                String label = switch (confirm.getConfirmStatus() != null ? confirm.getConfirmStatus() : "") {
                    case "PRESENT" -> "XN";
                    case "ANNUAL_LEAVE" -> "PN";
                    case "HALF_DAY" -> "X";
                    case "UNPAID_LEAVE" -> "CP";
                    case "HOLIDAY" -> "LE";
                    case "HOLIDAY_WORK" -> "CDL";
                    default -> "Duyệt";
                };

                if ("MORNING".equalsIgnoreCase(confirm.getSessionType())) {
                    cell.put("confirmMorning", label);
                } else if ("AFTERNOON".equalsIgnoreCase(confirm.getSessionType())) {
                    cell.put("confirmAfternoon", label);
                } else {
                    cell.put("confirmMorning", label);
                    cell.put("confirmAfternoon", label);
                }
            }
        }

        // BƯỚC 5: TÍNH CÔNG & KHẤU TRỪ PHẠT THEO THỜI GIAN THỰC ĐẾN TỪNG PHÚT HÔM NAY
        LocalDate today = LocalDate.now();
        LocalTime nowTime = LocalTime.now();
        LocalTime defaultLateDeadLine = LocalTime.of(8, 05, 59);

        // GIỜ LÀM THEO ĐƠN VỊ: mốc tính vi phạm của từng nhân viên (đơn vị gốc: 08:05 như cũ)
        Map<String, LocalTime> lateByEmp = new HashMap<>();
        Map<String, CompanyUnit> unitByEmp = new HashMap<>();   // lịch làm việc trong tuần của đơn vị
        if (!reportMap.isEmpty()) {
            for (User u : userRepository.findByEmployeeNoIn(new ArrayList<>(reportMap.keySet()))) {
                CompanyUnit unit = unitScope.unitOf(u);
                if (u.getEmployeeNo() != null && unit != null) {
                    lateByEmp.put(u.getEmployeeNo(), unit.violationAfterOrDefault().withSecond(59));
                    unitByEmp.put(u.getEmployeeNo(), unit);
                }
            }
        }

        for (AttendanceReportRow row : reportMap.values()) {
            LocalTime lateDeadLine = lateByEmp.getOrDefault(row.getEmployeeNo(), defaultLateDeadLine);
            CompanyUnit rowUnit = unitByEmp.get(row.getEmployeeNo());   // null = nghỉ Chủ nhật như bản cũ
            double actualWorkDays = 0.0;
            double confirmWorkDays = 0.0;
            double leaveDays = 0.0;
            double holidayDays = 0.0;
            int violationCount = 0;

            // 🌟 THÊM MỚI: cộng dồn số CÔNG tăng ca Chủ Nhật và Ngày Lễ trong cả tháng (đơn vị 0.5)
            double sundayOvertimeDays = 0.0;
            double holidayOvertimeDays = 0.0;

            for (int d = 1; d <= ym.lengthOfMonth(); d++) {
                LocalDate currentDate = ym.atDay(d);

                if (currentDate.isAfter(today)) continue;

                Map<String, Object> cellData = row.getDailyData().get(String.valueOf(d));
                Double wageMultiplier = holidayMapConfig.get(currentDate);
                boolean isConfiguredHoliday = (wageMultiplier != null);

                // 🌟 SỬA: nếu là Chủ Nhật, tính CÔNG theo GIỜ ĐÃ LÀM THỰC TẾ / 8 (chuẩn 1 công = 8 giờ),
                // không còn cộng cứng 0.5/buổi có mặt nữa. Làm ít hơn 8h thì công tính theo tỉ lệ,
                // làm hơn 8h thì phần dư vẫn cộng tiếp (không giới hạn ở 1.0).
                // LỊCH LÀM VIỆC ĐƠN VỊ: ngày nghỉ trong tuần tính như Chủ nhật bản cũ
                if (CompanyUnit.isOffDay(rowUnit, currentDate.getDayOfWeek())) {
                    if (cellData != null && cellData.get("workedMinutes") != null) {
                        long minutes = (Long) cellData.get("workedMinutes");
                        sundayOvertimeDays += minutes / 60.0 / 8.0;
                    }
                    continue;
                }

                if (isConfiguredHoliday) {
                    if (cellData == null) {
                        holidayDays += 1.0;
                    } else {
                        String mConf = (String) cellData.get("confirmMorning");
                        String mTime = (String) cellData.get("morning");
                        if (mConf != null && "CDL".equals(mConf)) holidayDays += 0.5 * wageMultiplier;
                        else if (mTime != null && !"-".equals(mTime)) holidayDays += 0.5 * wageMultiplier;
                        else holidayDays += 0.5;

                        String aConf = (String) cellData.get("confirmAfternoon");
                        String aTime = (String) cellData.get("afternoon");
                        if (aConf != null && "CDL".equals(aConf)) holidayDays += 0.5 * wageMultiplier;
                        else if (aTime != null && !"-".equals(aTime)) holidayDays += 0.5 * wageMultiplier;
                        else holidayDays += 0.5;

                        // 🌟 SỬA: tính công tăng ca Ngày Lễ theo GIỜ ĐÃ LÀM THỰC TẾ / 8 (không nhân hệ số lương,
                        // cột này chỉ phản ánh số công đã làm thực để đối chiếu, không phải cột tính lương)
                        if (cellData.get("workedMinutes") != null) {
                            long minutes = (Long) cellData.get("workedMinutes");
                            holidayOvertimeDays += minutes / 60.0 / 8.0;
                        }
                    }
                    continue;
                }

                boolean morningOnly = CompanyUnit.isMorningOnly(rowUnit, currentDate.getDayOfWeek());
                if (cellData == null) {
                    if (currentDate.isBefore(today)) {
                        violationCount += morningOnly ? 1 : 2;
                    } else if (currentDate.equals(today)) {
                        if (nowTime.isAfter(lateDeadLine)) {
                            violationCount += 1;
                        }
                    }
                    continue;
                }

                String groupKey = row.getEmployeeNo() + "_" + currentDate;
                LocalDateTime checkInDateTime = earliestCheckInMap.get(groupKey);

                // --- XỬ LÝ CHI TIẾT CA SÁNG ---
                String mTime = (String) cellData.get("morning");
                String mConf = (String) cellData.get("confirmMorning");

                if (mConf != null && !"-".equals(mConf)) {
                    if ("XN".equals(mConf) || "X".equals(mConf)) confirmWorkDays += 0.5;
                    else if ("PN".equals(mConf)) leaveDays += 0.5;
                    else if ("LE".equals(mConf)) holidayDays += 0.5;
                } else {
                    if (mTime != null && !"-".equals(mTime)) {
                        actualWorkDays += 0.5;
                        if (checkInDateTime != null && checkInDateTime.toLocalTime().isAfter(lateDeadLine)) {
                            violationCount += 1;
                        }
                    } else {
                        if (currentDate.isBefore(today) || (currentDate.equals(today) && nowTime.isAfter(lateDeadLine))) {
                            violationCount += 1;
                        }
                    }
                }

                // --- XỬ LÝ CHI TIẾT CA CHIỀU ---
                String aTime = (String) cellData.get("afternoon");
                String aConf = (String) cellData.get("confirmAfternoon");

                if (aConf != null && !"-".equals(aConf)) {
                    if ("XN".equals(aConf) || "X".equals(aConf)) confirmWorkDays += 0.5;
                    else if ("PN".equals(aConf)) leaveDays += 0.5;
                    else if ("LE".equals(aConf)) holidayDays += 0.5;
                } else {
                    if (aTime != null && !"-".equals(aTime)) {
                        actualWorkDays += 0.5;
                    } else {
                        if (currentDate.isBefore(today) && !morningOnly) {
                            violationCount += 1;
                        }
                    }
                }
            }

            row.setTotalActualWorkDays(actualWorkDays);
            row.setTotalConfirmWorkDays(confirmWorkDays);
            row.setTotalLeaveDays(leaveDays);
            row.setTotalHolidayDays(holidayDays);
            row.setTotalWorkDays(actualWorkDays + confirmWorkDays + leaveDays + holidayDays);

            row.setViolationCount(violationCount);
            row.setFineAmount(violationCount * 50000.0);

            // 🌟 THÊM MỚI: gán số công tăng ca Chủ Nhật / Ngày Lễ đã cộng dồn cả tháng (làm tròn 2 chữ số)
            row.setOvertimeSunday(Math.round(sundayOvertimeDays * 100.0) / 100.0);
            row.setOvertimeHoliday(Math.round(holidayOvertimeDays * 100.0) / 100.0);
        }

        return reportMap.values().stream().filter(Objects::nonNull).toList();
    }

    // ĐỒNG BỘ: Export Excel - MỖI NHÂN VIÊN CHIẾM 2 DÒNG THỰC (Giờ vào / Giờ ra)
    // thay vì gộp "morning\nafternoon" vào 1 ô như trước, để khớp đúng format file gốc Hikvision
    public ByteArrayInputStream exportExcel(String month, String filterEmployeeNo) throws Exception {
        return exportExcel(month, filterEmployeeNo, null);
    }

    public ByteArrayInputStream exportExcel(String month, String filterEmployeeNo, Set<String> scopeEmpNos) throws Exception {
        List<AttendanceReportRow> report = buildMonthlyReport(month, filterEmployeeNo, scopeEmpNos);

        // Sắp xếp theo phòng ban, cùng phòng thì theo tên A-Z (đồng bộ với bảng xem trên web)
        report = new ArrayList<>(report);
        report.sort(Comparator
                .comparing(AttendanceReportRow::getDepartment, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(AttendanceReportRow::getFullName, String.CASE_INSENSITIVE_ORDER));

        YearMonth ym = YearMonth.parse(month);

        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet("Bảng Chấm Công");

        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        headerStyle.setWrapText(true);

        CellStyle dataStyle = workbook.createCellStyle();
        dataStyle.setAlignment(HorizontalAlignment.CENTER);
        dataStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle nameStyle = workbook.createCellStyle();
        nameStyle.cloneStyleFrom(dataStyle);
        nameStyle.setAlignment(HorizontalAlignment.LEFT);

        CellStyle moneyStyle = workbook.createCellStyle();
        moneyStyle.cloneStyleFrom(dataStyle);
        DataFormat format = workbook.createDataFormat();
        moneyStyle.setDataFormat(format.getFormat("#,##0\" đ\""));

        // Style riêng cho dòng tiêu đề nhóm phòng ban
        CellStyle deptStyle = workbook.createCellStyle();
        Font deptFont = workbook.createFont();
        deptFont.setBold(true);
        deptStyle.setFont(deptFont);
        deptStyle.setAlignment(HorizontalAlignment.LEFT);
        deptStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        deptStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        deptStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        int rowIdx = 0;
        Row header = sheet.createRow(rowIdx++);
        header.setHeightInPoints(30);

        Cell h0 = header.createCell(0); h0.setCellValue("Mã NV"); h0.setCellStyle(headerStyle);
        Cell h1 = header.createCell(1); h1.setCellValue("Họ tên"); h1.setCellStyle(headerStyle);

        int lastDayCol = ym.lengthOfMonth();
        for (int d = 1; d <= lastDayCol; d++) {
            Cell c = header.createCell(d + 1);
            c.setCellValue(d + "\n" + ym.atDay(d).getDayOfWeek().name().substring(0, 3));
            c.setCellStyle(headerStyle);
        }

        Cell hTotalWork = header.createCell(lastDayCol + 2);
        hTotalWork.setCellValue("Tổng Công");
        hTotalWork.setCellStyle(headerStyle);

        Cell hTotalLeave = header.createCell(lastDayCol + 3);
        hTotalLeave.setCellValue("Phép Năm");
        hTotalLeave.setCellStyle(headerStyle);

        Cell hViolation = header.createCell(lastDayCol + 4);
        hViolation.setCellValue("Lỗi (Buổi)");
        hViolation.setCellStyle(headerStyle);

        Cell hFineAmount = header.createCell(lastDayCol + 5);
        hFineAmount.setCellValue("Tiền Phạt");
        hFineAmount.setCellStyle(headerStyle);

        String lastDepartment = null;

        for (AttendanceReportRow rowData : report) {

            // ← THÊM: Chèn dòng tiêu đề phòng ban mỗi khi đổi phòng
            String dept = rowData.getDepartment();
            boolean isFirst = (lastDepartment == null && rowIdx == 1);
            if (rowIdx == 1 || !java.util.Objects.equals(dept, lastDepartment)) {
                Row deptRow = sheet.createRow(rowIdx++);
                deptRow.setHeightInPoints(20);
                Cell deptCell = deptRow.createCell(0);
                deptCell.setCellValue((dept != null && !dept.isBlank()) ? dept : "Chưa phân phòng ban");
                deptCell.setCellStyle(deptStyle);
                for (int i = 1; i <= lastDayCol + 5; i++) {
                    Cell fillCell = deptRow.createCell(i);
                    fillCell.setCellStyle(deptStyle);
                }
                sheet.addMergedRegion(new CellRangeAddress(deptRow.getRowNum(), deptRow.getRowNum(), 0, lastDayCol + 5));
                lastDepartment = dept;
            }

            // ← THÊM: 2 dòng thực cho mỗi nhân viên (dòng vào / dòng ra)
            int checkinRowIdx = rowIdx++;
            int checkoutRowIdx = rowIdx++;

            Row checkinRow = sheet.createRow(checkinRowIdx);
            Row checkoutRow = sheet.createRow(checkoutRowIdx);
            checkinRow.setHeightInPoints(18);
            checkoutRow.setHeightInPoints(18);

            // Mã NV, Họ tên: merge dọc 2 dòng
            Cell cell0 = checkinRow.createCell(0); cell0.setCellValue(rowData.getEmployeeNo()); cell0.setCellStyle(dataStyle);
            checkoutRow.createCell(0).setCellStyle(dataStyle);
            sheet.addMergedRegion(new CellRangeAddress(checkinRowIdx, checkoutRowIdx, 0, 0));

            Cell cell1 = checkinRow.createCell(1); cell1.setCellValue(rowData.getFullName()); cell1.setCellStyle(nameStyle);
            checkoutRow.createCell(1).setCellStyle(nameStyle);
            sheet.addMergedRegion(new CellRangeAddress(checkinRowIdx, checkoutRowIdx, 1, 1));

            // Cột từng ngày: dòng trên = giờ vào, dòng dưới = giờ ra
            for (int d = 1; d <= lastDayCol; d++) {
                Map<String, Object> cellData = rowData.getDailyData().get(String.valueOf(d));

                String morningText = "-";
                String afternoonText = "-";
                if (cellData != null) {
                    morningText = cellData.get("confirmMorning") != null ? cellData.get("confirmMorning").toString()
                            : (cellData.get("morning") != null ? cellData.get("morning").toString() : "-");
                    afternoonText = cellData.get("confirmAfternoon") != null ? cellData.get("confirmAfternoon").toString()
                            : (cellData.get("afternoon") != null ? cellData.get("afternoon").toString() : "-");
                }

                Cell inCell = checkinRow.createCell(d + 1);
                inCell.setCellValue(morningText);
                inCell.setCellStyle(dataStyle);

                Cell outCell = checkoutRow.createCell(d + 1);
                outCell.setCellValue(afternoonText);
                outCell.setCellStyle(dataStyle);
            }

            // Các cột tổng hợp: merge dọc 2 dòng
            Cell cellTotalWork = checkinRow.createCell(lastDayCol + 2);
            cellTotalWork.setCellValue(rowData.getTotalWorkDays());
            cellTotalWork.setCellStyle(dataStyle);
            checkoutRow.createCell(lastDayCol + 2).setCellStyle(dataStyle);
            sheet.addMergedRegion(new CellRangeAddress(checkinRowIdx, checkoutRowIdx, lastDayCol + 2, lastDayCol + 2));

            Cell cellTotalLeave = checkinRow.createCell(lastDayCol + 3);
            cellTotalLeave.setCellValue(rowData.getTotalLeaveDays());
            cellTotalLeave.setCellStyle(dataStyle);
            checkoutRow.createCell(lastDayCol + 3).setCellStyle(dataStyle);
            sheet.addMergedRegion(new CellRangeAddress(checkinRowIdx, checkoutRowIdx, lastDayCol + 3, lastDayCol + 3));

            Cell cellViolation = checkinRow.createCell(lastDayCol + 4);
            cellViolation.setCellValue(rowData.getViolationCount());
            cellViolation.setCellStyle(dataStyle);
            checkoutRow.createCell(lastDayCol + 4).setCellStyle(dataStyle);
            sheet.addMergedRegion(new CellRangeAddress(checkinRowIdx, checkoutRowIdx, lastDayCol + 4, lastDayCol + 4));

            Cell cellFine = checkinRow.createCell(lastDayCol + 5);
            cellFine.setCellValue(rowData.getFineAmount());
            cellFine.setCellStyle(moneyStyle);
            checkoutRow.createCell(lastDayCol + 5).setCellStyle(moneyStyle);
            sheet.addMergedRegion(new CellRangeAddress(checkinRowIdx, checkoutRowIdx, lastDayCol + 5, lastDayCol + 5));
        }

        for (int i = 0; i <= lastDayCol + 5; i++) {
            sheet.autoSizeColumn(i);
            sheet.setColumnWidth(i, sheet.getColumnWidth(i) + 800);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();
        return new ByteArrayInputStream(out.toByteArray());
    }
}