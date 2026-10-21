package com.example.hrm.entity;


import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;



@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "payroll")
public class Payroll {



    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;



    /*
       LIÊN KẾT NHÂN VIÊN
    */

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private User employee;




    private Integer month;


    private Integer year;


    // CV / chức vụ tại thời điểm chấm lương (snapshot, có thể khác hiện tại của User)
    private String position;



    // =====================================================
    // PHỤ CẤP / THU NHẬP THÊM
    // =====================================================

    @Column(name = "he_so_trach_nhiem")
    private Double heSoTrachNhiem;

    @Column(name = "trach_nhiem_duoc_nhan")
    private Double trachNhiemDuocNhan;

    @Column(name = "phu_cap_level")
    private Double phuCapLevel;

    @Column(name = "phu_cap_tham_nien")
    private Double phuCapThamNien;

    @Column(name = "phu_cap_kiem_nhiem")
    private Double phuCapKiemNhiem;

    @Column(name = "tham_nien_5_nam")
    private Double thamNien5Nam;

    @Column(name = "thuong_dich_vu")
    private Double thuongDichVu;

    @Column(name = "thuong_chinh_sach")
    private Double thuongChinhSach;

    @Column(name = "thuong_tu_van_ban_hang")
    private Double thuongTuVanBanHang;

    @Column(name = "ban_lanh_dao")
    private Double banLanhDao;



    // Tổng cộng thu nhập (trước các khoản giảm trừ)
    @Column(name = "net_salary")
    private Double netSalary;



    // =====================================================
    // CÁC KHOẢN GIẢM TRỪ LƯƠNG
    // =====================================================

    @Column(name = "ky_quy")
    private Double kyQuy;

    @Column(name = "bhxh")
    private Double bhxh;

    @Column(name = "tam_ung_luong")
    private Double tamUngLuong;

    @Column(name = "cong_doan")
    private Double congDoan;

    @Column(name = "thue_tncn")
    private Double thueTNCN;

    @Column(name = "rui_ro")
    private Double ruiRo;



    // =====================================================
    // THỰC NHẬN / ĐỐI CHIẾU
    // =====================================================

    @Column(name = "thuc_nhan")
    private Double thucNhan;

    @Column(name = "luong_cb_tham_nien_phu_cap")
    private Double luongCoBanThamNienPhuCap;

    @Column(name = "doi_chieu_hanh_chinh")
    private Double doiChieuHanhChinh;

    @Column(name = "chenh_lech")
    private Double chenhLech;

    @Column(name = "ghi_chu", length = 1000)
    private String ghiChu;



    // =====================================================
    // GIẢM TRỪ GIA CẢNH
    // =====================================================

    @Column(name = "giam_tru_ban_than")
    private Double giamTruBanThan;

    @Column(name = "giam_tru_gia_canh")
    private Double giamTruGiaCanh;

    @Column(name = "sau_giam_tru")
    private Double sauGiamTru;

    @Column(name="cong_chuan")
    private Double congChuan;


    @Column(name="cong_hl")
    private Double congHL;


    @Column(name="luong_ngay_cong")
    private Double luongNgayCong;


    @Column(name="luong_them_gio")
    private Double luongThemGio;

    @Column(name = "so_du_luong_tvbh")
    private Double soDuLuongTVBH;
    @Column(name="phong_chong_thien_tai")
    private Double phongChongThienTai;

    private LocalDate importDate;

// =====================================================
// THÔNG TIN CÔNG
// =====================================================

    @Column(name = "so_ngay_trong_thang")
    private Double soNgayTrongThang;


    @Column(name = "ngay_le_tet")
    private Double ngayLeTet;


    @Column(name = "chu_nhat")
    private Double chuNhat;


    @Column(name = "du_cong")
    private Double duCong;



    @Column(name = "so_ngay_thuc_lam")
    private Double soNgayThucLam;


    // Công thêm giờ
    @Column(name = "cong_them_gio")
    private Double congThemGio;


    // Hệ số thêm giờ
    @Column(name = "he_so_them_gio")
    private Double heSoThemGio;



// =====================================================
// LƯƠNG CƠ BẢN
// =====================================================


    @Column(name = "luong_co_ban")
    private Double luongCoBan;



    @Column(name = "luong_theo_ngay")
    private Double luongTheoNgay;



    @Column(name = "thanh_tien_them_gio")
    private Double thanhTienThemGio;




// =====================================================
// PHỤ CẤP TRÁCH NHIỆM
// =====================================================


    @Column(name = "pc_trach_nhiem")
    private Double pcTrachNhiem;




// =====================================================
// GIẢM TRỪ BỔ SUNG
// =====================================================


    @Column(name = "phat_vi_pham")
    private Double phatViPham;

}