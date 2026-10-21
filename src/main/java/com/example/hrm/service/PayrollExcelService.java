package com.example.hrm.service;

import com.example.hrm.entity.MonthlyItemType;
import com.example.hrm.entity.Payroll;
import com.example.hrm.entity.User;
import com.example.hrm.service.PayrollCalculationService.CalculationResult;
import com.example.hrm.service.PayrollCalculationService.EmployeeCalc;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;

/**
 * Xuất / nhập Excel của module lương (Apache POI).
 *   - Bảng lương tháng (số tiền theo NGHÌN ĐỒNG như thẻ lương) + dòng tổng
 *   - Khoản theo tháng (ĐỒNG): tải về làm mẫu, sửa, nhập lại
 */
@Service
public class PayrollExcelService {

    // =====================================================================
    // BẢNG LƯƠNG THÁNG
    // =====================================================================

    private record Col(String header, Function<EmployeeCalc, Object> value, boolean money) {
    }

    private static Col text(String h, Function<EmployeeCalc, Object> f) {
        return new Col(h, f, false);
    }

    private static Col money(String h, Function<Payroll, Double> f) {
        return new Col(h, c -> f.apply(c.getPayroll()), true);
    }

    private static final List<Col> PAYROLL_COLS = List.of(
            text("Mã NV", c -> c.getUser().getEmployeeNo()),
            text("Họ tên", c -> c.getUser().getFullName()),
            text("Phòng", c -> c.getUser().getDepartment()),
            money("Lương cơ bản", Payroll::getCongChuan),
            new Col("Công HL", c -> c.getPayroll().getCongHL(), false),
            money("I.1 Lương ngày công", Payroll::getLuongNgayCong),
            money("I.2 Lương thêm giờ", Payroll::getLuongThemGio),
            money("I.3 Thâm niên hàng năm", Payroll::getPhuCapThamNien),
            money("I.4 T.Nhiệm được nhận", Payroll::getTrachNhiemDuocNhan),
            money("I.5 Phụ cấp Level", Payroll::getPhuCapLevel),
            money("I.6 PC kiêm nhiệm", Payroll::getPhuCapKiemNhiem),
            money("II.1 Thưởng dịch vụ", Payroll::getThuongDichVu),
            money("II.2 Thưởng chính sách", Payroll::getThuongChinhSach),
            money("II.3 Thưởng TVBH", Payroll::getThuongTuVanBanHang),
            money("II.4 Thưởng khác", Payroll::getBanLanhDao),
            money("II.5 Thâm niên 10 năm", Payroll::getThamNien5Nam),
            money("IV. Tổng thu nhập", Payroll::getNetSalary),
            money("III.1 Ký quỹ", Payroll::getKyQuy),
            money("III.2 Phạt vi phạm", Payroll::getPhatViPham),
            money("III.3 BHXH", Payroll::getBhxh),
            money("III.4 Tạm ứng, truy thu", Payroll::getTamUngLuong),
            money("III.5 Công đoàn", Payroll::getCongDoan),
            money("III.6 Thuế TNCN", Payroll::getThueTNCN),
            money("III.7 Phạt KD, DV", Payroll::getPhongChongThienTai),
            money("III.8 Rủi ro", Payroll::getRuiRo),
            money("V. Thực nhận", Payroll::getThucNhan)
    );

    /** File Excel bảng lương từ kết quả tính (xem trước hoặc đã lưu) */
    public byte[] payroll(CalculationResult r, String companyName) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            Sheet sh = wb.createSheet("Bang luong " + r.getMonth() + "-" + r.getYear());

            int rowIdx = 0;
            Row title = sh.createRow(rowIdx++);
            cell(title, 0, (companyName == null || companyName.isBlank() ? "" : companyName + " - ")
                    + "BẢNG LƯƠNG THÁNG " + r.getMonth() + "/" + r.getYear()
                    + (r.getDepartment() == null || r.getDepartment().isBlank() ? "" : " - " + r.getDepartment()), st.title);
            sh.addMergedRegion(new CellRangeAddress(0, 0, 0, 8));
            Row sub = sh.createRow(rowIdx++);
            cell(sub, 0, "Đơn vị: nghìn đồng" + (r.isLocked() ? "  |  KỲ ĐÃ CHỐT" : ""), st.note);

