package com.example.hrm.formula;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Danh mục cố định: tham số mặc định, biến hệ thống (đầu vào) và các hạng mục lương (đầu ra).
 *
 *  Thứ tự tính cho mỗi nhân viên:
 *    0. Nhân viên chưa có công thức ở cấp nào -> không tính lương.
 *    1. THAM SỐ         : BUILTIN_PARAMS  -> "Tham số" của công thức chung -> phòng ban -> nhân viên
 *                         -> "Tham số riêng" trong cấu hình lương của nhân viên
 *    2. BIẾN HỆ THỐNG   : INPUTS (lấy từ chấm công, cấu hình lương, phiếu lương nhập tay)
 *    3. BIẾN TRUNG GIAN : do HR tự đặt, gộp từ chung -> phòng ban -> nhân viên
 *    4. HẠNG MỤC LƯƠNG  : ITEMS theo đúng thứ tự dưới đây; mục sau dùng được kết quả mục trước
 */
public final class SalaryVariables {

    private SalaryVariables() {
    }

    public record Param(String name, double value, String label) {
    }

    public record Var(String name, String label, String group) {
    }

    /**
     * @param defaultFormula công thức MẪU GỢI Ý hiện trên form để HR bấm "Điền mẫu".
     *                       KHÔNG tự áp dụng khi tính lương: hạng mục nào chưa có công thức
     *                       ở cấp nào thì nhân viên đó không được tính.
     */
    public record Item(String code, String label, String defaultFormula, boolean optional) {

        /** Hạng mục bắt buộc */
        public Item(String code, String label, String defaultFormula) {
            this(code, label, defaultFormula, false);
        }
    }

    public record Func(String syntax, String description) {
    }

    // =====================================================================
    // 1. THAM SỐ MẶC ĐỊNH (ghi đè được ở mọi cấp)
    // =====================================================================

    public static final List<Param> BUILTIN_PARAMS = List.of(
            new Param("congChuanCoDinh", 0, "Công chuẩn cố định; 0 = tự tính (số ngày trong tháng trừ Chủ nhật)"),
            new Param("gioChuanNgay", 8, "Số giờ làm chuẩn / ngày"),
            new Param("gioNghiTrua", 1, "Giờ nghỉ trưa trừ ra khi ca dài hơn 5 tiếng"),
            new Param("otToiThieuPhut", 30, "Chỉ tính thêm giờ khi vượt ít nhất số phút này (làm tròn xuống theo bội số)"),
            new Param("heSoOtThuong", 1.5, "Hệ số thêm giờ ngày thường"),
            new Param("heSoOtChuNhat", 2, "Hệ số thêm giờ Chủ nhật"),
            new Param("tuDongTinhTangCa", 0, "0 = chỉ trả tăng ca nhập ở Khoản theo tháng (giờ x hệ số); 1 = NV chưa nhập thì tự tính theo giờ vào/ra"),
            new Param("tyLeBhxh", 0.105, "Tỷ lệ BHXH + BHYT + BHTN người lao động đóng"),
            new Param("soNgayNghiKhongDongBhxh", 14, "Nghỉ không lương từ số ngày này trở lên thì không đóng BHXH"),
            new Param("tyLeCongDoan", 0.01, "Tỷ lệ đoàn phí công đoàn"),
            new Param("giamTruBanThan", 15_500_000, "Giảm trừ gia cảnh bản thân / tháng"),
            new Param("giamTruNguoiPhuThuoc", 6_200_000, "Giảm trừ mỗi người phụ thuộc / tháng"),
            new Param("donViPhieuLuong", 1000, "Đơn vị lưu phiếu lương: 1000 = nghìn đồng (như thẻ lương / file Excel). "
                    + "Công thức luôn tính theo ĐỒNG, khoản nhập tay trên phiếu được nhân lại khi tính")
    );

    // =====================================================================
    // 2. BIẾN HỆ THỐNG (chỉ đọc)
    // =====================================================================

