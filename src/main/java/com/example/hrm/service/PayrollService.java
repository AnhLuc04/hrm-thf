package com.example.hrm.service;


import com.example.hrm.entity.Payroll;
import com.example.hrm.entity.User;
import com.example.hrm.repository.PayrollRepository;
import com.example.hrm.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;


@Slf4j
@Service
@RequiredArgsConstructor
public class PayrollService {
    private final PayrollAutoService payrollAutoService;

    private final PayrollRepository payrollRepository;

    private final UserRepository userRepository;

    private final UnitScope unitScope;   // ĐƠN VỊ: Kế toán đơn vị nào chỉ nhập lương cho nhân viên đơn vị đó

    private static final Pattern DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    // Số dòng / cột tối đa quét để tìm header (tiêu đề công ty + các dòng merge phía trên bảng)
    private static final int MAX_HEADER_SCAN_ROWS = 30;
    private static final int MAX_HEADER_SCAN_COLS = 60;


    /*
        Danh sách các cột cần dò, theo thứ tự ưu tiên (key nội bộ -> từ khoá đã chuẩn hoá để so khớp).
        - "EXACT:xxx"   -> header phải khớp CHÍNH XÁC (dùng cho từ khoá ngắn, dễ trùng như "C/L")
        - "xxx"         -> header chỉ cần CHỨA từ khoá này là đủ (dùng cho tiêu đề dài, nhiều biến thể)

        Với mỗi sheet, mỗi cột chỉ được gán cho 1 key đầu tiên khớp (tránh 1 cột bị gán 2 lần).

        LƯU Ý: bảng lương có HEADER 2 TẦNG:
          - Tầng 1 (dòng chứa "Mã NV"): TT, Mã NV, Họ tên, CV, Tổng cộng thu nhập, Thực nhận,
            Lương CB + thâm niên + Phụ cấp các loại, Đối chiếu hành chính, C/L, GHI CHÚ,
            Số đúng lương TVBH, Giảm trừ bản thân, Giảm trừ gia cảnh, Sau giảm, PC trách nhiệm,
            Thưởng..., Các khoản giảm trừ lương...
          - Tầng 2 (dòng ngay dưới, header+1): Lương CB, Số ngày thực làm, Lương theo ngày,
            Công thêm giờ, Hệ số thêm giờ, Thành tiền, Ký quỹ, BHXH, Tạm ứng lương/Truy thu lương,
            Công đoàn 1% Tổng lương, Thuế TNCN, Rủi ro, Phạt DV.../Truy thu Quỹ Phòng chống Thiên Tai...

        findSheetLayout() sẽ gộp cả 2 dòng này lại khi build columnIndex.
    */
    private static final LinkedHashMap<String, String> COLUMN_KEYWORDS = new LinkedHashMap<>();

