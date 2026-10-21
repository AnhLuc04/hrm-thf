package com.example.hrm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * MỞ LẠI HẠN XÁC NHẬN CÔNG cho 1 nhân viên (HCNS / quản trị / Giám đốc đơn vị cấp).
 * Các ngày lỗi từ fromDate đến toDate được gửi giải trình đến hết ngày openUntil,
 * dù đã quá hạn N ngày của đơn vị (VD nhân viên ốm, đi công tác dài ngày).
 */
@Entity
@Table(name = "attendance_confirm_extension",
       indexes = @Index(name = "idx_confirm_ext_emp", columnList = "employee_no, open_until"))
public class ConfirmExtension {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_no", nullable = false, length = 50)
    private String employeeNo;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    /** Được gửi đến hết ngày này */
    @Column(name = "open_until", nullable = false)
    private LocalDate openUntil;

    @Column(length = 300)
    private String reason;

    @Column(name = "granted_by", length = 100)
    private String grantedBy;

    @Column(name = "granted_by_name", length = 150)
    private String grantedByName;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public boolean covers(LocalDate date) {
        return date != null && !date.isBefore(fromDate) && !date.isAfter(toDate);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEmployeeNo() { return employeeNo; }
    public void setEmployeeNo(String employeeNo) { this.employeeNo = employeeNo; }
    public LocalDate getFromDate() { return fromDate; }
    public void setFromDate(LocalDate fromDate) { this.fromDate = fromDate; }
    public LocalDate getToDate() { return toDate; }
    public void setToDate(LocalDate toDate) { this.toDate = toDate; }
    public LocalDate getOpenUntil() { return openUntil; }
    public void setOpenUntil(LocalDate openUntil) { this.openUntil = openUntil; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getGrantedBy() { return grantedBy; }
    public void setGrantedBy(String grantedBy) { this.grantedBy = grantedBy; }
    public String getGrantedByName() { return grantedByName; }
    public void setGrantedByName(String grantedByName) { this.grantedByName = grantedByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
