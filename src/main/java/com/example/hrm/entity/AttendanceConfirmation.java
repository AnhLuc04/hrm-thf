package com.example.hrm.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendanceConfirmation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String employeeNo;

    private LocalDate attendanceDate; // Lưu ý: Đồng bộ trường này tên chuẩn với Service

    private String issueType;

    private String confirmStatus; // PRESENT, HALF_DAY, ANNUAL_LEAVE, UNPAID_LEAVE

    private String sessionType;

    private String note;

    private String approvalStatus; // PENDING, APPROVED, REJECTED

    private String approvedBy;

    private LocalDateTime approvedAt;
    private String managerComment;
    private LocalDateTime createdAt;
    // BỔ SUNG: Liên kết sang User để lấy Họ Tên chuẩn xác từ mã nhân viên
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employeeNo", referencedColumnName = "employeeNo", insertable = false, updatable = false)
    private User user;

}