//package com.example.hrm.dto;
//
//import java.util.HashMap;
//import java.util.Map;
//
//public class AttendanceReportRow {
//    private String employeeNo;
//    private String fullName;
//    private String department; // ← THÊM MỚI
//
//    // 3 Loại công phân tích chi tiết mới bổ sung
//    private double totalActualWorkDays = 0.0;  // Công đi làm thực tế (Quẹt thẻ máy)
//    private double totalConfirmWorkDays = 0.0; // Công xác nhận (Đơn giải trình quên quẹt, công tác)
//    private double totalLeaveDays = 0.0;       // Tổng số ngày nghỉ phép năm (PN)
//    private double totalHolidayDays = 0.0;     // Công ngày lễ (Nghỉ lễ LE hoặc Đi làm lễ CDL nhân hệ số)
//
//    // Tổng số cuối cùng hưởng lương tổng hợp
//    private double totalWorkDays = 0.0;        // Tổng công cuối tháng (Bao gồm tất cả các loại công trên)
//
//    // BỔ SUNG: Biến lưu số buổi vi phạm kỷ luật và tiền phạt tương ứng
//    private int violationCount = 0;            // Số ca/buổi vi phạm không phép
//    private double fineAmount = 0.0;           // Tổng tiền phạt tương ứng (Số ca * 50,000đ)
//
//    private Map<String, Map<String, Object>> dailyData = new HashMap<>();
//
//    public AttendanceReportRow(String employeeNo, String fullName) {
//        this.employeeNo = employeeNo;
//        this.fullName = fullName;
//    }
//
//    // Constructor mới có thêm department (dùng nếu muốn truyền luôn lúc khởi tạo)
//    public AttendanceReportRow(String employeeNo, String fullName, String department) {
//        this.employeeNo = employeeNo;
//        this.fullName = fullName;
//        this.department = department;
//    }
//
//    // ==================== GETTERS AND SETTERS ====================
//
//    public String getEmployeeNo() { return employeeNo; }
//    public void setEmployeeNo(String employeeNo) { this.employeeNo = employeeNo; }
//
//    public String getFullName() { return fullName; }
//    public void setFullName(String fullName) { this.fullName = fullName; }
//
//    // BỔ SUNG: Getter/Setter cho Phòng ban
//    public String getDepartment() { return department; }
//    public void setDepartment(String department) { this.department = department; }
//
//    // 1. Công đi làm thực tế
//    public double getTotalActualWorkDays() { return totalActualWorkDays; }
//    public void setTotalActualWorkDays(double totalActualWorkDays) { this.totalActualWorkDays = totalActualWorkDays; }
//
//    // 2. Công xác nhận đơn từ
//    public double getTotalConfirmWorkDays() { return totalConfirmWorkDays; }
//    public void setTotalConfirmWorkDays(double totalConfirmWorkDays) { this.totalConfirmWorkDays = totalConfirmWorkDays; }
//
//    // 3. Công phép năm
//    public double getTotalLeaveDays() { return totalLeaveDays; }
//    public void setTotalLeaveDays(double totalLeaveDays) { this.totalLeaveDays = totalLeaveDays; }
//
//    // 4. Công ngày lễ (nhân hệ số)
//    public double getTotalHolidayDays() { return totalHolidayDays; }
//    public void setTotalHolidayDays(double totalHolidayDays) { this.totalHolidayDays = totalHolidayDays; }
//
//    // 5. Tổng công hưởng lương tổng hợp
//    public double getTotalWorkDays() { return totalWorkDays; }
//    public void setTotalWorkDays(double totalWorkDays) { this.totalWorkDays = totalWorkDays; }
//
//    // BỔ SUNG: Getter/Setter cho Số buổi vi phạm kỷ luật
//    public int getViolationCount() { return violationCount; }
//    public void setViolationCount(int violationCount) { this.violationCount = violationCount; }
//
//    // BỔ SUNG: Getter/Setter cho Số tiền phạt kỷ luật
//    public double getFineAmount() { return fineAmount; }
//    public void setFineAmount(double fineAmount) { this.fineAmount = fineAmount; }
//
//    // Bản đồ dữ liệu ngày
//    public Map<String, Map<String, Object>> getDailyData() { return dailyData; }
//    public void setDailyData(Map<String, Map<String, Object>> dailyData) { this.dailyData = dailyData; }
//}

















package com.example.hrm.dto;

import java.util.HashMap;
import java.util.Map;

public class AttendanceReportRow {
    private String employeeNo;
    private String fullName;
    private String department;

    // ==== CÔNG (BT / XN) ====
    private double totalActualWorkDays = 0.0;  // Công đi làm thực tế (BT - Quẹt thẻ máy)
    private double totalConfirmWorkDays = 0.0; // Công xác nhận (XN - Đơn giải trình quên quẹt, công tác)

    // ==== TĂNG CA (QGiờ / CN / Lễ) ====
    // BỔ SUNG: Chưa có bảng/field lưu giờ tăng ca trong hệ thống hiện tại.
    // Để 3 field này ở mức "không có dữ liệu" (null) cho tới khi có business logic tính tăng ca.
    private Double overtimeNormalHours = null; // QGiờ - Tăng ca giờ thường
    private Double overtimeSunday = null;      // CN - Tăng ca chủ nhật
    private Double overtimeHoliday = null;     // Lễ - Tăng ca ngày lễ