    public static final String G_CONG = "Chấm công";
    public static final String G_LUONG = "Cấu hình lương nhân viên";
    public static final String G_NHAP_TAY = "Nhập tay trên phiếu lương";
    /** Khoản thay đổi từng tháng: nhập ở trang "Khoản theo tháng" (thưởng có thể lấy từ ERP SQL Server) */
    public static final String G_THANG = "Khoản theo tháng / ERP";

    public static final List<Var> INPUTS = List.of(
            new Var("congChuan", "Số công chuẩn của tháng", G_CONG),
            new Var("congHL", "Công hưởng lương = công đi làm + phép năm + ngày lễ (xác nhận công đã duyệt > công đã xác nhận > công máy)", G_CONG),
            new Var("congChuanDenNay", "Công chuẩn tính đến hôm nay (tháng đang chạy); tháng đã qua = congChuan", G_CONG),
            new Var("soNgayNghi", "Số ngày nghỉ tính đến hôm nay = MAX(0, congChuanDenNay - congHL)", G_CONG),
            new Var("tyLeCong", "Tỷ lệ công = MIN(1, congHL / congChuan)", G_CONG),
            new Var("soNgayDiLam", "Số ngày có chấm công > 0", G_CONG),
            new Var("gioOtThuong", "Giờ thêm ngày thường (theo giờ vào/ra)", G_CONG),
            new Var("gioOtChuNhat", "Giờ làm Chủ nhật", G_CONG),
            new Var("gioOtTong", "Tổng giờ thêm", G_CONG),
            new Var("congLe", "Số ngày lễ hưởng lương (holiday_config, không trùng CN) - đã cộng trong congHL", G_CONG),
            new Var("gioLamNgayLe", "Số giờ đi làm ngày lễ", G_CONG),
            new Var("gioLeQuyDoi", "Giờ làm ngày lễ × hệ số của ngày lễ (VD 4 giờ × 3.0 = 12)", G_CONG),
            new Var("soNgayPhep", "Số ngày nghỉ phép năm (xác nhận công đã duyệt) - đã cộng trong congHL", G_CONG),
            new Var("soNgayKhongLuong", "Số ngày nghỉ không lương (xác nhận công đã duyệt)", G_CONG),
            new Var("soNgayXacNhan", "Số ngày có yêu cầu xác nhận công đã duyệt", G_CONG),
            new Var("coNhapTangCa", "1 = tháng này có nhập tăng ca (giờ × hệ số) ở Khoản theo tháng", G_THANG),
            new Var("gioTangCaNhap", "Tổng số giờ tăng ca nhập tay", G_THANG),
            new Var("gioTangCaQuyDoi", "Σ giờ tăng ca × hệ số (VD 4x2 + 5x3 = 23)", G_THANG),

            new Var("luongCoBan", "Lương cơ bản / tháng (cấu hình lương, không có thì lấy users.salary)", G_LUONG),
            new Var("luongDongBhxh", "Mức lương đóng BHXH (không nhập thì = lương cơ bản)", G_LUONG),
            new Var("luongNgay", "Lương 1 ngày = luongCoBan / congChuan", G_LUONG),
            new Var("luongGio", "Lương 1 giờ = luongNgay / gioChuanNgay", G_LUONG),
            new Var("mucTrachNhiem", "Mức trách nhiệm (tổ trưởng, đào tạo, độc hại)", G_LUONG),
            new Var("heSoTrachNhiem", "Hệ số trách nhiệm (100% = 1)", G_LUONG),
            new Var("mucPhuCapLevel", "Mức phụ cấp level", G_LUONG),
            new Var("mucPhuCapThamNien", "Mức thâm niên hàng năm", G_LUONG),
            new Var("mucPhuCapKiemNhiem", "Mức phụ cấp kiêm nhiệm", G_LUONG),
            new Var("mucThamNien5Nam", "Mức thâm niên 10 năm", G_LUONG),
            new Var("kyQuy", "Ký quỹ trừ hàng tháng", G_LUONG),
            new Var("soNguoiPhuThuoc", "Số người phụ thuộc", G_LUONG),
            new Var("thamGiaBhxh", "1 = có đóng BHXH, 0 = không", G_LUONG),
            new Var("thamGiaCongDoan", "1 = có đóng công đoàn, 0 = không", G_LUONG),

            new Var("nhapThuongDichVu", "Thưởng dịch vụ - số nhập ở Khoản theo tháng / ERP", G_THANG),
            new Var("nhapThuongChinhSach", "Thưởng chính sách, gián tiếp, kế toán - số nhập / ERP", G_THANG),
            new Var("nhapThuongTuVanBanHang", "Thưởng tư vấn bán hàng (xe, bảo hiểm, PK) - số nhập / ERP", G_THANG),
            new Var("nhapThuongKhac", "Thưởng khác - số nhập / ERP", G_THANG),
            new Var("tamUng", "Tạm ứng / truy thu lương", G_THANG),
            new Var("ruiRo", "Rủi ro", G_THANG),
            new Var("phatViPham", "Phạt vi phạm đồng phục, chấm công, quy trình DV", G_THANG),
            new Var("phongChongThienTai", "Phạt KD, DV vi phạm / tạm thu hàng tồn / quỹ PCTT", G_THANG)
    );

