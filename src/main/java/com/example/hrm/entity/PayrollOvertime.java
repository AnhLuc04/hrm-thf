package com.example.hrm.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 1 dòng TĂNG CA nhập tay của 1 nhân viên trong 1 tháng: số giờ × hệ số (VD 4 giờ × 2, 5 giờ × 3).
 * Có dòng tăng ca trong tháng thì "Lương thêm giờ" = lương 1 giờ × Σ(giờ × hệ số), thay cho giờ tự tính theo chấm công.
 */
@Entity
@Table(name = "payroll_overtime",
        indexes = @Index(name = "ix_payroll_overtime_ky", columnList = "nam, thang, user_id"))
public class PayrollOvertime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "nam", nullable = false)
    private Integer year;

    @Column(name = "thang", nullable = false)
    private Integer month;

    /** Thứ tự dòng */
    @Column(name = "stt")
    private Integer seq;

    @Column(name = "so_gio", nullable = false)
    private Double hours;

    /** Hệ số: 1.5 = 150%, 2 = 200%, 3 = 300% */
    @Column(name = "he_so", nullable = false)
    private Double factor;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public Double getHours() {
        return hours;
    }

    public void setHours(Double hours) {
        this.hours = hours;
    }

    public Double getFactor() {
        return factor;
    }

    public void setFactor(Double factor) {
        this.factor = factor;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}