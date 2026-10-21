package com.example.hrm.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bộ công thức lương cho 1 cấp:
 *   - DEFAULT    : dùng chung cho toàn công ty (tối đa 1 bản ghi)
 *   - DEPARTMENT : cho 1 phòng ban (so khớp với users.department, không phân biệt hoa thường)
 *   - EMPLOYEE   : riêng cho 1 nhân viên
 *
 * Mỗi hạng mục lương (items) để trống = kế thừa từ cấp trên.
 */
@Entity
@Table(name = "salary_formula")
public class SalaryFormula {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FormulaScope scope;

    /** Tên phòng ban (chỉ dùng khi scope = DEPARTMENT) */
    @Column(length = 150)
    private String department;

    /** Nhân viên (chỉ dùng khi scope = EMPLOYEE) */
    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @Column(length = 200)
    private String name;

    /** Dòng "ten = giá trị": tham số, tính TRƯỚC biến hệ thống */
    @Column(name = "tham_so", columnDefinition = "TEXT")
    private String thamSo;

    /** Dòng "ten = biểu thức": tính SAU biến hệ thống, TRƯỚC các hạng mục lương */
    @Column(name = "bien_trung_gian", columnDefinition = "TEXT")
    private String bienTrungGian;

    /** mã hạng mục (SalaryVariables.ITEMS) -> biểu thức */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "salary_formula_item", joinColumns = @JoinColumn(name = "formula_id"))
    @MapKeyColumn(name = "item_code", length = 50)
    @Column(name = "expression", length = 2000)
    private Map<String, String> items = new LinkedHashMap<>();

    private Boolean active = true;

    @Column(name = "ghi_chu", columnDefinition = "TEXT")
    private String ghiChu;

    private LocalDateTime updatedAt;

    // ================= helper =================

    /** Biểu thức của 1 hạng mục, null nếu để trống (kế thừa) */
    public String expression(String itemCode) {
        String e = items == null ? null : items.get(itemCode);
        return e == null || e.isBlank() ? null : e.trim();
    }

    public boolean activeOrDefault() {
        return active == null || active;
    }

    public long overriddenItemCount() {
        return items == null ? 0 : items.values().stream().filter(v -> v != null && !v.isBlank()).count();
    }

    // ================= getter / setter =================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public FormulaScope getScope() {
        return scope;
    }

    public void setScope(FormulaScope scope) {
        this.scope = scope;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    /**
     * ĐƠN VỊ sở hữu mẫu. null = MẪU DÙNG CHUNG cho mọi đơn vị (các mẫu có từ trước): đơn vị nào cũng chọn được,
     * chỉ quản trị hệ thống sửa / xoá. Kế toán đơn vị bấm "Nhân bản" để có bản riêng sửa được.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "unit_id")
    private CompanyUnit unit;

    public CompanyUnit getUnit() {
        return unit;
    }

    public void setUnit(CompanyUnit unit) {
        this.unit = unit;
    }

    public boolean isShared() {
        return unit == null;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getThamSo() {
        return thamSo;
    }

    public void setThamSo(String thamSo) {
        this.thamSo = thamSo;
    }

    public String getBienTrungGian() {
        return bienTrungGian;
    }

    public void setBienTrungGian(String bienTrungGian) {
        this.bienTrungGian = bienTrungGian;
    }

    public Map<String, String> getItems() {
        return items;
    }

    public void setItems(Map<String, String> items) {
        this.items = items;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String getGhiChu() {
        return ghiChu;
    }

    public void setGhiChu(String ghiChu) {
        this.ghiChu = ghiChu;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}