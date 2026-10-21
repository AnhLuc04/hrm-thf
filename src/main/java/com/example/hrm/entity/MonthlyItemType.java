package com.example.hrm.entity;

import com.example.hrm.formula.Vars;

/**
 * Các khoản PHÁT SINH THEO THÁNG (thay đổi từng tháng) - nhập ở trang "Khoản theo tháng".
 *
 * variable : tên biến dùng trong công thức lương
 * erp      : true = loại thưởng có thể lấy tự động từ SQL Server (Cyber ERP) khi bật kết nối
 * group    : nhóm trên thẻ lương (II = thưởng, III = giảm trừ)
 */
public enum MonthlyItemType {

    THUONG_DICH_VU("thuongDichVu", "Thưởng dịch vụ", "II", true),
    THUONG_CHINH_SACH("thuongChinhSach", "Thưởng chính sách, gián tiếp, kế toán", "II", true),
    THUONG_TVBH("thuongTuVanBanHang", "Thưởng tư vấn bán hàng (xe, bảo hiểm, PK)", "II", true),
    THUONG_KHAC("banLanhDao", "Thưởng khác (nếu có)", "II", true),
    PHAT_VI_PHAM("phatViPham", "Phạt vi phạm đồng phục, chấm công, quy trình DV", "III", false),
    TAM_UNG("tamUng", "Tạm ứng lương, truy thu công nợ", "III", false),
    PHAT_KD_DV("phongChongThienTai", "Phạt KD, DV vi phạm. Tạm thu hàng tồn >90 ngày", "III", false),
    RUI_RO("ruiRo", "Rủi ro", "III", false);

    private final String variable;
    private final String label;
    private final String group;
    private final boolean erp;

    MonthlyItemType(String variable, String label, String group, boolean erp) {
        this.variable = variable;
        this.label = label;
        this.group = group;
        this.erp = erp;
    }

    public String getVariable() {
        return variable;
    }

    public String getLabel() {
        return label;
    }

    public String getGroup() {
        return group;
    }

    public boolean isErp() {
        return erp;
    }

    /** Tên biến "số nhập" trong công thức (thưởng: nhapThuong...; khoản trừ: như variable) */
    public String getInputVariable() {
        return switch (this) {
            case THUONG_DICH_VU -> "nhapThuongDichVu";
            case THUONG_CHINH_SACH -> "nhapThuongChinhSach";
            case THUONG_TVBH -> "nhapThuongTuVanBanHang";
            case THUONG_KHAC -> "nhapThuongKhac";
            default -> variable;
        };
    }

    /** Loại thưởng = hạng mục lương trong mẫu công thức (tính lại được theo tỉ lệ) */
    public boolean isBonus() {
        return "II".equals(group);
    }

    public String getCode() {
        return name();
    }

    public static MonthlyItemType ofVariable(String var) {
        for (MonthlyItemType t : values()) {
            if (Vars.key(t.variable).equals(Vars.key(var))) {
                return t;
            }
        }
        return null;
    }
}