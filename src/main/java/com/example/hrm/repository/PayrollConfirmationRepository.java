package com.example.hrm.repository;

import com.example.hrm.entity.AttendanceConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/** Yêu cầu xác nhận công dùng riêng cho tính lương (không sửa repository hiện có). */
public interface PayrollConfirmationRepository extends JpaRepository<AttendanceConfirmation, Long> {

    List<AttendanceConfirmation> findByApprovalStatusAndAttendanceDateBetween(String approvalStatus, LocalDate from, LocalDate to);

    List<AttendanceConfirmation> findByEmployeeNoAndApprovalStatusAndAttendanceDateBetween(
            String employeeNo, String approvalStatus, LocalDate from, LocalDate to);
}