    // =====================================================================
    // 3. HẠNG MỤC LƯƠNG (tính theo thứ tự, mỗi mục ghi đè được ở từng cấp)
    //    Chuỗi thứ 3 chỉ là MẪU GỢI Ý, không tự áp dụng.
    // =====================================================================

    public static final List<Item> ITEMS = List.of(
            new Item("luongNgayCong", "Lương ngày công",
                    "luongNgay * congHL"),
            new Item("luongThemGio", "Lương thêm giờ",
                    "luongGio * (gioOtThuong * heSoOtThuong + gioOtChuNhat * heSoOtChuNhat + gioLeQuyDoi)"),
            new Item("trachNhiem", "Trách nhiệm được nhận",
                    "mucTrachNhiem * IF(heSoTrachNhiem > 0, heSoTrachNhiem, 1) * tyLeCong"),
            new Item("phuCapLevel", "Phụ cấp level",
                    "mucPhuCapLevel * tyLeCong"),
            new Item("phuCapThamNien", "Thâm niên hàng năm",
                    "mucPhuCapThamNien * tyLeCong"),
            new Item("phuCapKiemNhiem", "Phụ cấp kiêm nhiệm",
                    "mucPhuCapKiemNhiem * tyLeCong"),
            new Item("thamNien5Nam", "II.5 Thâm niên 10 năm",
                    "mucThamNien5Nam * tyLeCong"),
            new Item("luongCbThamNienPhuCap", "Lương CB + thâm niên + phụ cấp",
                    "luongNgayCong + trachNhiem + phuCapLevel + phuCapThamNien + phuCapKiemNhiem + thamNien5Nam"),
            // II. THƯỞNG - tuỳ chọn: để trống = lấy đúng số nhập ở Khoản theo tháng / ERP.
            //    Tuỳ biến theo từng NV: khai báo tỉ lệ ở ô Tham số (VD tyLeThuongDichVu = 100%) rồi viết
            //    thuongDichVu = nhapThuongDichVu * tyLeThuongDichVu ; NV nhập tỉ lệ riêng ở Cấu hình lương NV.
            new Item("thuongDichVu", "II.1 Thưởng dịch vụ", "nhapThuongDichVu", true),
            new Item("thuongChinhSach", "II.2 Thưởng chính sách, gián tiếp, kế toán", "nhapThuongChinhSach", true),
            new Item("thuongTuVanBanHang", "II.3 Thưởng tư vấn bán hàng (xe, bảo hiểm, PK)", "nhapThuongTuVanBanHang", true),
            new Item("banLanhDao", "II.4 Thưởng khác (nếu có)", "nhapThuongKhac", true),
            new Item("tongThuNhap", "Tổng cộng thu nhập",
                    "luongCbThamNienPhuCap + luongThemGio + tongThuong"),
            new Item("bhxh", "BHXH (người lao động)",
                    "IF(thamGiaBhxh = 1 AND soNgayNghi < soNgayNghiKhongDongBhxh, luongDongBhxh * tyLeBhxh, 0)"),
            new Item("congDoan", "Công đoàn",
                    "IF(thamGiaCongDoan = 1, tongThuNhap * tyLeCongDoan, 0)"),
            new Item("giamTruGiaCanh", "Giảm trừ người phụ thuộc",
                    "soNguoiPhuThuoc * giamTruNguoiPhuThuoc"),
            new Item("sauGiamTru", "Thu nhập tính thuế (sau giảm trừ)",
                    "MAX(0, tongThuNhap - bhxh - giamTruBanThan - giamTruGiaCanh)"),
            new Item("thueTNCN", "Thuế TNCN",
                    "THUE_TNCN(sauGiamTru)"),
            new Item("thucNhan", "Thực nhận",
                    "tongThuNhap - kyQuy - phatViPham - bhxh - tamUng - congDoan - thueTNCN - phongChongThienTai - ruiRo")
    );

