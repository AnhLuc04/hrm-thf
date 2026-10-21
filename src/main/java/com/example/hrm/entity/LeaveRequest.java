package com.example.hrm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String employeeNo;

    private LocalDate fromDate;
    private LocalDate toDate;

    // FULL_DAY / MORNING / AFTERNOON
    private String sessionType;

    // ANNUAL / UNPAID / SICK
    private String leaveType;

    private double totalDays;

    // PENDING / APPROVED / REJECTED
    private String status;

    private String reason;

    private LocalDateTime createdAt;
}