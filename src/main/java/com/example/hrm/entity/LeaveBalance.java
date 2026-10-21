package com.example.hrm.entity;

import jakarta.persistence.*;
import lombok.*;
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaveBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String employeeNo;

    // Tổng phép được cấp hiện tại
    private double totalAnnualLeave;

    // Đã sử dụng
    private double usedAnnualLeave;

    // Còn lại
    private double remaining;

    // Định mức cộng phép mỗi tháng
    private double accrualPerMonth;

    // Tháng đã cộng gần nhất
    private String lastAccrualMonth;
}