    public static final List<Func> FUNCTIONS = List.of(
            new Func("IF(điều_kiện, khi_đúng, khi_sai)", "Rẽ nhánh. VD IF(congHL >= 22, luongCoBan, luongNgay * congHL)"),
            new Func("MIN(a, b, ...) / MAX(a, b, ...)", "Nhỏ nhất / lớn nhất"),
            new Func("ROUND(x, n)", "Làm tròn n chữ số; n = -3 là làm tròn đến nghìn đồng"),
            new Func("ROUNDUP(x, n) / ROUNDDOWN(x, n)", "Làm tròn lên / xuống"),
            new Func("FLOOR(x) / CEIL(x) / ABS(x)", "Phần nguyên dưới / trên / giá trị tuyệt đối"),
            new Func("AND(...) / OR(...) / NOT(x)", "Logic, hoặc viết a AND b, a OR b"),
            new Func("THUE_TNCN(x)", "Thuế TNCN lũy tiến 5 bậc trên thu nhập tính thuế x")
    );

    // =====================================================================
    // TRA CỨU
    // =====================================================================

    public static final Set<String> PARAM_KEYS = BUILTIN_PARAMS.stream()
            .map(p -> Vars.key(p.name())).collect(Collectors.toUnmodifiableSet());

    /**
     * Tên cũ vẫn dùng được trong công thức: trước khi tính các hạng mục thưởng = số nhập;
     * tongThuong = tổng 4 hạng mục thưởng (trước đó = tổng số nhập).
     */
    public static final List<String> LEGACY_INPUTS = List.of("thuongDichVu", "thuongChinhSach",
            "thuongTuVanBanHang", "banLanhDao", "tongThuong");

    public static final Set<String> INPUT_KEYS = java.util.stream.Stream.concat(
            INPUTS.stream().map(Var::name), LEGACY_INPUTS.stream())
            .map(Vars::key).collect(Collectors.toUnmodifiableSet());

    public static final Set<String> ITEM_KEYS = ITEMS.stream()
            .map(i -> Vars.key(i.code())).collect(Collectors.toUnmodifiableSet());

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        BUILTIN_PARAMS.forEach(p -> LABELS.put(Vars.key(p.name()), p.label()));
        INPUTS.forEach(v -> LABELS.put(Vars.key(v.name()), v.label()));
        ITEMS.forEach(i -> LABELS.put(Vars.key(i.code()), i.label()));
    }

    public static String label(String name) {
        return LABELS.get(Vars.key(name));
    }

    public static Map<String, List<Var>> inputsByGroup() {
        return INPUTS.stream().collect(Collectors.groupingBy(Var::group, LinkedHashMap::new, Collectors.toList()));
    }
}