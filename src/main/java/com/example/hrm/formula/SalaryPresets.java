package com.example.hrm.formula;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BỘ MẪU CÔNG THỨC LƯƠNG SOẠN SẴN (Kế toán bấm 1 nút ở trang Mẫu công thức là có, rồi sửa tiếp nếu cần).
 *
 * Mỗi mẫu = công thức gợi ý chuẩn của SalaryVariables.ITEMS (lương theo công, phụ cấp theo tỷ lệ công,
 * BHXH, công đoàn, thuế TNCN 5 bậc, thực nhận) + phần khác riêng của từng nhóm.
 * Tham số có ghi chú sau dấu # thành ô "tỉ lệ / hệ số" nhập riêng được cho từng nhân viên.
 * Tiền tính theo ĐỒNG; phiếu lưu theo nghìn đồng như cũ.
 */
public final class SalaryPresets {

    private SalaryPresets() {
    }

    public record Preset(String key, String name, String departmentHint, String note,
                         String thamSo, String bienTrungGian, Map<String, String> overrides) {

        /** Đủ mọi hạng mục: công thức chuẩn, hạng mục nào mẫu khai riêng thì lấy của mẫu */
        public Map<String, String> items() {
            Map<String, String> m = new LinkedHashMap<>();
            for (SalaryVariables.Item it : SalaryVariables.ITEMS) {
                m.put(it.code(), overrides.getOrDefault(it.code(), it.defaultFormula()));
            }
            return m;
        }
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    public static final List<Preset> ALL = List.of(

            new Preset("VP", "Chuẩn - Văn phòng / Gián tiếp", "HCNS",
                    "Lương theo ngày công thực tế. Phụ cấp, trách nhiệm theo tỷ lệ công. "
                            + "Thưởng chính sách / khác lấy đúng số nhập ở Khoản theo tháng (hoặc ERP). "
                            + "Thêm giờ: chỉ trả giờ đã nhập ở Khoản theo tháng.",
                    """
                    heSoOtThuong = 1.5      # HS thêm giờ ngày thường
                    heSoOtChuNhat = 2       # HS thêm giờ ngày nghỉ
                    """,
                    "",
                    map()),

            new Preset("KD", "Chuẩn - Kinh doanh (tư vấn bán hàng)", "Kinh Doanh",
                    "Lương theo ngày công + thưởng tư vấn bán hàng (xe, bảo hiểm, phụ kiện) nhân hệ số riêng từng người "
                            + "(mặc định 100%). Thưởng nhập ở Khoản theo tháng hoặc lấy từ ERP.",
                    """
                    heSoThuongBanHang = 1   # HS thưởng bán hàng
                    heSoOtThuong = 1.5      # HS thêm giờ ngày thường
                    heSoOtChuNhat = 2       # HS thêm giờ ngày nghỉ
                    """,
                    "",
                    map("thuongTuVanBanHang", "nhapThuongTuVanBanHang * heSoThuongBanHang")),

            new Preset("DV", "Chuẩn - Dịch vụ / Xưởng", "Dịch Vụ",
                    "Lương theo ngày công. Thêm giờ TỰ TÍNH theo giờ vào / ra máy chấm công khi chưa nhập tay "
                            + "(ngày thường × 1,5; ngày nghỉ × 2; ngày lễ × hệ số lễ). Thưởng dịch vụ nhân hệ số riêng (mặc định 100%).",
                    """
                    tuDongTinhTangCa = 1    # Tự tính thêm giờ theo máy (1 = có)
                    heSoOtThuong = 1.5      # HS thêm giờ ngày thường
                    heSoOtChuNhat = 2       # HS thêm giờ ngày nghỉ
                    heSoThuongDichVu = 1    # HS thưởng dịch vụ
                    """,
                    "",
                    map("thuongDichVu", "nhapThuongDichVu * heSoThuongDichVu")),

            new Preset("KHOAN", "Chuẩn - Lương khoán đủ công", null,
                    "Đi đủ công chuẩn thì nhận ĐỦ lương cơ bản (không phụ thuộc tháng 24 hay 27 công); "
                            + "thiếu công thì trừ theo ngày. Phụ cấp nhận đủ khi đủ công.",
                    """
                    heSoOtThuong = 1.5      # HS thêm giờ ngày thường
                    heSoOtChuNhat = 2       # HS thêm giờ ngày nghỉ
                    """,
                    "",
                    map("luongNgayCong", "IF(congHL >= congChuan, luongCoBan, luongNgay * congHL)")),

            new Preset("TV", "Chuẩn - Thử việc 85%", null,
                    "Nhận 85% lương theo ngày công (sửa tỉ lệ riêng từng người ở Cấu hình lương NV). "
                            + "Thử việc không đóng BHXH, công đoàn; thuế TNCN vẫn tính theo biểu lũy tiến như mẫu chuẩn "
                            + "(nếu công ty khấu trừ 10% thì sửa mục Thuế TNCN thành tongThuNhap * 10%).",
                    """
                    tyLeThuViec = 0.85      # Tỉ lệ lương thử việc
                    """,
                    "",
                    map("luongNgayCong", "luongNgay * congHL * tyLeThuViec",
                            "bhxh", "0",
                            "congDoan", "0"))
    );

    public static Preset byKey(String key) {
        return ALL.stream().filter(p -> p.key().equalsIgnoreCase(key)).findFirst().orElse(null);
    }
}
