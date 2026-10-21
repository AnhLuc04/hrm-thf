package com.example.hrm.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Nhật ký thao tác trên module lương: ai, lúc nào, làm gì, với đối tượng nào. */
@Entity
@Table(name = "payroll_audit_log",
        indexes = {@Index(name = "ix_payroll_audit_time", columnList = "thoi_gian"),
                @Index(name = "ix_payroll_audit_target", columnList = "doi_tuong")})
public class PayrollAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "thoi_gian", nullable = false)
    private LocalDateTime time;

    @Column(name = "nguoi_thuc_hien", length = 100)
    private String username;

    /** VD CONFIG_SAVE, FORMULA_SAVE, MONTHLY_SAVE, PERIOD_LOCK, PAYROLL_SAVE */
    @Column(name = "hanh_dong", length = 40)
    private String action;

    /** VD "NV 00000269 - Lê Hữu Lực", "Mẫu Văn phòng", "Kỳ 9/2026" */
    @Column(name = "doi_tuong", length = 200)
    private String target;

    @Column(name = "chi_tiet", columnDefinition = "TEXT")
    private String detail;

    /** ĐƠN VỊ của thao tác (null = bản ghi cũ = đơn vị gốc, hoặc quản trị hệ thống xem "Tất cả") */
    @Column(name = "unit_id")
    private Long unitId;

    public Long getUnitId() {
        return unitId;
    }

    public void setUnitId(Long unitId) {
        this.unitId = unitId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getTime() {
        return time;
    }

    public void setTime(LocalDateTime time) {
        this.time = time;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}