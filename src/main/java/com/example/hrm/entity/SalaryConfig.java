package com.example.hrm.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Số liệu lương cố định của từng nhân viên (đầu vào cho công thức).
 * Nếu nhân viên chưa có bản ghi này: lương cơ bản = users.salary, không có phụ cấp.
 */
@Entity
@Table(name = "salary_config")
public class SalaryConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /** Lương cơ bản / tháng (đủ công). Null -> users.salary */
    private Double luongCoBan;

    /** Mức lương đóng BHXH. Null -> lương cơ bản */
    private Double luongDongBhxh;

    private Double mucTrachNhiem;

    private Double heSoTrachNhiem;

    private Double phuCapLevel;

    private Double phuCapThamNien;

    private Double phuCapKiemNhiem;

    private Double thamNien5Nam;

    private Double kyQuy;

    private Integer soNguoiPhuThuoc = 0;

    private Boolean thamGiaBhxh = true;

    private Boolean thamGiaCongDoan = true;


    // ===== Khoản theo thẻ lương (đơn vị ĐỒNG) =====

    /** T.Nhiệm được nhận cố định / tháng. Null = tự tính (mức × HS × tỷ lệ công) */
    @Column(name = "trach_nhiem_co_dinh")
    private Double trachNhiemCoDinh;

    /** BHXH trừ cố định / tháng. Null = tự tính (lương đóng BHXH × tỷ lệ) */
    @Column(name = "bhxh_co_dinh")
    private Double bhxhCoDinh;

    /** Công đoàn cố định / tháng. Null = tự tính (% tổng thu nhập) */
    @Column(name = "cong_doan_co_dinh")
    private Double congDoanCoDinh;

    /** Thuế TNCN cố định / tháng. Null = tự tính (biểu lũy tiến) */
    @Column(name = "thue_tncn_co_dinh")
    private Double thueTncnCoDinh;

    /** Tạm ứng lương, truy thu công nợ / tháng (dùng khi phiếu lương tháng đó chưa nhập) */
    @Column(name = "tam_ung")
    private Double tamUng;

    /** Phạt vi phạm đồng phục, chấm công, quy trình DV / tháng */
    @Column(name = "phat_vi_pham")
    private Double phatViPham;

    /** Phạt KD, DV vi phạm. Tạm thu hàng tồn > 90 ngày / tháng */
    @Column(name = "phat_kd_dv")
    private Double phatKdDv;

    /** Rủi ro / tháng */
    @Column(name = "rui_ro")
    private Double ruiRo;

    /** Thưởng khác (nếu có) / tháng */
    @Column(name = "thuong_khac")
    private Double thuongKhac;

    /** Mẫu công thức lương áp dụng cho nhân viên. Null = chưa chọn -> không tính lương */
    @ManyToOne
    @JoinColumn(name = "formula_id")
    private SalaryFormula formula;

    /**
     * Tỉ lệ riêng của nhân viên cho các tham số mà mẫu công thức khai báo.
     * tên tham số -> giá trị như người dùng nhập (VD "1.5%", "2", "0.85"); không có = dùng mặc định của mẫu.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "salary_config_ty_le", joinColumns = @JoinColumn(name = "config_id"))
    @MapKeyColumn(name = "ten", length = 100)
    @Column(name = "gia_tri", length = 100)
    private Map<String, String> tyLe = new LinkedHashMap<>();

    /** Tham số riêng nâng cao, mỗi dòng "ten = giá trị" (áp dụng sau tỉ lệ riêng) */
    @Column(name = "tham_so_rieng", columnDefinition = "TEXT")
    private String thamSoRieng;

    @Column(name = "ghi_chu", columnDefinition = "TEXT")
    private String ghiChu;

    private LocalDateTime updatedAt;

    // ================= getter / setter =================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Double getLuongCoBan() {
        return luongCoBan;
    }

    public void setLuongCoBan(Double luongCoBan) {
        this.luongCoBan = luongCoBan;
    }

    public Double getLuongDongBhxh() {
        return luongDongBhxh;
    }

    public void setLuongDongBhxh(Double luongDongBhxh) {
        this.luongDongBhxh = luongDongBhxh;
    }

    public Double getMucTrachNhiem() {
        return mucTrachNhiem;
    }

    public void setMucTrachNhiem(Double mucTrachNhiem) {
        this.mucTrachNhiem = mucTrachNhiem;
    }

    public Double getHeSoTrachNhiem() {
        return heSoTrachNhiem;
    }

    public void setHeSoTrachNhiem(Double heSoTrachNhiem) {
        this.heSoTrachNhiem = heSoTrachNhiem;
    }

    public Double getPhuCapLevel() {
        return phuCapLevel;
    }

    public void setPhuCapLevel(Double phuCapLevel) {
        this.phuCapLevel = phuCapLevel;
    }

    public Double getPhuCapThamNien() {
        return phuCapThamNien;
    }

    public void setPhuCapThamNien(Double phuCapThamNien) {
        this.phuCapThamNien = phuCapThamNien;
    }

    public Double getPhuCapKiemNhiem() {
        return phuCapKiemNhiem;
    }

    public void setPhuCapKiemNhiem(Double phuCapKiemNhiem) {
        this.phuCapKiemNhiem = phuCapKiemNhiem;
    }

    public Double getThamNien5Nam() {
        return thamNien5Nam;
    }

    public void setThamNien5Nam(Double thamNien5Nam) {
        this.thamNien5Nam = thamNien5Nam;
    }

    public Double getKyQuy() {
        return kyQuy;
    }

    public void setKyQuy(Double kyQuy) {
        this.kyQuy = kyQuy;
    }

    public Integer getSoNguoiPhuThuoc() {
        return soNguoiPhuThuoc;
    }

    public void setSoNguoiPhuThuoc(Integer soNguoiPhuThuoc) {
        this.soNguoiPhuThuoc = soNguoiPhuThuoc;
    }

    public Boolean getThamGiaBhxh() {
        return thamGiaBhxh;
    }

    public void setThamGiaBhxh(Boolean thamGiaBhxh) {
        this.thamGiaBhxh = thamGiaBhxh;
    }

    public Boolean getThamGiaCongDoan() {
        return thamGiaCongDoan;
    }

    public void setThamGiaCongDoan(Boolean thamGiaCongDoan) {
        this.thamGiaCongDoan = thamGiaCongDoan;
    }

    public Double getTrachNhiemCoDinh() {
        return trachNhiemCoDinh;
    }

    public void setTrachNhiemCoDinh(Double trachNhiemCoDinh) {
        this.trachNhiemCoDinh = trachNhiemCoDinh;
    }

    public Double getBhxhCoDinh() {
        return bhxhCoDinh;
    }

    public void setBhxhCoDinh(Double bhxhCoDinh) {
        this.bhxhCoDinh = bhxhCoDinh;
    }

    public Double getCongDoanCoDinh() {
        return congDoanCoDinh;
    }

    public void setCongDoanCoDinh(Double congDoanCoDinh) {
        this.congDoanCoDinh = congDoanCoDinh;
    }

    public Double getThueTncnCoDinh() {
        return thueTncnCoDinh;
    }

    public void setThueTncnCoDinh(Double thueTncnCoDinh) {
        this.thueTncnCoDinh = thueTncnCoDinh;
    }

    public Double getTamUng() {
        return tamUng;
    }

    public void setTamUng(Double tamUng) {
        this.tamUng = tamUng;
    }

    public Double getPhatViPham() {
        return phatViPham;
    }

    public void setPhatViPham(Double phatViPham) {
        this.phatViPham = phatViPham;
    }

    public Double getPhatKdDv() {
        return phatKdDv;
    }

    public void setPhatKdDv(Double phatKdDv) {
        this.phatKdDv = phatKdDv;
    }

    public Double getRuiRo() {
        return ruiRo;
    }

    public void setRuiRo(Double ruiRo) {
        this.ruiRo = ruiRo;
    }

    public Double getThuongKhac() {
        return thuongKhac;
    }

    public void setThuongKhac(Double thuongKhac) {
        this.thuongKhac = thuongKhac;
    }

    public SalaryFormula getFormula() {
        return formula;
    }

    public void setFormula(SalaryFormula formula) {
        this.formula = formula;
    }

    public Map<String, String> getTyLe() {
        return tyLe;
    }

    public void setTyLe(Map<String, String> tyLe) {
        this.tyLe = tyLe;
    }

    public String getThamSoRieng() {
        return thamSoRieng;
    }

    public void setThamSoRieng(String thamSoRieng) {
        this.thamSoRieng = thamSoRieng;
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