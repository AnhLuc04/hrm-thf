package com.example.hrm.entity;


import jakarta.persistence.*;
import lombok.*;


@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name="users")
public class User {


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    private String username;


    private String password;


    private String fullName;


    @Column(unique = true)
    private String employeeNo;



    // THÊM MỚI - MÃ DÙNG CHO IMPORT LƯƠNG
    @Column(name="salary_code", unique = true)
    private String salaryCode;



    private String attendanceCode;


    private String department;


    private String role;


    @Builder.Default
    private Boolean departmentManager=false;


    @Builder.Default
    @Column(name="employee_type")
    private String employeeType="CHINH_THUC";


    private Double salary;



    /**
     * ĐƠN VỊ (công ty) của nhân viên. Mỗi người chỉ thuộc 1 đơn vị.
     * Nhân viên cũ được tự gán vào "đơn vị gốc" khi khởi động (UnitBootstrap).
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "unit_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private CompanyUnit unit;


    @Transient
    private double annualLeaveDays;


}