            int headerRow = rowIdx;
            Row h = sh.createRow(rowIdx++);
            h.setHeightInPoints(32);
            for (int i = 0; i < PAYROLL_COLS.size(); i++) {
                cell(h, i, PAYROLL_COLS.get(i).header(), st.header);
            }

            int first = rowIdx;
            for (EmployeeCalc c : r.getRows()) {
                Row row = sh.createRow(rowIdx++);
                for (int i = 0; i < PAYROLL_COLS.size(); i++) {
                    Col col = PAYROLL_COLS.get(i);
                    Object v = col.value().apply(c);
                    cell(row, i, v, col.money() ? st.money : (v instanceof Number ? st.decimal : st.text));
                }
            }
            int last = rowIdx - 1;

            Row total = sh.createRow(rowIdx);
            cell(total, 0, "TỔNG (" + r.getRows().size() + " người)", st.totalText);
            sh.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 0, 2));
            for (int i = 3; i < PAYROLL_COLS.size(); i++) {
                Cell cc = total.createCell(i);
                cc.setCellStyle(st.total);
                if (PAYROLL_COLS.get(i).money() && last >= first) {
                    String colL = CellReference.convertNumToColString(i);
                    cc.setCellFormula("SUM(" + colL + (first + 1) + ":" + colL + (last + 1) + ")");
                }
            }

            sh.createFreezePane(3, headerRow + 1);
            sh.setAutoFilter(new CellRangeAddress(headerRow, Math.max(headerRow, last), 0, PAYROLL_COLS.size() - 1));
            sh.setColumnWidth(0, 12 * 256);
            sh.setColumnWidth(1, 26 * 256);
            sh.setColumnWidth(2, 16 * 256);
            for (int i = 3; i < PAYROLL_COLS.size(); i++) {
                sh.setColumnWidth(i, 13 * 256);
            }
            sh.getPrintSetup().setLandscape(true);
            sh.setFitToPage(true);
            sh.getPrintSetup().setFitWidth((short) 1);
            sh.getPrintSetup().setFitHeight((short) 0);

            return bytes(wb);
        }
    }

    // =====================================================================
    // KHOẢN THEO THÁNG
    // =====================================================================

    private static final String CODE_ROW_MARK = "#ma";

    /**
     * Dòng 1: tiêu đề; dòng 2: tên cột; dòng 3: mã loại (để nhập lại, đừng sửa); từ dòng 4: dữ liệu (ĐỒNG).
     */
    public byte[] monthlyItems(YearMonth ym, List<User> users, Map<Long, Map<MonthlyItemType, Double>> values,
                               Map<Long, List<PayrollOvertimeService.Line>> overtime) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            Sheet sh = wb.createSheet("Khoan " + ym.getMonthValue() + "-" + ym.getYear());
            MonthlyItemType[] types = MonthlyItemType.values();

            Row title = sh.createRow(0);
            cell(title, 0, "KHOẢN THEO THÁNG " + ym.getMonthValue() + "/" + ym.getYear()
                    + " (đơn vị: ĐỒNG; ô trống = không có / xoá khoản đó)", st.title);

            Row h = sh.createRow(1);
            h.setHeightInPoints(45);
            cell(h, 0, "Mã NV", st.header);
            cell(h, 1, "Họ tên", st.header);
            cell(h, 2, "Phòng", st.header);
            Row codes = sh.createRow(2);
            cell(codes, 0, CODE_ROW_MARK, st.note);
            for (int i = 0; i < types.length; i++) {
                cell(h, 3 + i, types[i].getLabel(), st.header);
                cell(codes, 3 + i, types[i].name(), st.note);
            }
            int otCol = 3 + types.length;
            cell(h, otCol, OT_LABEL, st.header);
            cell(codes, otCol, OT_CODE, st.note);

            int r = 3;
            for (User u : users) {
                Row row = sh.createRow(r++);
                cell(row, 0, u.getEmployeeNo(), st.text);
                cell(row, 1, u.getFullName(), st.text);
                cell(row, 2, u.getDepartment(), st.text);
                Map<MonthlyItemType, Double> m = values.getOrDefault(u.getId(), Map.of());
                for (int i = 0; i < types.length; i++) {
                    cell(row, 3 + i, m.get(types[i]), st.money);
                }
                String ot = PayrollOvertimeService.format(overtime.getOrDefault(u.getId(), List.of()));
                cell(row, otCol, ot.isEmpty() ? null : ot, st.text);
            }

            sh.createFreezePane(3, 3);
            sh.setColumnWidth(0, 12 * 256);
            sh.setColumnWidth(1, 26 * 256);
            sh.setColumnWidth(2, 16 * 256);
            for (int i = 0; i < types.length; i++) {
                sh.setColumnWidth(3 + i, 17 * 256);
            }
            sh.setColumnWidth(otCol, 24 * 256);
            return bytes(wb);
        }
    }

    static final String OT_CODE = "TANG_CA";
    static final String OT_LABEL = "Tăng ca (giờ x hệ số, VD 4x2 + 5x3)";

    public record MonthlyImport(Map<Long, Map<MonthlyItemType, Double>> values,
                                Map<Long, List<PayrollOvertimeService.Line>> overtime,
                                Map<Long, String> labels,
                                List<String> errors,
                                List<String> warnings) {
    }

    /**
     * Đọc file Excel khoản theo tháng.
     * Cột nhận theo dòng mã loại (THUONG_DICH_VU...) nếu có, không thì theo đúng tên cột.
     * Cột không có trong file -> giữ nguyên; ô trống -> xoá khoản đó.
     */
    public MonthlyImport readMonthlyItems(InputStream in, Map<String, User> byCode, Set<MonthlyItemType> erpTypes)
            throws IOException {

        Map<Long, Map<MonthlyItemType, Double>> values = new LinkedHashMap<>();
        Map<Long, List<PayrollOvertimeService.Line>> overtime = new LinkedHashMap<>();
        int otCol = -1;
        Map<Long, String> labels = new HashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();

            // Tìm dòng tiêu đề (ô đầu = "Mã NV") và các cột loại khoản
            int headerRow = -1;
            Map<Integer, MonthlyItemType> cols = new LinkedHashMap<>();
            for (int r = 0; r <= Math.min(10, sh.getLastRowNum()) && headerRow < 0; r++) {
                Row row = sh.getRow(r);
                if (row != null && "mã nv".equalsIgnoreCase(fmt.formatCellValue(row.getCell(0)).trim())) {
                    headerRow = r;
                    for (int c = 1; c < row.getLastCellNum(); c++) {
                        String hd = fmt.formatCellValue(row.getCell(c)).trim();
                        MonthlyItemType t = byLabel(hd);
                        if (t != null) {
                            cols.put(c, t);
                        } else if (hd.toLowerCase(Locale.ROOT).startsWith("tăng ca") || OT_CODE.equalsIgnoreCase(hd)) {
                            otCol = c;
                        }
                    }
                }
            }
            if (headerRow < 0) {
                errors.add("không thấy dòng tiêu đề có ô đầu là 'Mã NV'");
                return new MonthlyImport(values, overtime, labels, errors, warnings);
            }
            int dataStart = headerRow + 1;
            Row codeRow = sh.getRow(dataStart);
            if (codeRow != null && CODE_ROW_MARK.equals(fmt.formatCellValue(codeRow.getCell(0)).trim())) {
                cols.clear();
                for (int c = 1; c < codeRow.getLastCellNum(); c++) {
                    String code = fmt.formatCellValue(codeRow.getCell(c)).trim();
                    if (OT_CODE.equals(code)) {
                        otCol = c;
                        continue;
                    }
                    try {
                        cols.put(c, MonthlyItemType.valueOf(code));
                    } catch (IllegalArgumentException ignored) {
                        // cột thông tin (họ tên, phòng)
                    }
                }
                dataStart++;
            }
            if (cols.isEmpty() && otCol < 0) {
                errors.add("không thấy cột khoản nào (tên cột phải giống file tải về)");
                return new MonthlyImport(values, overtime, labels, errors, warnings);
            }
            Set<MonthlyItemType> skippedErp = EnumSet.noneOf(MonthlyItemType.class);

            for (int r = dataStart; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null) {
                    continue;
                }
                String code = fmt.formatCellValue(row.getCell(0)).trim();
                if (code.isEmpty()) {
                    continue;
                }
                User u = byCode.get(ErpBonusService.normEmp(code));
                if (u == null) {
                    warnings.add("dòng " + (r + 1) + ": không có nhân viên mã '" + code + "'");
                    continue;
                }
                if (values.containsKey(u.getId())) {
                    errors.add("dòng " + (r + 1) + ": mã '" + code + "' bị trùng");
                    continue;
                }
                Map<MonthlyItemType, Double> m = new EnumMap<>(MonthlyItemType.class);
                for (Map.Entry<Integer, MonthlyItemType> e : cols.entrySet()) {
                    MonthlyItemType t = e.getValue();
                    if (erpTypes.contains(t)) {
                        skippedErp.add(t);
                        continue;
                    }
                    Cell cell = row.getCell(e.getKey());
                    try {
                        m.put(t, number(cell, fmt));
                    } catch (NumberFormatException ex) {
                        errors.add("dòng " + (r + 1) + " cột '" + t.getLabel() + "': '"
                                + fmt.formatCellValue(cell) + "' không phải số");
                    }
                }
                values.put(u.getId(), m);
                labels.put(u.getId(), SalaryFormulaService.employeeLabel(u));
                if (otCol >= 0) {
                    String ot = fmt.formatCellValue(row.getCell(otCol));
                    try {
                        overtime.put(u.getId(), PayrollOvertimeService.parse(ot));
                    } catch (IllegalArgumentException ex) {
                        errors.add("dòng " + (r + 1) + " cột Tăng ca: " + ex.getMessage());
                    }
                }
            }
            if (!skippedErp.isEmpty()) {
                warnings.add("bỏ qua cột đang lấy từ ERP: " + skippedErp);
            }
            if (warnings.size() > 20) {
                int more = warnings.size() - 20;
                warnings.subList(20, warnings.size()).clear();
                warnings.add("... và " + more + " cảnh báo khác");
            }
        }
        return new MonthlyImport(values, overtime, labels, errors, warnings);
    }

    private static MonthlyItemType byLabel(String s) {
        String k = s == null ? "" : s.trim();
        for (MonthlyItemType t : MonthlyItemType.values()) {
            if (t.getLabel().equalsIgnoreCase(k) || t.name().equalsIgnoreCase(k)) {
                return t;
            }
        }
        return null;
    }

    /** Ô số hoặc chữ kiểu VN ("2.000.000"); trống -> null; sai -> NumberFormatException */
    private static Double number(Cell cell, DataFormatter fmt) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC
                || (cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC)) {
            return cell.getNumericCellValue();
        }
        String s = fmt.formatCellValue(cell).trim();
        if (s.isEmpty()) {
            return null;
        }
        String t = s.replace(" ", "");
        if (t.matches("-?\\d{1,3}(\\.\\d{3})+(,\\d+)?")) {
            t = t.replace(".", "").replace(",", ".");
        } else if (t.matches("-?\\d{1,3}(,\\d{3})+(\\.\\d+)?")) {
            t = t.replace(",", "");
        } else {
            t = t.replace(",", ".");
        }
        return Double.valueOf(t);
    }

    // =====================================================================
    // HELPER
    // =====================================================================

    private static final class Styles {
        final CellStyle title;
        final CellStyle note;
        final CellStyle header;
        final CellStyle text;
        final CellStyle money;
        final CellStyle decimal;
        final CellStyle total;
        final CellStyle totalText;

        Styles(Workbook wb) {
            DataFormat df = wb.createDataFormat();

            Font bold = wb.createFont();
            bold.setBold(true);
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            Font grey = wb.createFont();
            grey.setItalic(true);
            grey.setColor(IndexedColors.GREY_50_PERCENT.getIndex());

            title = wb.createCellStyle();
            title.setFont(big);
            note = wb.createCellStyle();
            note.setFont(grey);

            header = bordered(wb);
            header.setFont(bold);
            header.setWrapText(true);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            text = bordered(wb);
            money = bordered(wb);
            money.setDataFormat(df.getFormat("#,##0"));
            decimal = bordered(wb);
            decimal.setDataFormat(df.getFormat("0.##"));

            total = bordered(wb);
            total.setFont(bold);
            total.setDataFormat(df.getFormat("#,##0"));
            total.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            total.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            totalText = bordered(wb);
            totalText.setFont(bold);
            totalText.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            totalText.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        private static CellStyle bordered(Workbook wb) {
            CellStyle s = wb.createCellStyle();
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
            return s;
        }
    }

    private static void cell(Row row, int col, Object v, CellStyle style) {
        Cell c = row.createCell(col);
        if (v instanceof Number n) {
            c.setCellValue(n.doubleValue());
        } else if (v != null) {
            c.setCellValue(String.valueOf(v));
        }
        c.setCellStyle(style);
    }

    private static byte[] bytes(Workbook wb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }
}