package com.example.hrm.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 1 khoản phát sinh của 1 nhân viên trong 1 tháng (phạt, tạm ứng, rủi ro, thưởng...). Đơn vị: ĐỒNG.
 * Duy nhất theo (nhân viên, năm, tháng, loại).
 */
@Entity
@Table(name = "payroll_monthly_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_payroll_monthly_item",
                columnNames = {"user_id", "nam", "thang", "loai"}),
        indexes = @Index(name = "ix_payroll_monthly_item_ky", columnList = "nam, thang"))
public class PayrollMonthlyItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "nam", nullable = false)
    private Integer year;

    @Column(name = "thang", nullable = false)
    private Integer month;

    /** varchar (không dùng kiểu ENUM của MySQL) để sau này thêm loại khoản không phải sửa bảng */
    @Enumerated(EnumType.STRING)
    @Column(name = "loai", nullable = false, length = 40, columnDefinition = "varchar(40)")
    private MonthlyItemType type;

    /** Số tiền (đồng) */
    @Column(name = "so_tien")
    private Double amount;

    @Column(name = "ghi_chu", length = 500)
    private String note;

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

    public MonthlyItemType getType() {
        return type;
    }

    public void setType(MonthlyItemType type) {
        this.type = type;
    }

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
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