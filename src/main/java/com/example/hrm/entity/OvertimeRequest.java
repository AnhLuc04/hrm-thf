package com.example.hrm.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
public class OvertimeRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String employeeNo;
    private LocalDate requestDate; // Ngày thực hiện tăng ca
    private Double hours;          // Số giờ (ví dụ: 2.5)
    private String reason;         // Lý do tăng ca
    private String status;         // PENDING, APPROVED, REJECTED

    // Thêm các thông tin người duyệt
    private String approvedBy;
    private LocalDateTime approvedAt;
}