    private double totalLeaveDays = 0.0;       // Tổng số ngày nghỉ phép năm ĐÃ DÙNG trong tháng (PN)
    private double totalHolidayDays = 0.0;     // Lễ/Tết - Công ngày lễ (Nghỉ lễ LE hoặc Đi làm lễ CDL nhân hệ số)

    // Tổng số cuối cùng hưởng lương tổng hợp
    private double totalWorkDays = 0.0;        // Tổng Công cuối tháng (Bao gồm tất cả các loại công trên)

    // ==== PHÉP NĂM (Tháng này / Tích lũy / Sử dụng / Còn lại) ====
    // BỔ SUNG: "Tháng này" (phát sinh trong tháng), "Tích lũy" (lũy kế từ đầu năm),
    // "Còn lại" (số dư hiện tại) đều phụ thuộc CHÍNH SÁCH PHÉP NĂM (số ngày/tháng, ngày vào làm...)
    // mà hệ thống hiện tại CHƯA lưu trữ. Để null cho tới khi có nghiệp vụ tính rõ ràng.
    private Double leaveAccruedThisMonth = null; // Phép Năm > Tháng này
    private Double leaveAccruedTotal = null;     // Phép Năm > Tích lũy
    // "Sử dụng" = số phép đã dùng trong tháng => dùng lại totalLeaveDays, không cần field riêng
    private Double leaveRemaining = null;        // Phép Năm > Còn lại

    // BỔ SUNG: Biến lưu số buổi vi phạm kỷ luật và tiền phạt tương ứng
    private int violationCount = 0;            // Số ca/buổi vi phạm không phép
    private double fineAmount = 0.0;           // Tổng tiền phạt tương ứng (Số ca * 50,000đ)

    private Map<String, Map<String, Object>> dailyData = new HashMap<>();

    public AttendanceReportRow(String employeeNo, String fullName) {
        this.employeeNo = employeeNo;
        this.fullName = fullName;
    }

    // ==================== GETTERS AND SETTERS ====================

    public String getEmployeeNo() { return employeeNo; }
    public void setEmployeeNo(String employeeNo) { this.employeeNo = employeeNo; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    // 1. Công đi làm thực tế (BT)
    public double getTotalActualWorkDays() { return totalActualWorkDays; }
    public void setTotalActualWorkDays(double totalActualWorkDays) { this.totalActualWorkDays = totalActualWorkDays; }

    // 2. Công xác nhận đơn từ (XN)
    public double getTotalConfirmWorkDays() { return totalConfirmWorkDays; }
    public void setTotalConfirmWorkDays(double totalConfirmWorkDays) { this.totalConfirmWorkDays = totalConfirmWorkDays; }

    // 3. Tăng ca (chưa có nghiệp vụ tính - luôn null cho tới khi bổ sung)
    public Double getOvertimeNormalHours() { return overtimeNormalHours; }
    public void setOvertimeNormalHours(Double overtimeNormalHours) { this.overtimeNormalHours = overtimeNormalHours; }

    public Double getOvertimeSunday() { return overtimeSunday; }
    public void setOvertimeSunday(Double overtimeSunday) { this.overtimeSunday = overtimeSunday; }

    public Double getOvertimeHoliday() { return overtimeHoliday; }
    public void setOvertimeHoliday(Double overtimeHoliday) { this.overtimeHoliday = overtimeHoliday; }

    // 4. Công phép năm đã dùng trong tháng
    public double getTotalLeaveDays() { return totalLeaveDays; }
    public void setTotalLeaveDays(double totalLeaveDays) { this.totalLeaveDays = totalLeaveDays; }

    // 5. Công ngày lễ (nhân hệ số)
    public double getTotalHolidayDays() { return totalHolidayDays; }
    public void setTotalHolidayDays(double totalHolidayDays) { this.totalHolidayDays = totalHolidayDays; }

    // 6. Tổng công hưởng lương tổng hợp
    public double getTotalWorkDays() { return totalWorkDays; }
    public void setTotalWorkDays(double totalWorkDays) { this.totalWorkDays = totalWorkDays; }

    // 7. Phép năm - Tháng này / Tích lũy / Còn lại (chưa có nghiệp vụ tính - luôn null)
    public Double getLeaveAccruedThisMonth() { return leaveAccruedThisMonth; }
    public void setLeaveAccruedThisMonth(Double leaveAccruedThisMonth) { this.leaveAccruedThisMonth = leaveAccruedThisMonth; }

    public Double getLeaveAccruedTotal() { return leaveAccruedTotal; }
    public void setLeaveAccruedTotal(Double leaveAccruedTotal) { this.leaveAccruedTotal = leaveAccruedTotal; }

    public Double getLeaveRemaining() { return leaveRemaining; }
    public void setLeaveRemaining(Double leaveRemaining) { this.leaveRemaining = leaveRemaining; }

    // BỔ SUNG: Getter/Setter cho Số buổi vi phạm kỷ luật
    public int getViolationCount() { return violationCount; }
    public void setViolationCount(int violationCount) { this.violationCount = violationCount; }

    // BỔ SUNG: Getter/Setter cho Số tiền phạt kỷ luật
    public double getFineAmount() { return fineAmount; }
    public void setFineAmount(double fineAmount) { this.fineAmount = fineAmount; }

    // Bản đồ dữ liệu ngày
    public Map<String, Map<String, Object>> getDailyData() { return dailyData; }
    public void setDailyData(Map<String, Map<String, Object>> dailyData) { this.dailyData = dailyData; }
}