package com.example.hrm.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Đánh dấu phiếu lương do hệ thống TỰ TÍNH từ bảng công (phiếu tạm tính).
 *
 *  - Chỉ phiếu có dấu này mới được job hằng ngày tính lại.
 *  - Phiếu Kế toán import từ Excel KHÔNG có dấu -> không bao giờ bị tự động ghi đè.
 *  - Khi Kế toán import Excel cho tháng đó, phiếu tạm tính được xoá để import đè (PayrollAutoService.releaseForImport).
 *
 * Bảng riêng để KHÔNG phải sửa entity Payroll đang có.
 */
@Entity
@Table(name = "payroll_auto_mark")
public class PayrollAutoMark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payroll_id", nullable = false, unique = true)
    private Long payrollId;

    @Column(name = "user_id")
    private Long userId;

    private Integer month;

    private Integer year;

    /** Bảng công đã tính đến ngày này (tháng đang chạy = hôm nay) */
    @Column(name = "calc_up_to")
    private LocalDate calcUpTo;

    @Column(name = "calculated_at")
    private LocalDateTime calculatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPayrollId() {
        return payrollId;
    }

    public void setPayrollId(Long payrollId) {
        this.payrollId = payrollId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getMonth() {
        return month;
    }

    public void setMonth(Integer month) {
        this.month = month;
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public LocalDate getCalcUpTo() {
        return calcUpTo;
    }

    public void setCalcUpTo(LocalDate calcUpTo) {
        this.calcUpTo = calcUpTo;
    }

    public LocalDateTime getCalculatedAt() {
        return calculatedAt;
    }

    public void setCalculatedAt(LocalDateTime calculatedAt) {
        this.calculatedAt = calculatedAt;
    }

    /** true nếu là phiếu của tháng chưa hết (số liệu còn thay đổi) */
    public boolean isProvisional() {
        return calcUpTo != null && month != null && year != null
                && calcUpTo.isBefore(java.time.YearMonth.of(year, month).atEndOfMonth());
    }
}