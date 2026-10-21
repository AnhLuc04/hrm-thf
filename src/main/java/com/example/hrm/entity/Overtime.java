package com.example.hrm.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDate;

@Entity
public class Overtime {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String employeeNo;
    private LocalDate requestDate; // Ngày tăng ca
    private Double hours;          // Số giờ tăng ca (vd: 1.5)
    private String reason;
    private String approvalStatus; // PENDING, APPROVED, REJECTED

    // Getter và Setter
}