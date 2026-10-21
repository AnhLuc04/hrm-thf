package com.example.hrm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * PHÒNG BAN CỦA ĐƠN VỊ + NGƯỜI DUYỆT (HCNS cấu hình ở trang Chức vụ & phòng ban).
 * approverNo trống = theo quy tắc cũ (trưởng phòng; Dịch Vụ -> GĐ dịch vụ; Kế Toán -> Kế toán trưởng; còn lại Giám đốc).
 * Tên phòng so khớp với users.department (không phân biệt hoa thường, bỏ khoảng trắng thừa).
 */
@Entity
@Table(name = "unit_department",
        uniqueConstraints = @UniqueConstraint(name = "uk_unit_department", columnNames = {"unit_id", "name"}))
public class UnitDepartment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** id đơn vị (đơn vị gốc với dữ liệu cũ unit_id null: lưu id đơn vị gốc) */
    @Column(name = "unit_id", nullable = false)
    private Long unitId;

    @Column(nullable = false, length = 100)
    private String name;

    /** Mã NV người duyệt đơn (giải trình công) của phòng; trống = quy tắc cũ */
    @Column(name = "approver_no", length = 50)
    private String approverNo;

    @Column(name = "updated_by", length = 150)
    private String updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUnitId() { return unitId; }
    public void setUnitId(Long unitId) { this.unitId = unitId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getApproverNo() { return approverNo; }
    public void setApproverNo(String approverNo) { this.approverNo = approverNo; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
