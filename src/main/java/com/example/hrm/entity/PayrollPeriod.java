package com.example.hrm.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Kỳ lương (tháng). LOCKED = đã chốt sổ: không sửa khoản tháng, không lưu / tính lại phiếu lương của tháng. */
@Entity
@Table(name = "payroll_period",
        uniqueConstraints = @UniqueConstraint(name = "uk_payroll_period_unit", columnNames = {"nam", "thang", "unit_id"}))
public class PayrollPeriod {

    public static final String OPEN = "OPEN";
    public static final String LOCKED = "LOCKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nam", nullable = false)
    private Integer year;

    @Column(name = "thang", nullable = false)
    private Integer month;

    @Column(name = "trang_thai", nullable = false, length = 20)
    private String status = OPEN;

    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "ghi_chu", length = 500)
    private String note;

    /** ĐƠN VỊ chốt kỳ (mỗi đơn vị chốt riêng). null = bản ghi cũ = đơn vị gốc. */
    @Column(name = "unit_id")
    private Long unitId;

    public Long getUnitId() {
        return unitId;
    }

    public void setUnitId(Long unitId) {
        this.unitId = unitId;
    }

    public boolean isLocked() {
        return LOCKED.equals(status);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public Integer getMonth() {
        return month;
    }

    public void setMonth(Integer month) {
        this.month = month;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public LocalDateTime getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(LocalDateTime lockedAt) {
        this.lockedAt = lockedAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}