    static {
        COLUMN_KEYWORDS.put("maNv", "EXACT:manv");
        COLUMN_KEYWORDS.put("hoTen", "EXACT:hoten");
        COLUMN_KEYWORDS.put("cv", "EXACT:cv");

        // ===== Tầng 2: nhóm "Lương cơ bản" / "Lương thêm giờ" =====
        // "Lương CB" (bare) -> công chuẩn/lương cơ bản dùng để tính lương ngày công.
        // Dùng EXACT để KHÔNG bị nhầm với cột "Lương CB + thâm niên + Phụ cấp các loại".
        COLUMN_KEYWORDS.put("congChuan", "EXACT:luongcb");
        // "Số ngày thực làm"
        COLUMN_KEYWORDS.put("congHL", "songaythuclam");
        // "Lương theo ngày"
        COLUMN_KEYWORDS.put("luongNgayCong", "luongtheongay");
        // "Thành tiền" (kết quả cuối của nhóm Lương thêm giờ: Công thêm giờ x Hệ số thêm giờ)
        // Dùng EXACT vì "Thành tiền" là tiêu đề ngắn, tránh match nhầm cột khác.
        COLUMN_KEYWORDS.put("luongThemGio", "EXACT:thanhtien");

        COLUMN_KEYWORDS.put("heSoTrachNhiem", "hesotrachnhiem");
        COLUMN_KEYWORDS.put("trachNhiemDuocNhan", "trachnhiemduocnhan");
        COLUMN_KEYWORDS.put("phuCapLevel", "phucaplevel");
        COLUMN_KEYWORDS.put("phuCapThamNien", "phucapthamnien");
        COLUMN_KEYWORDS.put("phuCapKiemNhiem", "phucapkiemnhiem");
        COLUMN_KEYWORDS.put("thamNien5Nam", "thamniencongtac");
        COLUMN_KEYWORDS.put("thuongDichVu", "thuongdichvu");
        COLUMN_KEYWORDS.put("thuongChinhSach", "thuongchinhsach");
        COLUMN_KEYWORDS.put("thuongTuVanBanHang", "thuongtuvan");
        COLUMN_KEYWORDS.put("banLanhDao", "banlanhdao");

        COLUMN_KEYWORDS.put("netSalary", "tongcongthunhap");

        COLUMN_KEYWORDS.put("kyQuy", "kyquy");
        COLUMN_KEYWORDS.put("bhxh", "EXACT:bhxh");
        COLUMN_KEYWORDS.put("tamUngLuong", "tamung");
        COLUMN_KEYWORDS.put("congDoan", "congdoan");
        COLUMN_KEYWORDS.put("thueTNCN", "thuetncn");
        COLUMN_KEYWORDS.put("ruiRo", "ruiro");
        // "...Truy thu Quỹ Phòng chống Thiên Tai" (nằm chung cụm với cột Phạt DV Vi phạm quy trình)
        COLUMN_KEYWORDS.put("phongChongThienTai", "phongchongthientai");

        COLUMN_KEYWORDS.put("thucNhan", "EXACT:thucnhan");
        // "Lương CB + thâm niên + Phụ cấp các loại" -> dùng cụm đặc trưng, KHÔNG dùng "luongcb"
        // để tránh match nhầm sang cột "Lương CB" (bare) đã dùng cho congChuan ở trên.
        COLUMN_KEYWORDS.put("luongCoBanThamNienPhuCap", "thamnienphucapcacloai");
        COLUMN_KEYWORDS.put("doiChieuHanhChinh", "doichieuhanhchinh");
        COLUMN_KEYWORDS.put("chenhLech", "EXACT:cl");
        COLUMN_KEYWORDS.put("ghiChu", "EXACT:ghichu");

        COLUMN_KEYWORDS.put("giamTruBanThan", "giamtrubanthan");
        COLUMN_KEYWORDS.put("giamTruGiaCanh", "giamtrugiacanh");
        COLUMN_KEYWORDS.put("sauGiamTru", "saugiam");

        // "Số đúng lương TVBH" (cột cuối cùng bên phải bảng lương)
        // "Số đúng lương TVBH" -> normalizeHeader() -> "sodungluongtvbh"
        COLUMN_KEYWORDS.put("soDuLuongTVBH", "dungluongtvbh");
    }

    // =====================================================
    // IMPORT EXCEL LƯƠNG
    // =====================================================

