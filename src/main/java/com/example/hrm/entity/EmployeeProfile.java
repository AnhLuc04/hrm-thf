package com.example.hrm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * HỒ SƠ NHÂN VIÊN (1 người 1 bản ghi, nối theo users.id). Bảng tự tạo nhờ ddl-auto=update.
 * Thông tin tài khoản (họ tên, mã NV, phòng ban, chức vụ quản lý, đơn vị) vẫn nằm ở bảng users như cũ.
 */
@Entity
@Table(name = "employee_profile")
@Getter
@Setter
public class EmployeeProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    // ---------------- thông tin cá nhân
    @Column(length = 10) private String gender;
    private LocalDate birthDate;
    @Column(length = 150) private String birthPlace;
    @Column(length = 50) private String ethnicity;
    @Column(length = 30) private String maritalStatus;
    @Column(length = 20) private String phone;
    @Column(length = 150) private String personalEmail;
    @Column(length = 300) private String permanentAddress;
    @Column(length = 300) private String currentAddress;

    // ---------------- giấy tờ (nhạy cảm: chỉ HCNS + chính chủ xem)
    @Column(length = 20) private String idNumber;
    private LocalDate idIssueDate;
    @Column(length = 150) private String idIssuePlace;
    @Column(length = 20) private String taxCode;
    @Column(length = 20) private String socialInsuranceNo;
    @Column(length = 30) private String bankAccount;
    @Column(length = 150) private String bankName;

    // ---------------- liên hệ khẩn cấp
    @Column(length = 150) private String emergencyName;
    @Column(length = 50) private String emergencyRelation;
    @Column(length = 20) private String emergencyPhone;

    // ---------------- công việc & hợp đồng
    @Column(length = 150) private String jobTitle;
    private LocalDate startDate;
    @Column(length = 50) private String contractType;
    @Column(length = 50) private String contractNo;
    private LocalDate contractStart;
    private LocalDate contractEnd;
    private LocalDate leaveDate;

    // ---------------- học vấn & CV
    @Column(length = 50) private String educationLevel;
    @Column(length = 150) private String major;
    @Column(length = 200) private String school;
    private Integer graduationYear;
    @Column(columnDefinition = "TEXT") private String summary;          // giới thiệu ngắn / mục tiêu
    @Column(columnDefinition = "TEXT") private String experience;       // kinh nghiệm làm việc
    @Column(columnDefinition = "TEXT") private String educationHistory; // quá trình học tập, đào tạo
    @Column(columnDefinition = "TEXT") private String skills;           // kỹ năng
    @Column(columnDefinition = "TEXT") private String certificates;     // chứng chỉ, bằng lái
    @Column(columnDefinition = "TEXT") private String note;             // ghi chú của HCNS

    @Column(length = 150) private String updatedBy;
    private LocalDateTime updatedAt;
}
