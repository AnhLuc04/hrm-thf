package com.example.hrm.repository;

import com.example.hrm.entity.AttendanceConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceConfirmationRepository extends JpaRepository<AttendanceConfirmation, Long> {

    @Query("SELECT c FROM AttendanceConfirmation c " +
            "WHERE c.approvalStatus = 'PENDING' " +
            "AND c.attendanceDate BETWEEN :from AND :to " +
            "AND (" +
            "    c.approvedBy = :employeeNo " + // Case 1: Đích danh người duyệt
            "    OR (" +
            "        c.approvedBy IS NULL " +
            "        AND c.employeeNo IN (" +
            "            SELECT u.employeeNo FROM User u " +
            "            WHERE u.department = :currentUserDepartment" + // Thắt chặt: Nhân viên phải cùng phòng với người đang đăng nhập duyệt
            "        )" +
            "    )" +
            ")")
    List<AttendanceConfirmation> findRequestsForApprover(
            @Param("currentUserDepartment") String currentUserDepartment,
            @Param("employeeNo") String employeeNo,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );




















    // 1. Cho Giám đốc: Chỉ lấy đơn của những người là Trưởng phòng (departmentManager = true)
    @Query("SELECT c FROM AttendanceConfirmation c JOIN User u ON c.employeeNo = u.employeeNo " +
            "WHERE u.departmentManager = true AND c.approvalStatus = 'PENDING' " +
            "AND c.attendanceDate BETWEEN :from AND :to")
    List<AttendanceConfirmation> findPendingRequestsForDirector(@Param("from") LocalDate from, @Param("to") LocalDate to);

    // 2. Cho Trưởng phòng: Lấy đơn của nhân viên thuộc phòng ban mình quản lý
    @Query("SELECT c FROM AttendanceConfirmation c JOIN User u ON c.employeeNo = u.employeeNo " +
            "WHERE u.department = :dept AND u.departmentManager = false " +
            "AND c.approvalStatus = 'PENDING' AND c.attendanceDate BETWEEN :from AND :to")
    List<AttendanceConfirmation> findPendingRequestsForDepartment(@Param("dept") String dept, @Param("from") LocalDate from, @Param("to") LocalDate to);
    // SỬA LẠI QUERY ĐẢM BẢO CHÍNH XÁC THEO KHÓA ĐƯỢC TRUYỀN VÀO
    @Query("SELECT c FROM AttendanceConfirmation c " +
            "WHERE c.approvalStatus = 'PENDING' " +
            "AND c.approvedBy = :approvedBy " +
            "AND c.attendanceDate BETWEEN :from AND :to")
    List<AttendanceConfirmation> findPendingRequestsByApprovedByKey(
            @Param("approvedBy") String approvedBy,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );
    // =========================================================================
    // QUẢN LÝ & SERVICE (LỌC THEO PHÒNG BAN SỬ DỤNG SUBQUERY JOIN SANG BẢNG USER)
    // =========================================================================
    // Sửa lỗi sập app: Tìm các đơn PENDING từ ngày X đến ngày Y thuộc các nhân viên có phòng ban chỉ định
    @Query("SELECT c FROM AttendanceConfirmation c WHERE c.approvalStatus = 'PENDING' " +
            "AND c.attendanceDate >= :from AND c.attendanceDate <= :to " +
            "AND c.employeeNo IN (SELECT u.employeeNo FROM User u WHERE u.department = :department)")
    List<AttendanceConfirmation> findPendingRequestsForManagerByMonth(
            @Param("department") String department,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    // =========================================================================
    // NHÂN VIÊN & LỌC LỊCH SỬ GIAO DIỆN CHẤM CÔNG
    // =========================================================================
    // Lọc lịch sử đơn của Nhân viên từ ngày 'startDate' đến ngày 'endDate' (Từ đầu tháng đến nay)
    @Query("SELECT c FROM AttendanceConfirmation c WHERE c.employeeNo = :employeeNo " +
            "AND c.approvalStatus = :status " +
            "AND c.attendanceDate >= :startDate AND c.attendanceDate <= :endDate")
    List<AttendanceConfirmation> findByEmployeeNoAndStatusAndMonth(
            @Param("employeeNo") String employeeNo,
            @Param("status") String status,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    // Kiểm tra trùng lặp: Một ca cụ thể trong ngày đã được gửi giải trình trước đó chưa
    boolean existsByEmployeeNoAndAttendanceDateAndSessionType(
            String employeeNo,
            LocalDate attendanceDate,
            String sessionType
    );

    // Đếm số lượng đơn PHÉP NĂM đang ở trạng thái CHỜ DUYỆT (Chống lách luật tạo nhiều đơn ca lẻ)
    @Query("SELECT COUNT(c) FROM AttendanceConfirmation c WHERE c.employeeNo = :employeeNo " +
            "AND c.approvalStatus = 'PENDING' " +
            "AND c.confirmStatus = 'ANNUAL_LEAVE'")
    long countPendingAnnualLeaveRequests(@Param("employeeNo") String employeeNo);

    // =========================================================================
    // CÁC HÀM HỖ TRỢ / LOGIC CŨ GIỮ NGUYÊN
    // =========================================================================
    List<AttendanceConfirmation> findByAttendanceDateBetweenAndApprovalStatus(LocalDate start, LocalDate end, String approvalStatus);

    Optional<AttendanceConfirmation> findByEmployeeNoAndAttendanceDateAndIssueType(String employeeNo, LocalDate attendanceDate, String issueType);

    List<AttendanceConfirmation> findByApprovalStatus(String status);
    @Query("SELECT ac FROM AttendanceConfirmation ac JOIN FETCH ac.user WHERE ac.approvalStatus = 'PENDING'")
    List<AttendanceConfirmation> findPendingRequestsWithUser();
    List<AttendanceConfirmation> findByEmployeeNoAndApprovalStatus(String employeeNo, String approvalStatus);
    List<AttendanceConfirmation> findByEmployeeNoAndAttendanceDateBetweenAndApprovalStatus(
            String employeeNo, LocalDate from, LocalDate to, String approvalStatus);
    List<AttendanceConfirmation> findByApprovedByAndApprovalStatus(String approvedBy, String approvalStatus);
}