    @Transactional
    public String importExcel(
            MultipartFile file,
            Integer month,
            Integer year

    ) throws Exception {

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {

            log.info("====================");
            log.info("FILE : {}", file.getOriginalFilename());
            log.info("SO SHEET : {}", workbook.getNumberOfSheets());
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                log.info("SHEET {} = '{}'", i, workbook.getSheetName(i));
            }
            log.info("====================");

            SheetLayout layout = findSheetLayout(workbook);

            if (layout == null) {
                return "Import thất bại: không tìm thấy sheet nào chứa cột 'Mã NV' trong file Excel.";
            }

            log.info("SU DUNG SHEET '{}' | HEADER ROW = {}", layout.sheet.getSheetName(), layout.headerRowIndex);
            log.info("CAC COT DA DO DUOC: {}", layout.columnIndex);

            if (!layout.columnIndex.containsKey("maNv")) {
                return "Import thất bại: không tìm thấy cột 'Mã NV' trong sheet '" + layout.sheet.getSheetName() + "'.";
            }

            Sheet sheet = layout.sheet;

            // 🔒 ĐƠN VỊ: chỉ khớp mã với nhân viên của đơn vị đang làm việc, không ghi nhầm phiếu đơn vị khác
            List<User> users = unitScope.users();
            log.info("TOTAL USER MYSQL (trong don vi) : {}", users.size());

            Map<String, User> userMap = buildUserMap(users);

            int success = 0;
            int notFound = 0;
            int duplicate = 0;
            int error = 0;

            StringBuilder debugLog = new StringBuilder();

            int lastRow = sheet.getLastRowNum();
            int maNvCol = layout.columnIndex.get("maNv");
            Integer hoTenCol = layout.columnIndex.get("hoTen");

            for (int rowIndex = layout.headerRowIndex + 1; rowIndex <= lastRow; rowIndex++) {

                Row row = sheet.getRow(rowIndex);

                if (row == null) {
                    continue;
                }

                String salaryCode = normalizeCode(getCellValue(row.getCell(maNvCol)));

                if (salaryCode.isBlank()) {
                    // Dòng section (VD: "Ban Giám đốc", "Hành chính"...) hoặc dòng trống -> bỏ qua
                    continue;
                }

                User user = userMap.get(salaryCode);

                if (user == null) {

                    log.warn("KHONG TIM THAY USER = {} (hex={})", salaryCode, toHex(salaryCode));

                    String hoTen = hoTenCol != null ? getCellValue(row.getCell(hoTenCol)) : "";
                    debugLog.append("Row ").append(rowIndex + 1)
                            .append(": code='").append(salaryCode)
                            .append("' (").append(hoTen).append(") not found. ");

                    notFound++;
                    continue;
                }

                boolean exists = payrollRepository
                        .existsByEmployeeIdAndMonthAndYear(user.getId(), month, year);

                if (exists) {
                    log.info("TRUNG PHIEU : {}", salaryCode);
                    duplicate++;
                    continue;
                }

                try {

                    Payroll payroll = buildPayrollFromRow(row, layout, user, month, year);

                    payrollRepository.save(payroll);

                    success++;

                } catch (Exception rowEx) {

                    // Log đầy đủ lỗi + số dòng Excel để dễ tra, KHÔNG để 1 dòng lỗi
                    // làm hỏng toàn bộ transaction (rollback hết các dòng đã import).
                    log.error("LOI KHI IMPORT DONG {} (ma NV = {}): {}",
                            rowIndex + 1, salaryCode, rowEx.getMessage(), rowEx);

                    error++;
                }
            }

            String result = "Import thành công: " + success
                    + " phiếu lương | Không tìm thấy: " + notFound
                    + " | Trùng: " + duplicate
                    + " | Lỗi: " + error;

            if (notFound > 0) {
                log.warn("CHI TIET KHONG TIM THAY: {}", debugLog);
            }

            // =====================================================
            // DEBUG: In trực tiếp ra màn hình (không cần xem console)
            // - Sheet nào đã được chọn để import
            // - Danh sách TẤT CẢ các cột đã dò được (key nội bộ -> vị trí cột Excel)
            // - Danh sách các cột ĐỊNH NGHĨA SẴN nhưng KHÔNG dò được trong file này
            //   (đây chính là các trường sẽ bị NULL / trống trong DB)
            // =====================================================

            StringBuilder debugInfo = new StringBuilder();
            debugInfo.append("\n\n===== DEBUG (xoá đoạn này sau khi hết lỗi) =====\n");
            debugInfo.append("Sheet dùng để import: '").append(layout.sheet.getSheetName())
                    .append("' | Dòng header (Excel row): ").append(layout.headerRowIndex + 1).append("\n");

            debugInfo.append("\n--- CÁC CỘT ĐÃ DÒ ĐƯỢC (").append(layout.columnIndex.size()).append(" cột) ---\n");
            for (Map.Entry<String, Integer> e : layout.columnIndex.entrySet()) {
                int excelCol = e.getValue();
                String excelColLetter = org.apache.poi.ss.util.CellReference.convertNumToColString(excelCol);
                debugInfo.append("  ").append(e.getKey())
                        .append(" -> cột Excel ").append(excelColLetter)
                        .append(" (index ").append(excelCol).append(")\n");
            }

            debugInfo.append("\n--- CÁC CỘT BỊ THIẾU (sẽ NULL trong DB) ---\n");
            for (String key : COLUMN_KEYWORDS.keySet()) {
                if (!layout.columnIndex.containsKey(key)) {
                    debugInfo.append("  ").append(key)
                            .append(" (rule dò: ").append(COLUMN_KEYWORDS.get(key)).append(")\n");
                }
            }

            debugInfo.append("=====================================\n");

            return result + debugInfo;
        }
    }





    private Payroll buildPayrollFromRow(
            Row row,
            SheetLayout layout,
            User user,
            Integer month,
            Integer year
    ){

        Payroll payroll = new Payroll();


        payroll.setEmployee(user);
        payroll.setMonth(month);
        payroll.setYear(year);
        payroll.setImportDate(LocalDate.now());


        payroll.setPosition(
                readText(row, layout, "cv")
        );


        // công
        payroll.setCongChuan(
                readMoney(row, layout,"congChuan")
        );

        payroll.setCongHL(
                readMoney(row, layout,"congHL")
        );

        payroll.setLuongNgayCong(
                readMoney(row, layout,"luongNgayCong")
        );

        payroll.setLuongThemGio(
                readMoney(row, layout,"luongThemGio")
        );



        // phụ cấp

        payroll.setHeSoTrachNhiem(
                readMoney(row, layout,"heSoTrachNhiem")
        );

        payroll.setTrachNhiemDuocNhan(
                readMoney(row, layout,"trachNhiemDuocNhan")
        );

        payroll.setPhuCapLevel(
                readMoney(row, layout,"phuCapLevel")
        );

        payroll.setPhuCapThamNien(
                readMoney(row, layout,"phuCapThamNien")
        );

        payroll.setPhuCapKiemNhiem(
                readMoney(row, layout,"phuCapKiemNhiem")
        );

        payroll.setThamNien5Nam(
                readMoney(row, layout,"thamNien5Nam")
        );



        // thưởng

        payroll.setThuongDichVu(
                readMoney(row,layout,"thuongDichVu")
        );

        payroll.setThuongChinhSach(
                readMoney(row,layout,"thuongChinhSach")
        );

        payroll.setThuongTuVanBanHang(
                readMoney(row,layout,"thuongTuVanBanHang")
        );

        payroll.setBanLanhDao(
                readMoney(row,layout,"banLanhDao")
        );



        payroll.setNetSalary(
                readMoney(row,layout,"netSalary")
        );



        // giảm trừ

        payroll.setKyQuy(
                readMoney(row,layout,"kyQuy")
        );

        payroll.setBhxh(
                readMoney(row,layout,"bhxh")
        );


        payroll.setTamUngLuong(
                readMoney(row,layout,"tamUngLuong")
        );


        payroll.setCongDoan(
                readMoney(row,layout,"congDoan")
        );


        payroll.setThueTNCN(
                readMoney(row,layout,"thueTNCN")
        );


        payroll.setRuiRo(
                readMoney(row,layout,"ruiRo")
        );


        payroll.setPhongChongThienTai(
                readMoney(row,layout,"phongChongThienTai")
        );



        // thực nhận

        payroll.setThucNhan(
                readMoney(row,layout,"thucNhan")
        );


        payroll.setLuongCoBanThamNienPhuCap(
                readMoney(row,layout,"luongCoBanThamNienPhuCap")
        );


        payroll.setDoiChieuHanhChinh(
                readMoney(row,layout,"doiChieuHanhChinh")
        );


        payroll.setChenhLech(
                readMoney(row,layout,"chenhLech")
        );


        payroll.setGhiChu(
                readText(row,layout,"ghiChu")
        );


        // gia cảnh

        payroll.setGiamTruBanThan(
                readMoney(row,layout,"giamTruBanThan")
        );


        payroll.setGiamTruGiaCanh(
                readMoney(row,layout,"giamTruGiaCanh")
        );


        payroll.setSauGiamTru(
                readMoney(row,layout,"sauGiamTru")
        );


        payroll.setSoDuLuongTVBH(
                readMoney(row,layout,"soDuLuongTVBH")
        );


        return payroll;
    }



    private Double readMoney(Row row, SheetLayout layout, String key) {

        Integer col = layout.columnIndex.get(key);

        if (col == null) {
            return null;
        }

        return parseMoney(getCellValue(row.getCell(col)));
    }


    private String readText(Row row, SheetLayout layout, String key) {

        Integer col = layout.columnIndex.get(key);

        if (col == null) {
            return null;
        }

        String value = getCellValue(row.getCell(col));

        return value.isBlank() ? null : value;
    }




    // =====================================================
    // TỰ ĐỘNG DÒ SHEET + TOÀN BỘ CỘT (Mã NV, Họ tên, các khoản phụ cấp/giảm trừ...)
    // =====================================================

    private SheetLayout findSheetLayout(Workbook workbook) {

        // QUÉT TẤT CẢ SHEET, TẤT CẢ ỨNG VIÊN HEADER HỢP LỆ (có cột "Mã NV"),
        // RỒI CHỌN ỨNG VIÊN CÓ SỐ CỘT KHỚP NHIỀU NHẤT.
        // Lý do: file có thể có nhiều sheet (VD: "PHIẾU LƯƠNG" mẫu 1 người,
        // "IN BẢNG LƯƠNG" bảng đầy đủ) đều có cột "Mã NV", nhưng ta luôn muốn
        // dùng sheet có đầy đủ cột dữ liệu nhất, không phải sheet xuất hiện trước.

        SheetLayout best = null;

        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {

            Sheet sheet = workbook.getSheetAt(s);

            int maxRow = Math.min(sheet.getLastRowNum(), MAX_HEADER_SCAN_ROWS);

            for (int r = 0; r <= maxRow; r++) {

                Row row = sheet.getRow(r);

                if (row == null) {
                    continue;
                }

                // Bước 1: chỉ dùng dòng này để XÁC ĐỊNH có phải header row không
                // (phải có cột "Mã NV").
                Map<String, Integer> probe = scanRowForColumns(row);

                if (!probe.containsKey("maNv")) {
                    continue;
                }

                // Bước 2: bảng lương có header 2 TẦNG - dòng chứa "Mã NV" (r) chỉ có
                // các cột cấp 1 (Mã NV, Họ tên, CV, Tổng cộng thu nhập...), còn các cột
                // cấp 2 (Lương CB, Số ngày thực làm, BHXH, Rủi ro, Sau giảm...) nằm ở
                // dòng NGAY BÊN DƯỚI (r + 1). Gộp cả 2 dòng lại để không bị mất cột.
                Map<String, Integer> columnIndex = scanHeaderRows(sheet, r, r + 1);

                log.info("UNG VIEN HEADER O SHEET '{}' DONG {} (Excel row {}) -> {} COT KHOP",
                        sheet.getSheetName(), r, r + 1, columnIndex.size());

                if (best == null || columnIndex.size() > best.columnIndex.size()) {
                    best = new SheetLayout(sheet, r, columnIndex);
                }

                // Mỗi sheet chỉ lấy header row đầu tiên tìm thấy (không quét tiếp
                // các dòng khác trong CÙNG sheet đó), rồi chuyển sang sheet kế tiếp.
                break;
            }
        }

        if (best != null) {
            log.info("CHON SHEET '{}' (HEADER ROW {}) VI CO NHIEU COT KHOP NHAT: {} COT",
                    best.sheet.getSheetName(), best.headerRowIndex, best.columnIndex.size());
        }

        return best;
    }




    // =====================================================
    // QUÉT NHIỀU DÒNG HEADER (header 2 tầng), GỘP KẾT QUẢ LẠI
    // =====================================================

    private Map<String, Integer> scanHeaderRows(Sheet sheet, int... rowIndexes) {

        Map<String, Integer> result = new LinkedHashMap<>();

        for (int rowIndex : rowIndexes) {

            Row row = sheet.getRow(rowIndex);

            if (row == null) {
                continue;
            }

            scanRowIntoMap(row, result);
        }

        return result;
    }




    // =====================================================
    // QUÉT 1 DÒNG, GÁN CỘT CHO TỪNG KEY THEO COLUMN_KEYWORDS
    // =====================================================

    private Map<String, Integer> scanRowForColumns(Row row) {

        Map<String, Integer> result = new LinkedHashMap<>();

        scanRowIntoMap(row, result);

        return result;
    }




    // =====================================================
    // QUÉT 1 DÒNG VÀ GHI KẾT QUẢ VÀO MAP CÓ SẴN (dùng chung cho
    // scanRowForColumns và scanHeaderRows để gộp nhiều dòng lại).
    // Key nào đã có trong map (do dòng trước gán) thì bỏ qua,
    // tránh dòng sau ghi đè mất kết quả của dòng trước.
    // =====================================================

    private void scanRowIntoMap(Row row, Map<String, Integer> result) {

        int maxCol = Math.min(row.getLastCellNum(), MAX_HEADER_SCAN_COLS);

        for (int c = 0; c < maxCol; c++) {

            Cell cell = row.getCell(c);

            if (cell == null) {
                continue;
            }

            String normalized = normalizeHeader(getCellValue(cell));

            if (normalized.isBlank()) {
                continue;
            }

            for (Map.Entry<String, String> entry : COLUMN_KEYWORDS.entrySet()) {

                String key = entry.getKey();

                // Cột đã được gán cho key này rồi (ở dòng này hoặc dòng trước đó
                // khi gộp header nhiều tầng) -> bỏ qua.
                if (result.containsKey(key)) {
                    continue;
                }

                String rule = entry.getValue();
                boolean matched;

                if (rule.startsWith("EXACT:")) {
                    matched = normalized.equals(rule.substring("EXACT:".length()));
                } else {
                    matched = normalized.contains(rule);
                }

                if (matched) {
                    result.put(key, c);
                    break;
                }
            }
        }
    }




    // =====================================================
    // BUILD MAP TRA CỨU USER THEO MÃ LƯƠNG
    // =====================================================

    private Map<String, User> buildUserMap(List<User> users) {

        Map<String, User> map = new HashMap<>();


        for (User user : users) {


            // ==========================
            // 1. MÃ LƯƠNG (ưu tiên)
            // ==========================

            if(user.getSalaryCode()!=null
                    && !user.getSalaryCode().isBlank()){


                String code =
                        normalizeCode(
                                user.getSalaryCode()
                        );


                map.putIfAbsent(code,user);

            }



            // ==========================
            // 2. MÃ NHÂN VIÊN
            // Excel đang dùng cột Mã NV
            // ==========================

            if(user.getEmployeeNo()!=null
                    && !user.getEmployeeNo().isBlank()){


                String code =
                        normalizeCode(
                                user.getEmployeeNo()
                        );


                map.putIfAbsent(code,user);

            }



        /*
          KHÔNG DÙNG attendanceCode
          vì đây là mã chấm công
        */

        }


        log.info(
                "USER MAP SIZE = {}",
                map.size()
        );


        return map;

    }

    // =====================================================
    // LỊCH SỬ LƯƠNG
    // =====================================================

    public List<Payroll> getHistory(String salaryCode) {

        return payrollRepository
                .findByEmployeeSalaryCodeOrderByYearDescMonthDesc(salaryCode).stream()
                .filter(p -> p.getEmployee() == null || unitScope.inScope(p.getEmployee()))   // 🔒 ĐƠN VỊ
                .toList();
    }




    // =====================================================
    // LƯƠNG THEO THÁNG
    // =====================================================

    public List<Payroll> getByMonthYear(Integer month, Integer year) {

        return payrollRepository.findByMonthAndYear(month, year).stream()
                .filter(p -> p.getEmployee() == null || unitScope.inScope(p.getEmployee()))
                .toList();
    }




    // =====================================================
    // ĐỌC CELL EXCEL
    // =====================================================

    private String getCellValue(Cell cell) {

        if (cell == null) {
            return "";
        }

        DataFormatter formatter = new DataFormatter();

        return formatter.formatCellValue(cell).trim();
    }




    // =====================================================
    // PARSE SỐ TIỀN (loại bỏ dấu phẩy/chấm ngăn cách hàng nghìn, ký hiệu tiền tệ)
    // =====================================================

    private Double parseMoney(String raw) {

        if (raw == null || raw.isBlank()) {
            return null;
        }

        String cleaned = raw
                .replace(",", "")
                .replace(".", "")
                .replace("₫", "")
                .replace("đ", "")
                .replace("%", "")
                .trim();

        if (cleaned.isBlank() || cleaned.equals("-")) {
            return null;
        }

        // Số âm dạng kế toán: (1.234) -> -1234
        boolean negative = cleaned.startsWith("(") && cleaned.endsWith(")");
        if (negative) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }

        try {
            double value = Double.parseDouble(cleaned);
            return negative ? -value : value;
        } catch (NumberFormatException e) {
            log.warn("KHONG PARSE DUOC SO TIEN: '{}'", raw);
            return null;
        }
    }




    // =====================================================
    // CHUẨN HÓA MÃ NHÂN VIÊN
    // =====================================================

    private String normalizeCode(String code) {

        if (code == null) {
            return "";
        }

        code = code
                .replace("\u00A0", "")   // non-breaking space
                .replace("\u200B", "")   // zero-width space
                .replace("\uFEFF", "")   // BOM
                .replace(" ", "")
                .trim();

        /*
            MySQL:
            1.0391

            Excel:
            01.0391

            => chuẩn hóa: pad số 0 phía trước phần nguyên
        */

        if (code.matches("\\d\\.\\d{4}")) {
            code = "0" + code;
        }

        return code;
    }




    // =====================================================
    // CHUẨN HÓA TÊN HEADER: bỏ dấu, bỏ khoảng trắng/ký tự đặc biệt, lowercase
    // (vd "Mã NV" -> "manv", "Họ tên" -> "hoten", "C/L" -> "cl")
    // =====================================================

    private String normalizeHeader(String s) {

        if (s == null) {
            return "";
        }

        String temp = Normalizer.normalize(s, Normalizer.Form.NFD);
        temp = DIACRITICS.matcher(temp).replaceAll("");
        temp = temp.replace('đ', 'd').replace('Đ', 'D');
        temp = temp.toLowerCase();
        temp = temp.replaceAll("[^a-z0-9]", "");

        return temp;
    }




    // =====================================================
    // DEBUG: IN MÃ HEX CỦA CHUỖI (phát hiện ký tự ẩn)
    // =====================================================

    private String toHex(String s) {

        if (s == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        for (char c : s.toCharArray()) {
            sb.append(String.format("[%04x]", (int) c));
        }

        return sb.toString();
    }

// =====================================
// CHI TIẾT PHIẾU LƯƠNG
// =====================================

    public Payroll findById(Long id){

        return payrollRepository
                .findById(id)
                .orElse(null);

    }


// =====================================
// PHIẾU LƯƠNG CÁ NHÂN
// =====================================

    public List<Payroll> getMyPayroll(
            String employeeNo
    ){


        return payrollRepository
                .findByEmployeeEmployeeNoOrderByYearDescMonthDesc(
                        employeeNo
                );


    }
    private static class SheetLayout {

        final Sheet sheet;
        final int headerRowIndex;
        final Map<String, Integer> columnIndex;

        SheetLayout(Sheet sheet, int headerRowIndex, Map<String, Integer> columnIndex) {
            this.sheet = sheet;
            this.headerRowIndex = headerRowIndex;
            this.columnIndex = columnIndex;
        }
    }

}