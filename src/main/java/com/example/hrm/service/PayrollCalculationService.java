package com.example.hrm.service;

import com.example.hrm.entity.Attendance;
import com.example.hrm.entity.AttendanceConfirmation;
import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.entity.HolidayConfig;
import com.example.hrm.entity.MonthlyItemType;
import com.example.hrm.entity.Payroll;
import com.example.hrm.entity.PayrollAutoMark;
import com.example.hrm.entity.SalaryConfig;
import com.example.hrm.entity.User;
import com.example.hrm.formula.DefinitionParser.Definition;
import com.example.hrm.formula.FormulaEngine;
import com.example.hrm.formula.FormulaException;
import com.example.hrm.formula.SalaryVariables;
import com.example.hrm.formula.Vars;
import com.example.hrm.repository.PayrollAttendanceRepository;
import com.example.hrm.repository.PayrollAutoMarkRepository;
import com.example.hrm.repository.PayrollCalcRepository;
import com.example.hrm.repository.PayrollConfirmationRepository;
import com.example.hrm.repository.PayrollHolidayRepository;
import com.example.hrm.repository.SalaryConfigRepository;
import com.example.hrm.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * =====================================================================
 *  TÍNH LƯƠNG TỰ ĐỘNG THEO CHẤM CÔNG + CÔNG THỨC THEO PHÒNG BAN / NHÂN VIÊN
 * =====================================================================
 *  Với mỗi nhân viên:
 *    0. Nhân viên CHƯA chọn mẫu công thức (hoặc mẫu đang tắt) -> KHÔNG tính.
 *    1. Tham số   : tham số mặc định (tỷ lệ BHXH, giảm trừ...) -> "Tham số" của mẫu
 *                   -> tỉ lệ riêng của nhân viên -> tham số riêng nâng cao
 *                   + 4 TỈ LỆ THƯỞNG của nhân viên (luôn có, mặc định 100%)
 *    2. Biến hệ thống từ chấm công, cấu hình lương, các khoản nhập tay trên phiếu lương
 *    3. Biến trung gian (HR tự đặt)
 *    4. 15 hạng mục lương theo thứ tự trong SalaryVariables.ITEMS
 *       Hạng mục thưởng không tuỳ biến trong mẫu = số nhập (Khoản theo tháng / ERP) × tỉ lệ thưởng của NV
 *  Mọi bước được ghi vào bảng giải trình (trace) để HR đối chiếu.
 *
 *  Khoản PHÁT SINH THEO THÁNG (thưởng, phạt, tạm ứng, rủi ro), thứ tự ưu tiên:
 *    ERP SQL Server (nếu bật & loại đó có câu SQL) -> "Khoản theo tháng" -> số Kế toán nhập trên phiếu import -> 0
 *  Kỳ lương đã CHỐT: không lưu / tính lại phiếu của tháng đó.
 */
@Service
public class PayrollCalculationService {

    private static final Logger log = LoggerFactory.getLogger(PayrollCalculationService.class);

    private final UserRepository userRepository;
    private final PayrollAttendanceRepository attendanceRepository;
    private final PayrollCalcRepository payrollRepository;
    private final SalaryConfigRepository configRepository;
    private final SalaryFormulaService formulaService;
    private final PayrollAutoMarkRepository markRepository;
    private final PayrollMonthlyItemService monthlyService;
    private final ErpBonusService erpService;
    private final PayrollPeriodService periodService;
    private final PayrollHolidayRepository holidayRepository;
    private final PayrollConfirmationRepository confirmationRepository;
    private final PayrollOvertimeService overtimeService;

    /** Quy đổi trạng thái xác nhận công -> số công */
    @Value("${payroll.confirm.values:PRESENT=1,HALF_DAY=0.5,ANNUAL_LEAVE=1,UNPAID_LEAVE=0}")
    private String confirmValues = WorkdayCalculator.DEFAULT_VALUES;

    /** Đồng hồ giờ Việt Nam (đổi được trong test để giả lập "hôm nay") */
    private Clock clock = Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));

    public PayrollCalculationService(UserRepository userRepository,
                                     PayrollAttendanceRepository attendanceRepository,
                                     PayrollCalcRepository payrollRepository,
                                     SalaryConfigRepository configRepository,
                                     SalaryFormulaService formulaService,
                                     PayrollAutoMarkRepository markRepository,
                                     PayrollMonthlyItemService monthlyService,
                                     ErpBonusService erpService,
                                     PayrollPeriodService periodService,
                                     PayrollHolidayRepository holidayRepository,
                                     PayrollConfirmationRepository confirmationRepository,
                                     PayrollOvertimeService overtimeService) {
        this.userRepository = userRepository;
        this.attendanceRepository = attendanceRepository;
        this.payrollRepository = payrollRepository;
        this.configRepository = configRepository;
        this.formulaService = formulaService;
        this.markRepository = markRepository;
        this.monthlyService = monthlyService;
        this.erpService = erpService;
        this.periodService = periodService;
        this.holidayRepository = holidayRepository;
        this.confirmationRepository = confirmationRepository;
        this.overtimeService = overtimeService;
    }

    /** ĐƠN VỊ: phạm vi người đang đăng nhập (null trong kiểm thử = không lọc) */
    private UnitScope unitScope;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUnitScope(UnitScope unitScope) {
        this.unitScope = unitScope;
    }

    private Long scopeUnitId() {
        return unitScope == null ? null : unitScope.scopeUnitId();
    }

    private List<User> scopedUsers() {
        return unitScope == null ? userRepository.findAll() : unitScope.users();
    }

    private Long unitKeyOf(User u) {
        return unitScope == null ? null : unitScope.unitKey(u);
    }

    /** Mọi đơn vị đều đã chốt tháng này (chưa có đơn vị nào: theo bản ghi cũ) */
    private boolean allUnitsLocked(YearMonth ym) {
        Set<Long> locked = periodService.lockedUnits(ym);
        if (locked.isEmpty()) {
            return false;
        }
        Set<Long> all = new HashSet<>();
        for (User u : userRepository.findAll()) {
            all.add(unitKeyOf(u));
        }
        return locked.containsAll(all);
    }

    public User userById(Long id) {
        return id == null ? null : userRepository.findById(id).orElse(null);
    }

    /** Chỉ dùng cho kiểm thử: giả lập "hôm nay" */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Phiếu lương đã có của tháng thì xử lý thế nào */
    public enum ExistingPolicy {
        /** bỏ qua mọi phiếu đã có */
        SKIP,
        /** tính lại mọi phiếu đã có (giữ khoản nhập tay) */
        OVERWRITE,
        /** chỉ tính lại phiếu do hệ thống tự tính; phiếu Kế toán import thì giữ nguyên (job hằng ngày) */
        AUTO_ONLY
    }

    // =====================================================================
    // PHIẾU TẠM TÍNH: cập nhật theo bảng công đến hôm nay (job hằng ngày + nút "Cập nhật ngay")
    // =====================================================================

    /**
     * Tạo / cập nhật phiếu lương tự tính cho cả công ty trong 1 tháng, theo bảng công tính đến hôm nay.
     * Không đụng tới phiếu Kế toán đã import.
     */
    @Transactional
    public CalculationResult refreshAuto(YearMonth ym) {
        // ĐƠN VỊ: chỉ bỏ qua khi MỌI đơn vị đã chốt; đơn vị đã chốt bị bỏ qua từng người trong calculateMonth
        Long viewing = scopeUnitId();
        if (allUnitsLocked(ym) || (viewing != null && periodService.lockedUnits(ym).contains(viewing))) {
            log.info("KY LUONG {} DA CHOT - bo qua cap nhat phieu tam tinh", ym);
            CalculationResult r = new CalculationResult(ym.getMonthValue(), ym.getYear(), null, false);
            r.locked = true;
            return r;
        }
        return calculateMonth(ym.getMonthValue(), ym.getYear(), null, true, ExistingPolicy.AUTO_ONLY);
    }

    // =====================================================================
    // CẢ CÔNG TY / 1 PHÒNG BAN
    // =====================================================================

    /**
     * @param department null/trống = tất cả phòng ban
     * @param save       false = xem trước, không ghi DB
     * @param overwrite  true  = tính lại cả phiếu lương đã có (giữ khoản nhập tay)
     */
    @Transactional
    public CalculationResult calculateMonth(int month, int year, String department, boolean save, boolean overwrite) {
        return calculateMonth(month, year, department, save, overwrite ? ExistingPolicy.OVERWRITE : ExistingPolicy.SKIP);
    }

    @Transactional
    public CalculationResult calculateMonth(int month, int year, String department, boolean save, ExistingPolicy policy) {

        YearMonth ym = YearMonth.of(year, month);
        LocalDate asOf = asOf(ym);

        // ĐƠN VỊ: mỗi đơn vị chốt kỳ riêng
        Long scope = scopeUnitId();
        Set<Long> lockedUnits = periodService.lockedUnits(ym);
        boolean locked = scope != null ? lockedUnits.contains(scope) : allUnitsLocked(ym);
        if (save && locked) {
            periodService.requireOpen(ym);   // ném lỗi "kỳ đã chốt"
        }

        Map<Long, Map<MonthlyItemType, Double>> monthlyByUser = monthlyService.forMonth(ym);
        Map<String, Map<MonthlyItemType, Double>> erpByEmp = erpService.fetchMonth(ym);
        Map<Long, List<PayrollOvertimeService.Line>> overtimeByUser = overtimeService.forMonth(ym);

        // Phiếu Kế toán import được tính lại: các khoản Kế toán nhập trên phiếu được chuyển sang "Khoản theo tháng"
        Map<Long, Map<MonthlyItemType, Double>> adopted = new LinkedHashMap<>();

        Map<Long, PayrollAutoMark> marks = new HashMap<>();
        for (PayrollAutoMark m : markRepository.findByMonthAndYear(month, year)) {
            marks.put(m.getPayrollId(), m);
        }

        Map<Long, List<Attendance>> attendanceByUser = attendanceRepository
                .findByDateBetween(ym.atDay(1), ym.atEndOfMonth())
                .stream()
                .filter(a -> a.getUser() != null)
                .collect(Collectors.groupingBy(a -> a.getUser().getId()));

        Map<LocalDate, HolidayConfig> holidays = holidays(ym);
        Map<String, List<AttendanceConfirmation>> confirmByEmp = new HashMap<>();
        for (AttendanceConfirmation cf : confirmationRepository.findByApprovalStatusAndAttendanceDateBetween(
                WorkdayCalculator.APPROVED, ym.atDay(1), ym.atEndOfMonth())) {
            confirmByEmp.computeIfAbsent(ErpBonusService.normEmp(cf.getEmployeeNo()), k -> new ArrayList<>()).add(cf);
        }

        Map<Long, SalaryConfig> configByUser = configRepository.findAll().stream()
                .filter(c -> c.getUser() != null)
                .collect(Collectors.toMap(c -> c.getUser().getId(), c -> c, (a, b) -> a));

        SalaryFormulaService.Snapshot formulas = formulaService.snapshot();

        CalculationResult result = new CalculationResult(month, year, department, save);
        result.locked = locked;
        result.erpTypes = erpService.erpTypes();
        result.erpError = result.erpTypes.isEmpty() ? null : erpService.getLastError();

        String deptFilter = SalaryFormulaService.normDept(department);

        // 🔒 ĐƠN VỊ: Kế toán đơn vị nào chỉ tính lương nhân viên đơn vị đó (job chạy nền: mọi đơn vị)
        List<User> users = new ArrayList<>(scopedUsers());
        users.sort(Comparator.comparing((User u) -> SalaryFormulaService.normDept(u.getDepartment()))
                .thenComparing(u -> u.getEmployeeNo() == null ? "" : u.getEmployeeNo()));

        for (User user : users) {

            if (!deptFilter.isEmpty() && !deptFilter.equals(SalaryFormulaService.normDept(user.getDepartment()))) {
                continue;
            }

            String label = SalaryFormulaService.employeeLabel(user);
            if (save && lockedUnits.contains(unitKeyOf(user))) {
                result.skippedExisting.add(label + " (kỳ của đơn vị đã chốt)");
                continue;
            }
            List<Attendance> attendances = attendanceByUser.getOrDefault(user.getId(), List.of());
            List<AttendanceConfirmation> confirms = confirmByEmp.getOrDefault(ErpBonusService.normEmp(user.getEmployeeNo()), List.of());

            if (attendances.isEmpty() && confirms.isEmpty()) {
                result.noAttendance.add(label);
                continue;
            }

            SalaryConfig config = configByUser.get(user.getId());

            // Chưa chọn mẫu công thức (hoặc mẫu đang tắt) -> KHÔNG tính
            FormulaChain chain = formulas.chainFor(user, config);
            if (!chain.hasFormula()) {
                result.noFormula.add(label + " (" + SalaryFormulaService.noFormulaReason(config) + ")");
                continue;
            }

            if (config == null && (user.getSalary() == null || user.getSalary() <= 0)) {
                result.noBaseSalary.add(label);
                continue;
            }

            Optional<Payroll> existing = payrollRepository
                    .findFirstByEmployeeIdAndMonthAndYear(user.getId(), month, year);

            if (existing.isPresent()) {
                boolean isAuto = marks.containsKey(existing.get().getId());
                if (policy == ExistingPolicy.SKIP) {
                    result.skippedExisting.add(label);
                    continue;
                }
                if (policy == ExistingPolicy.AUTO_ONLY && !isAuto) {
                    result.skippedImported.add(label);
                    continue;
                }
            }

            // Luôn tính trên BẢN SAO: nếu công thức lỗi giữa chừng, entity đang được Hibernate
            // quản lý không bị sửa dở dang (tránh tự flush dữ liệu nửa vời xuống DB).
            Payroll work = existing
                    .map(PayrollCalculationService::copyManualFields)
                    .orElseGet(Payroll::new);

            EmployeeCalc calc = new EmployeeCalc(user, work, existing.isPresent());
            MonthlyValues mv = new MonthlyValues(
                    erpFor(erpByEmp, user),
                    monthlyByUser.getOrDefault(user.getId(), Map.of()),
                    existing.isPresent() && !marks.containsKey(existing.get().getId()),
                    overtimeByUser.getOrDefault(user.getId(), List.of()));

            try {
                compute(calc, config, new WorkData(attendances, confirms, holidays), ym, chain, asOf, mv);

                if (save) {
                    Payroll target = existing.orElse(work);
                    if (existing.isPresent()) {
                        copyComputedFields(work, target);
                    }
                    target = payrollRepository.save(target);
                    mark(target, user, ym, asOf, marks.get(target.getId()));
                    if (!calc.fromPayslip.isEmpty()) {
                        adopted.put(user.getId(), calc.fromPayslip);
                    }
                }

                result.rows.add(calc);

            } catch (FormulaException | IllegalArgumentException ex) {
                log.warn("LOI TINH LUONG {} : {}", label, ex.getMessage());
                calc.error = ex.getMessage();
                result.errors.add(calc);
            }
        }

        if (!adopted.isEmpty()) {
            monthlyService.adoptFromPayslips(ym, adopted);
        }

        log.info("TINH LUONG {}/{} [{}] save={} policy={} den ngay {} -> {} phieu, {} loi",
                month, year, department, save, policy, asOf, result.rows.size(), result.errors.size());

        return result;
    }

    // =====================================================================
    // GIẢI TRÌNH 1 NHÂN VIÊN (không lưu)
    // =====================================================================

    @Transactional(readOnly = true)
    public EmployeeCalc trace(Long userId, int month, int year) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên id = " + userId));

        YearMonth ym = YearMonth.of(year, month);

        List<Attendance> attendances = attendanceRepository
                .findByUserIdAndDateBetween(userId, ym.atDay(1), ym.atEndOfMonth());

        SalaryConfig config = configRepository.findByUserId(userId).orElse(null);

        Optional<Payroll> existing = payrollRepository.findFirstByEmployeeIdAndMonthAndYear(userId, month, year);
        Payroll payroll = existing.map(PayrollCalculationService::copyManualFields).orElseGet(Payroll::new);

        EmployeeCalc calc = new EmployeeCalc(user, payroll, existing.isPresent());

        boolean imported = existing.isPresent()
                && markRepository.findByPayrollId(existing.get().getId()).isEmpty();
        MonthlyValues mv = new MonthlyValues(
                erpFor(erpService.fetchMonth(ym), user),
                monthlyService.forUser(userId, ym),
                imported,
                overtimeService.forUser(userId, ym));

        try {
            FormulaChain chain = formulaService.snapshot().chainFor(user, config);
            if (!chain.hasFormula()) {
                throw new FormulaException("Không tính lương vì nhân viên "
                        + SalaryFormulaService.noFormulaReason(config)
                        + ". Vào Cấu hình lương NV để chọn mẫu công thức");
            }
            List<AttendanceConfirmation> confirms = user.getEmployeeNo() == null ? List.of()
                    : confirmationRepository.findByEmployeeNoAndApprovalStatusAndAttendanceDateBetween(
                    user.getEmployeeNo(), WorkdayCalculator.APPROVED, ym.atDay(1), ym.atEndOfMonth());
            compute(calc, config, new WorkData(attendances, confirms, holidays(ym)), ym, chain, asOf(ym), mv);
        } catch (FormulaException | IllegalArgumentException ex) {
            calc.error = ex.getMessage();
        }

        return calc;
    }

    /**
     * Thông tin "tạm tính đến ngày ..." cho phiếu XEM TRƯỚC (không lưu DB) - dùng hiện nhãn trên thẻ lương.
     */
    public PayrollAutoMark previewMark(YearMonth ym) {
        PayrollAutoMark m = new PayrollAutoMark();
        m.setMonth(ym.getMonthValue());
        m.setYear(ym.getYear());
        m.setCalcUpTo(asOf(ym));
        m.setCalculatedAt(LocalDateTime.now(clock));
        return m;
    }

    // =====================================================================
    // LÕI TÍNH TOÁN
    // =====================================================================

    private void compute(EmployeeCalc c,
                         SalaryConfig cfg,
                         WorkData work,
                         YearMonth ym,
                         FormulaChain chain,
                         LocalDate asOf,
                         MonthlyValues mv) {

        User user = c.user;
        Payroll p = c.payroll;
        Vars v = new Vars();
        // LỊCH LÀM VIỆC ĐƠN VỊ (HCNS cấu hình): ngày nghỉ / ngày làm sáng trong tuần
        CompanyUnit workUnit = unitScope == null ? null : unitScope.unitOf(user);

        // ---------------- 1. THAM SỐ ----------------

        for (SalaryVariables.Param prm : SalaryVariables.BUILTIN_PARAMS) {
            v.put(prm.name(), prm.value());
            c.add(TraceRow.PARAM, prm.name(), prm.label(), FormulaChain.SRC_BUILTIN, null, prm.value());
        }

        for (Definition d : chain.params()) {
            double val = eval(d.expression(), v, d.where());
            v.put(d.name(), val);
            c.add(TraceRow.PARAM, d.name(), SalaryVariables.label(d.name()), d.source(), d.expression(), val);
        }

        // Tỉ lệ thưởng của NV (mặc định 100%): luôn có, mẫu không cần khai báo.
        // Mẫu đã khai báo tên này trong "Tham số" thì vòng lặp trên đã tính (kèm tỉ lệ riêng của NV) -> bỏ qua.
        Set<String> declared = new HashSet<>();
        for (Definition d : chain.params()) {
            declared.add(d.name().toLowerCase(Locale.ROOT));
        }
        Map<String, String> tyLeNv = cfg != null && cfg.getTyLe() != null ? cfg.getTyLe() : Map.of();
        for (MonthlyItemType t : bonusTypes()) {
            String name = bonusRateName(t);
            if (declared.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String raw = rateOf(tyLeNv, name);
            if (raw != null && !raw.isBlank()) {
                double val = eval(raw, v, "Tỉ lệ " + t.getLabel());
                v.put(name, val);
                c.add(TraceRow.PARAM, name, "Tỉ lệ " + t.getLabel(), SRC_BONUS_RATE, raw, val);
            } else {
                v.put(name, 1);
                c.add(TraceRow.PARAM, name, "Tỉ lệ " + t.getLabel(), "Mặc định (100%)", null, 1);
            }
        }
        // Cách tính thưởng riêng của NV: số nhập × tỉ lệ (mặc định) / số tiền cố định / công thức riêng
        Map<MonthlyItemType, BonusRule> bonusRules = bonusRules(tyLeNv);

        // ---------------- 2. BIẾN HỆ THỐNG ----------------

        double congChuan = v.getOrZero("congChuanCoDinh") > 0
                ? v.getOrZero("congChuanCoDinh")
                : countWorkingDays(ym, workUnit);

        double gioChuanNgay = v.getOrZero("gioChuanNgay");
        double gioNghiTrua = v.getOrZero("gioNghiTrua");
        double otToiThieuPhut = v.getOrZero("otToiThieuPhut");

        // Công: xác nhận công đã duyệt -> công đã xác nhận -> công máy chấm; ngày lễ = 1 công hưởng lương
        WorkdayCalculator.Result w = WorkdayCalculator.compute(work.attendances(), work.confirmations(), work.holidays(),
                WorkdayCalculator.parseValues(confirmValues), ym.atDay(1), asOf, gioChuanNgay, gioNghiTrua, otToiThieuPhut, workUnit);
        double congHL = w.congHL;
        if (!w.unknownStatuses.isEmpty()) {
            log.warn("XAC NHAN CONG: trang thai chua quy doi {} (khai bao payroll.confirm.values)", w.unknownStatuses);
        }

        double luongCoBan = firstPositive(cfg != null ? cfg.getLuongCoBan() : null, user.getSalary());
        double luongDongBhxh = firstPositive(cfg != null ? cfg.getLuongDongBhxh() : null, luongCoBan);
        double luongNgay = congChuan > 0 ? luongCoBan / congChuan : 0;
        double luongGio = gioChuanNgay > 0 ? luongNgay / gioChuanNgay : 0;

        String srcCong = SalaryVariables.G_CONG;
        String srcCfg = cfg != null ? SalaryVariables.G_LUONG : "users.salary (chưa có cấu hình lương)";

        // Tháng đang chạy: chỉ tính số ngày làm việc đã trôi qua (để "số ngày nghỉ" không bị tính cả những ngày chưa tới)
        double congChuanDenNay = Math.min(congChuan, countWorkingDays(ym, asOf, workUnit));
        String srcDenNay = asOf.isBefore(ym.atEndOfMonth()) ? srcCong + " đến " + asOf : srcCong;

        input(c, v, "congChuan", congChuan, srcCong);
        input(c, v, "congHL", round2(congHL), srcDenNay);
        input(c, v, "congChuanDenNay", congChuanDenNay, srcDenNay);
        input(c, v, "soNgayNghi", round2(Math.max(0, congChuanDenNay - congHL)), srcDenNay);
        input(c, v, "tyLeCong", congChuan > 0 ? Math.min(1, congHL / congChuan) : 0, srcCong);
        input(c, v, "soNgayDiLam", w.soNgayDiLam, srcCong);
        input(c, v, "gioOtThuong", w.gioOtThuong, srcCong);
        input(c, v, "gioOtChuNhat", w.gioOtChuNhat, srcCong);
        input(c, v, "gioOtTong", w.gioOtThuong + w.gioOtChuNhat, srcCong);
        input(c, v, "congLe", w.congLe, SRC_HOLIDAY);
        input(c, v, "gioLamNgayLe", w.gioLamNgayLe, SRC_HOLIDAY);
        input(c, v, "gioLeQuyDoi", round2(w.gioLeQuyDoi), SRC_HOLIDAY);
        input(c, v, "soNgayPhep", w.soNgayPhep, SRC_CONFIRM);
        input(c, v, "soNgayKhongLuong", w.soNgayKhongLuong, SRC_CONFIRM);
        input(c, v, "soNgayXacNhan", w.soNgayXacNhan, SRC_CONFIRM);
        String srcOt = mv.overtime().isEmpty() ? "Chưa nhập tăng ca" : SRC_OVERTIME + ": " + PayrollOvertimeService.format(mv.overtime());
        input(c, v, "coNhapTangCa", mv.overtime().isEmpty() ? 0 : 1, srcOt);
        input(c, v, "gioTangCaNhap", PayrollOvertimeService.totalHours(mv.overtime()), srcOt);
        input(c, v, "gioTangCaQuyDoi", PayrollOvertimeService.totalConverted(mv.overtime()), srcOt);

        input(c, v, "luongCoBan", luongCoBan, srcCfg);
        input(c, v, "luongDongBhxh", luongDongBhxh, srcCfg);
        input(c, v, "luongNgay", luongNgay, srcCfg);
        input(c, v, "luongGio", luongGio, srcCfg);
        input(c, v, "mucTrachNhiem", cfg != null ? nz(cfg.getMucTrachNhiem()) : 0, srcCfg);
        input(c, v, "heSoTrachNhiem", cfg != null ? nz(cfg.getHeSoTrachNhiem()) : 0, srcCfg);
        input(c, v, "mucPhuCapLevel", cfg != null ? nz(cfg.getPhuCapLevel()) : 0, srcCfg);
        input(c, v, "mucPhuCapThamNien", cfg != null ? nz(cfg.getPhuCapThamNien()) : 0, srcCfg);
        input(c, v, "mucPhuCapKiemNhiem", cfg != null ? nz(cfg.getPhuCapKiemNhiem()) : 0, srcCfg);
        input(c, v, "mucThamNien5Nam", cfg != null ? nz(cfg.getThamNien5Nam()) : 0, srcCfg);
        input(c, v, "kyQuy", cfg != null ? nz(cfg.getKyQuy()) : 0, srcCfg);
        input(c, v, "soNguoiPhuThuoc", cfg != null && cfg.getSoNguoiPhuThuoc() != null ? cfg.getSoNguoiPhuThuoc() : 0, srcCfg);
        input(c, v, "thamGiaBhxh", cfg == null || !Boolean.FALSE.equals(cfg.getThamGiaBhxh()) ? 1 : 0, srcCfg);
        input(c, v, "thamGiaCongDoan", cfg == null || !Boolean.FALSE.equals(cfg.getThamGiaCongDoan()) ? 1 : 0, srcCfg);

        // Khoản nhập tay trên phiếu lương lưu theo đơn vị phiếu (nghìn đồng) -> đổi ra ĐỒNG để tính
        double dv = donVi(v);

        // Khoản phát sinh theo tháng: ERP -> Khoản theo tháng -> phiếu Kế toán import -> (Rủi ro: mức trong cấu hình NV) -> 0
        Map<MonthlyItemType, Double> monthlyVal = new EnumMap<>(MonthlyItemType.class);
        Set<MonthlyItemType> entered = EnumSet.noneOf(MonthlyItemType.class);   // tháng này CÓ nhập số
        for (MonthlyItemType t : MonthlyItemType.values()) {
            String src;
            double val;
            Double manual = mv.importedPayroll ? payrollValue(p, t) : null;
            if (mv.erp.containsKey(t)) {
                val = mv.erp.get(t);
                src = SRC_ERP;
                entered.add(t);
            } else if (mv.items.containsKey(t)) {
                val = mv.items.get(t);
                src = SRC_MONTHLY;
                entered.add(t);
            } else if (manual != null) {
                val = manual * dv;
                src = SalaryVariables.G_NHAP_TAY;
                c.fromPayslip.put(t, val);
                entered.add(t);
            } else if (t == MonthlyItemType.RUI_RO && cfg != null && cfg.getRuiRo() != null) {
                val = cfg.getRuiRo();
                src = SRC_CFG_MONTHLY;
            } else {
                val = 0;
                src = "Chưa nhập (= 0)";
            }
            monthlyVal.put(t, val);
            input(c, v, t.getInputVariable(), val, src);
            // tên cũ (thuongDichVu...) = số nhập, cho đến khi hạng mục thưởng cùng tên tính lại
            v.put(t.getVariable(), val);
        }
        v.put("tongThuong", monthlyVal.get(MonthlyItemType.THUONG_DICH_VU)
                + monthlyVal.get(MonthlyItemType.THUONG_CHINH_SACH)
                + monthlyVal.get(MonthlyItemType.THUONG_TVBH)
                + monthlyVal.get(MonthlyItemType.THUONG_KHAC));

        // ---------------- 3. BIẾN TRUNG GIAN ----------------

        for (Definition d : chain.intermediates()) {
            double val = eval(d.expression(), v, d.where());
            v.put(d.name(), val);
            c.add(TraceRow.INTERMEDIATE, d.name(), null, d.source(), d.expression(), val);
        }

        // ---------------- 4. HẠNG MỤC LƯƠNG ----------------

        Map<String, Double> out = new LinkedHashMap<>();

        for (SalaryVariables.Item it : SalaryVariables.ITEMS) {
            // Trước "Tổng thu nhập": tongThuong = tổng 4 hạng mục thưởng (đã tính theo công thức / tỉ lệ của NV)
            if ("tongThuNhap".equals(it.code())) {
                double tong = out.getOrDefault("thuongDichVu", 0.0) + out.getOrDefault("thuongChinhSach", 0.0)
                        + out.getOrDefault("thuongTuVanBanHang", 0.0) + out.getOrDefault("banLanhDao", 0.0);
                v.put("tongThuong", tong);
                c.add(TraceRow.ITEM, "tongThuong", "Tổng 4 khoản thưởng", "= II.1 + II.2 + II.3 + II.4",
                        "thuongDichVu + thuongChinhSach + thuongTuVanBanHang + banLanhDao", tong);
            }
            // Khoản nhập CỐ ĐỊNH trong cấu hình lương NV (BHXH, công đoàn, thuế, T.Nhiệm được nhận) thay cho công thức
            // Lương thêm giờ: có nhập tăng ca trong tháng -> lương 1 giờ × Σ(giờ × hệ số), thay công thức
            if ("luongThemGio".equals(it.code())
                    && (!mv.overtime().isEmpty() || v.getOrZero("tuDongTinhTangCa") == 0)) {
                boolean manual = !mv.overtime().isEmpty();
                double val = manual ? Math.round(v.getOrZero("luongGio") * v.getOrZero("gioTangCaQuyDoi")) : 0;
                v.put(it.code(), val);
                out.put(it.code(), val);
                c.add(TraceRow.ITEM, it.code(), it.label(),
                        manual ? SRC_OVERTIME + ": " + PayrollOvertimeService.format(mv.overtime())
                                : "Không tự tính tăng ca theo giờ vào/ra (tuDongTinhTangCa = 0), chưa nhập tăng ca",
                        manual ? "luongGio * gioTangCaQuyDoi" : "0", val);
                continue;
            }
            Double fixed = fixedOverride(cfg, it.code());
            if (fixed != null) {
                double val = Math.round(fixed);
                v.put(it.code(), val);
                out.put(it.code(), val);
                c.add(TraceRow.ITEM, it.code(), it.label(), SRC_FIXED, "(số cố định)", val);
                continue;
            }
            // Hạng mục thưởng: cách tính riêng của NV (cố định / công thức) ưu tiên hơn mẫu;
            // còn lại: mẫu có công thức thì theo mẫu, không thì = số nhập (Khoản theo tháng / ERP) × tỉ lệ thưởng của NV
            MonthlyItemType bonusType = bonusTypeOfItem(it.code());
            BonusRule rule = bonusType == null ? null : bonusRules.get(bonusType);
            FormulaChain.Resolved r;
            if (rule != null && rule.mode() == BONUS_FIXED) {
                boolean over = entered.contains(bonusType);
                r = over
                        ? new FormulaChain.Resolved(bonusType.getInputVariable(), SRC_BONUS_FIXED_OVER)
                        : new FormulaChain.Resolved(String.valueOf(Math.round(rule.fixed())), SRC_BONUS_FIXED);
            } else if (rule != null && rule.mode() == BONUS_FORMULA) {
                // biến dùng trong công thức riêng của NV
                v.put("nhap", v.getOrZero(bonusType.getInputVariable()));
                v.put("tyLe", v.getOrZero(bonusRateName(bonusType)));
                v.put("coDinh", rule.fixed());
                r = new FormulaChain.Resolved(rule.formula(), SRC_BONUS_FORMULA);
            } else if (it.optional() && chain.item(it.code()) == null) {
                MonthlyItemType bt = bonusType;
                r = bt != null
                        ? new FormulaChain.Resolved(bt.getInputVariable() + " * " + bonusRateName(bt), SRC_BONUS_DEFAULT)
                        : new FormulaChain.Resolved(it.defaultFormula(), SRC_BONUS_DEFAULT);
            } else {
                r = chain.requireItem(it);
            }
            double val = Math.round(eval(r.expression(), v, "[" + r.source() + "] " + it.label()));
            v.put(it.code(), val);
            out.put(it.code(), val);
            c.add(TraceRow.ITEM, it.code(), it.label(), r.source(), r.expression(), val);
        }

        // ---------------- 5. GHI VÀO PHIẾU LƯƠNG ----------------

        p.setEmployee(user);
        p.setMonth(ym.getMonthValue());
        p.setYear(ym.getYear());
        p.setImportDate(LocalDate.now(clock));

        // Theo quy ước phiếu lương / file Excel đang dùng:
        //   - tiền lưu theo đơn vị phiếu (nghìn đồng)
        //   - cột congChuan = "Lương cơ bản" trên thẻ lương
        //   - HS trách nhiệm lưu theo % (100 = 100%)
        p.setCongChuan(unit(luongCoBan, dv));
        p.setCongHL(round2(congHL));
        p.setHeSoTrachNhiem(v.getOrZero("heSoTrachNhiem") > 0 ? v.getOrZero("heSoTrachNhiem") * 100 : null);
        p.setKyQuy(nullIfZero(unit(v.getOrZero("kyQuy"), dv)));
        p.setGiamTruBanThan(unit(v.getOrZero("giamTruBanThan"), dv));

        p.setLuongNgayCong(unit(out.get("luongNgayCong"), dv));
        p.setLuongThemGio(unit(out.get("luongThemGio"), dv));
        p.setTrachNhiemDuocNhan(nullIfZero(unit(out.get("trachNhiem"), dv)));
        p.setPhuCapLevel(nullIfZero(unit(out.get("phuCapLevel"), dv)));
        p.setPhuCapThamNien(nullIfZero(unit(out.get("phuCapThamNien"), dv)));
        p.setPhuCapKiemNhiem(nullIfZero(unit(out.get("phuCapKiemNhiem"), dv)));
        p.setThamNien5Nam(nullIfZero(unit(out.get("thamNien5Nam"), dv)));
        p.setLuongCoBanThamNienPhuCap(unit(out.get("luongCbThamNienPhuCap"), dv));
        p.setNetSalary(unit(out.get("tongThuNhap"), dv));
        p.setBhxh(unit(out.get("bhxh"), dv));
        p.setCongDoan(unit(out.get("congDoan"), dv));
        p.setGiamTruGiaCanh(unit(out.get("giamTruGiaCanh"), dv));
        p.setSauGiamTru(unit(out.get("sauGiamTru"), dv));
        p.setThueTNCN(unit(out.get("thueTNCN"), dv));
        p.setThucNhan(unit(out.get("thucNhan"), dv));

        // Khoản theo tháng đang áp dụng -> ghi lên phiếu để thẻ lương hiện đúng dòng
        // (thưởng: lấy kết quả hạng mục thưởng = số nhập × tỉ lệ của NV, hoặc công thức riêng của mẫu)
        for (MonthlyItemType t : MonthlyItemType.values()) {
            double val = t.isBonus() && out.containsKey(t.getVariable()) ? out.get(t.getVariable()) : monthlyVal.get(t);
            setPayrollValue(p, t, nullIfZero(unit(val, dv)));
        }
    }

    // =====================================================================
    // HELPER
    // =====================================================================

    private static double eval(String expr, Vars v, String where) {
        try {
            return FormulaEngine.evaluate(expr, v);
        } catch (FormulaException e) {
            throw new FormulaException(where + ": " + e.getMessage() + "  | công thức: " + expr);
        }
    }

    private static void input(EmployeeCalc c, Vars v, String name, double value, String source) {
        v.put(name, value);
        c.add(TraceRow.INPUT, name, SalaryVariables.label(name), source, null, value);
    }

    /** Ngày tính bảng công: tháng đang chạy = hôm nay; tháng đã qua = cuối tháng; tháng sau = trước ngày 1 */
    LocalDate asOf(YearMonth ym) {
        LocalDate today = today();
        LocalDate end = ym.atEndOfMonth();
        if (today.isAfter(end)) {
            return end;
        }
        if (today.isBefore(ym.atDay(1))) {
            return ym.atDay(1).minusDays(1);
        }
        return today;
    }

    /** Ghi / cập nhật dấu "phiếu tự tính" */
    private void mark(Payroll payroll, User user, YearMonth ym, LocalDate asOf, PayrollAutoMark existing) {
        PayrollAutoMark m = existing != null ? existing : new PayrollAutoMark();
        m.setPayrollId(payroll.getId());
        m.setUserId(user.getId());
        m.setMonth(ym.getMonthValue());
        m.setYear(ym.getYear());
        m.setCalcUpTo(asOf);
        m.setCalculatedAt(LocalDateTime.now(clock));
        markRepository.save(m);
    }

    static final String SRC_FIXED = "Nhập cố định (cấu hình lương NV)";
    static final String SRC_MONTHLY = "Khoản theo tháng";
    static final String SRC_ERP = "ERP SQL Server";
    static final String SRC_BONUS_DEFAULT = "Không tuỳ biến trong mẫu = số nhập (Khoản theo tháng / ERP) × tỉ lệ thưởng của NV";
    static final String SRC_OVERTIME = "Tăng ca nhập (Khoản theo tháng)";
    static final String SRC_HOLIDAY = "Ngày lễ (holiday_config)";
    static final String SRC_CONFIRM = "Xác nhận công đã duyệt";
    /** bắt đầu bằng "Tỉ lệ riêng" -> dòng được tô vàng trên trang giải trình */
    static final String SRC_BONUS_RATE = "Tỉ lệ riêng của NV (tỉ lệ thưởng)";

    // =====================================================================
    // TỈ LỆ THƯỞNG CỦA NHÂN VIÊN
    // =====================================================================

    /** 4 loại thưởng (II.1 - II.4) theo thứ tự trên thẻ lương */
    public static List<MonthlyItemType> bonusTypes() {
        return Arrays.stream(MonthlyItemType.values()).filter(MonthlyItemType::isBonus).collect(Collectors.toList());
    }

    /** Tên tỉ lệ thưởng của NV: tyLeThuongDichVu, tyLeThuongChinhSach, tyLeThuongTuVanBanHang, tyLeBanLanhDao */
    public static String bonusRateName(MonthlyItemType t) {
        String v = t.getVariable();
        return "tyLe" + Character.toUpperCase(v.charAt(0)) + v.substring(1);
    }

    /** Loại thưởng ứng với 1 hạng mục lương (thuongDichVu -> THUONG_DICH_VU); null nếu không phải thưởng */
    private static MonthlyItemType bonusTypeOfItem(String itemCode) {
        for (MonthlyItemType t : bonusTypes()) {
            if (t.getVariable().equals(itemCode)) {
                return t;
            }
        }
        return null;
    }

    // ---------- cách tính thưởng riêng của NV (lưu trong SalaryConfig.tyLe) ----------

    public static final int BONUS_RATE = 0;      // số nhập tháng × tỉ lệ (mặc định)
    public static final int BONUS_FIXED = 1;     // số tiền cố định, tháng có nhập thì dùng số nhập
    public static final int BONUS_FORMULA = 2;   // công thức riêng: nhap, tyLe, coDinh + mọi biến hệ thống

    static final String SRC_BONUS_FIXED = "Nhập cố định (cấu hình lương NV): thưởng cố định";
    static final String SRC_BONUS_FIXED_OVER = "Khoản theo tháng: số nhập tháng này (ghi đè thưởng cố định)";
    static final String SRC_BONUS_FORMULA = "Tham số riêng: công thức thưởng của NV";

    /** Cách tính thưởng của 1 NV cho 1 khoản */
    public record BonusRule(int mode, double fixed, String formula) {
    }

    /** "ThuongDichVu" (dùng ghép tên khoá) */
    private static String bonusBase(MonthlyItemType t) {
        String v = t.getVariable();
        return Character.toUpperCase(v.charAt(0)) + v.substring(1);
    }

    public static String bonusModeKey(MonthlyItemType t) {
        return "cachTinh" + bonusBase(t);
    }

    public static String bonusFixedKey(MonthlyItemType t) {
        return "coDinh" + bonusBase(t);
    }

    public static String bonusFormulaKey(MonthlyItemType t) {
        return "congThuc" + bonusBase(t);
    }

    /** Đọc cách tính thưởng từ tỉ lệ riêng của NV; không có / sai -> số nhập × tỉ lệ */
    public static Map<MonthlyItemType, BonusRule> bonusRules(Map<String, String> tyLe) {
        Map<MonthlyItemType, BonusRule> m = new EnumMap<>(MonthlyItemType.class);
        Map<String, String> src = tyLe == null ? Map.of() : tyLe;
        for (MonthlyItemType t : bonusTypes()) {
            String modeRaw = rateOf(src, bonusModeKey(t));
            int mode = "1".equals(trimOrEmpty(modeRaw)) ? BONUS_FIXED
                    : "2".equals(trimOrEmpty(modeRaw)) ? BONUS_FORMULA : BONUS_RATE;
            double fixed = 0;
            try {
                String f = rateOf(src, bonusFixedKey(t));
                fixed = f == null || f.isBlank() ? 0 : Double.parseDouble(f.trim());
            } catch (NumberFormatException ignored) {
                // số sai -> 0
            }
            String formula = trimOrEmpty(rateOf(src, bonusFormulaKey(t)));
            if (mode == BONUS_FORMULA && formula.isEmpty()) {
                mode = BONUS_RATE;
            }
            m.put(t, new BonusRule(mode, fixed, formula));
        }
        return m;
    }

    /** Mô tả ngắn cách tính thưởng (hiện ở bảng Cấu hình / Khoản theo tháng); "" = mặc định 100% */
    public static String bonusRuleText(Map<String, String> tyLe, MonthlyItemType t) {
        BonusRule r = bonusRules(tyLe).get(t);
        if (r.mode() == BONUS_FIXED) {
            return "cố định " + String.format(Locale.ROOT, "%,.0f", r.fixed()).replace(',', '.') + " (nhập để ghi đè)";
        }
        if (r.mode() == BONUS_FORMULA) {
            return "công thức: " + r.formula();
        }
        String rate = tyLe == null ? null : rateOf(tyLe, bonusRateName(t));
        return rate == null || rate.isBlank() ? "" : "× " + rate.trim();
    }

    private static String trimOrEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /** Lấy tỉ lệ đã lưu, tên không phân biệt hoa thường */
    private static String rateOf(Map<String, String> m, String name) {
        String v = m.get(name);
        if (v == null) {
            for (Map.Entry<String, String> e : m.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
        }
        return v;
    }

    /** Dữ liệu công của 1 nhân viên trong tháng */
    record WorkData(List<Attendance> attendances,
                    List<AttendanceConfirmation> confirmations,
                    Map<LocalDate, HolidayConfig> holidays) {
    }

    /** Ngày lễ trong tháng (ngày -> cấu hình) */
    private Map<LocalDate, HolidayConfig> holidays(YearMonth ym) {
        Map<LocalDate, HolidayConfig> m = new HashMap<>();
        for (HolidayConfig h : holidayRepository.findByHolidayDateBetween(ym.atDay(1), ym.atEndOfMonth())) {
            if (h.getHolidayDate() != null) {
                m.put(h.getHolidayDate(), h);
            }
        }
        return m;
    }

    static final String SRC_CFG_MONTHLY = "Cấu hình lương NV (mức hằng tháng)";

    /** Các khoản theo tháng của 1 nhân viên (đồng) */
    record MonthlyValues(Map<MonthlyItemType, Double> erp,
                         Map<MonthlyItemType, Double> items,
                         boolean importedPayroll,
                         List<PayrollOvertimeService.Line> overtime) {
    }

    /** Thưởng ERP của 1 nhân viên: khớp theo mã NV, không có thì theo mã lương */
    private static Map<MonthlyItemType, Double> erpFor(Map<String, Map<MonthlyItemType, Double>> erp, User u) {
        if (erp.isEmpty()) {
            return Map.of();
        }
        Map<MonthlyItemType, Double> m = erp.get(ErpBonusService.normEmp(u.getEmployeeNo()));
        if (m == null && u.getSalaryCode() != null) {
            m = erp.get(ErpBonusService.normEmp(u.getSalaryCode()));
        }
        return m == null ? Map.of() : m;
    }

    /** Giá trị 1 khoản theo tháng trên phiếu lương (nghìn đồng) */
    public static Double payrollValue(Payroll p, MonthlyItemType t) {
        return switch (t) {
            case THUONG_DICH_VU -> p.getThuongDichVu();
            case THUONG_CHINH_SACH -> p.getThuongChinhSach();
            case THUONG_TVBH -> p.getThuongTuVanBanHang();
            case THUONG_KHAC -> p.getBanLanhDao();
            case PHAT_VI_PHAM -> p.getPhatViPham();
            case TAM_UNG -> p.getTamUngLuong();
            case PHAT_KD_DV -> p.getPhongChongThienTai();
            case RUI_RO -> p.getRuiRo();
        };
    }

    static void setPayrollValue(Payroll p, MonthlyItemType t, Double v) {
        switch (t) {
            case THUONG_DICH_VU -> p.setThuongDichVu(v);
            case THUONG_CHINH_SACH -> p.setThuongChinhSach(v);
            case THUONG_TVBH -> p.setThuongTuVanBanHang(v);
            case THUONG_KHAC -> p.setBanLanhDao(v);
            case PHAT_VI_PHAM -> p.setPhatViPham(v);
            case TAM_UNG -> p.setTamUngLuong(v);
            case PHAT_KD_DV -> p.setPhongChongThienTai(v);
            case RUI_RO -> p.setRuiRo(v);
        }
    }

    /** Số nhập cố định trong cấu hình NV cho 1 hạng mục; null = dùng công thức */
    private static Double fixedOverride(SalaryConfig cfg, String itemCode) {
        if (cfg == null) {
            return null;
        }
        return switch (itemCode) {
            case "trachNhiem" -> cfg.getTrachNhiemCoDinh();
            case "bhxh" -> cfg.getBhxhCoDinh();
            case "congDoan" -> cfg.getCongDoanCoDinh();
            case "thueTNCN" -> cfg.getThueTncnCoDinh();
            default -> null;
        };
    }

    private static double donVi(Vars v) {
        double d = v.getOrZero("donViPhieuLuong");
        return d > 0 ? d : 1;
    }

    /** đồng -> đơn vị phiếu (nghìn đồng), giữ 3 số lẻ để không mất tiền lẻ */
    private static Double unit(Double dong, double dv) {
        if (dong == null) {
            return null;
        }
        return Math.round(dong) / dv;
    }

    /**
     * CÔNG CHUẨN theo LỊCH LÀM VIỆC của đơn vị: ngày làm cả ngày = 1, ngày làm sáng = 0,5, ngày nghỉ = 0.
     * Đơn vị null / chưa cấu hình = trừ Chủ nhật như bản cũ.
     */
    public static double countWorkingDays(YearMonth ym, CompanyUnit unit) {
        return countWorkingDays(ym, ym.atEndOfMonth(), unit);
    }

    /** Công chuẩn theo lịch đơn vị từ đầu tháng đến hết ngày 'to' */
    public static double countWorkingDays(YearMonth ym, LocalDate to, CompanyUnit unit) {
        double count = 0;
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            LocalDate day = ym.atDay(d);
            if (day.isAfter(to)) {
                break;
            }
            count += CompanyUnit.dayWeight(unit, day.getDayOfWeek());
        }
        return count;
    }

    /** Để hiện trên thẻ lương: 26 (số nguyên) hoặc 24.5 */
    public static Number workingDaysNumber(YearMonth ym, CompanyUnit unit) {
        double d = countWorkingDays(ym, unit);
        return d == Math.rint(d) ? (Number) (int) d : (Number) d;
    }

    /** Số ngày làm việc (trừ Chủ nhật) từ đầu tháng đến hết ngày 'to' (tính cả 'to') */
    public static int countWorkingDays(YearMonth ym, LocalDate to) {
        int count = 0;
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            LocalDate day = ym.atDay(d);
            if (day.isAfter(to)) {
                break;
            }
            if (day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return count;
    }

    /** Số ngày làm việc trong tháng: các ngày trừ Chủ nhật (= "Công chuẩn = NTT - CN" trên thẻ lương) */
    public static int countWorkingDays(YearMonth ym) {
        int count = 0;
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            if (ym.atDay(d).getDayOfWeek() != DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return count;
    }

    /**
     * Giờ thêm của 1 ngày chấm công.
     * Ngày thường: phần vượt gioChuanNgay. Chủ nhật: toàn bộ giờ làm.
     * Làm tròn xuống theo bội số otToiThieuPhut, dưới mức đó thì không tính.
     */
    static double overtimeHours(Attendance a, boolean chuNhat,
                                double gioChuanNgay, double gioNghiTrua, double otToiThieuPhut) {

        if (a.getCheckInTime() == null || a.getCheckOutTime() == null) {
            return 0;
        }

        long minutes = Duration.between(a.getCheckInTime(), a.getCheckOutTime()).toMinutes();
        if (minutes <= 0) {
            return 0;
        }

        double worked = minutes / 60.0;
        if (worked > 5) {
            worked -= gioNghiTrua;
        }

        double otMinutes = (chuNhat ? worked : worked - gioChuanNgay) * 60;

        if (otMinutes <= 0 || otMinutes < otToiThieuPhut) {
            return 0;
        }
        if (otToiThieuPhut > 0) {
            otMinutes = Math.floor(otMinutes / otToiThieuPhut) * otToiThieuPhut;
        }
        return otMinutes / 60.0;
    }

    static Payroll copyManualFields(Payroll src) {
        Payroll p = new Payroll();
        p.setPosition(src.getPosition());
        p.setThuongDichVu(src.getThuongDichVu());
        p.setThuongChinhSach(src.getThuongChinhSach());
        p.setThuongTuVanBanHang(src.getThuongTuVanBanHang());
        p.setBanLanhDao(src.getBanLanhDao());
        p.setTamUngLuong(src.getTamUngLuong());
        p.setRuiRo(src.getRuiRo());
        p.setPhongChongThienTai(src.getPhongChongThienTai());
        p.setPhatViPham(src.getPhatViPham());
        p.setGhiChu(src.getGhiChu());
        return p;
    }

    /** Chép các trường do máy tính ra sang phiếu lương đã có (giữ nguyên mọi trường khác). */
    static void copyComputedFields(Payroll from, Payroll to) {
        to.setEmployee(from.getEmployee());
        to.setMonth(from.getMonth());
        to.setYear(from.getYear());
        to.setImportDate(from.getImportDate());
        to.setCongChuan(from.getCongChuan());
        to.setCongHL(from.getCongHL());
        to.setHeSoTrachNhiem(from.getHeSoTrachNhiem());
        to.setKyQuy(from.getKyQuy());
        to.setGiamTruBanThan(from.getGiamTruBanThan());
        to.setLuongNgayCong(from.getLuongNgayCong());
        to.setLuongThemGio(from.getLuongThemGio());
        to.setTrachNhiemDuocNhan(from.getTrachNhiemDuocNhan());
        to.setPhuCapLevel(from.getPhuCapLevel());
        to.setPhuCapThamNien(from.getPhuCapThamNien());
        to.setPhuCapKiemNhiem(from.getPhuCapKiemNhiem());
        to.setThamNien5Nam(from.getThamNien5Nam());
        to.setLuongCoBanThamNienPhuCap(from.getLuongCoBanThamNienPhuCap());
        to.setNetSalary(from.getNetSalary());
        to.setBhxh(from.getBhxh());
        to.setCongDoan(from.getCongDoan());
        to.setGiamTruGiaCanh(from.getGiamTruGiaCanh());
        to.setSauGiamTru(from.getSauGiamTru());
        to.setThueTNCN(from.getThueTNCN());
        to.setThucNhan(from.getThucNhan());
        // khoản theo tháng đang áp dụng (đã gồm số Kế toán nhập nếu không có nguồn nào khác)
        for (MonthlyItemType t : MonthlyItemType.values()) {
            setPayrollValue(to, t, payrollValue(from, t));
        }
    }

    private static double nz(Double v) {
        return v == null ? 0 : v;
    }

    private static double firstPositive(Double a, Double b) {
        if (a != null && a > 0) {
            return a;
        }
        if (b != null && b > 0) {
            return b;
        }
        return 0;
    }

    private static Double nullIfZero(Double v) {
        return v == null || v == 0 ? null : v;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    // =====================================================================
    // KẾT QUẢ
    // =====================================================================

    public static class TraceRow {

        public static final String PARAM = "1. Tham số";
        public static final String INPUT = "2. Biến hệ thống";
        public static final String INTERMEDIATE = "3. Biến trung gian";
        public static final String ITEM = "4. Hạng mục lương";

        private final String group;
        private final String name;
        private final String label;
        private final String source;
        private final String expression;
        private final double value;

        public TraceRow(String group, String name, String label, String source, String expression, double value) {
            this.group = group;
            this.name = name;
            this.label = label;
            this.source = source;
            this.expression = expression;
            this.value = value;
        }

        public String getGroup() {
            return group;
        }

        public String getName() {
            return name;
        }

        public String getLabel() {
            return label;
        }

        public String getSource() {
            return source;
        }

        public String getExpression() {
            return expression;
        }

        public double getValue() {
            return value;
        }

        /** true nếu giá trị là tỉ lệ / tham số riêng của nhân viên (tô màu trên trang giải trình) */
        public boolean isCustom() {
            return source != null && (source.startsWith("Tỉ lệ riêng") || source.startsWith("Tham số riêng")
                    || source.startsWith("Nhập cố định") || source.startsWith(SRC_MONTHLY)
                    || source.startsWith(SRC_ERP) || source.startsWith(SRC_CFG_MONTHLY)
                    || source.startsWith(SRC_OVERTIME));
        }
    }

    public static class EmployeeCalc {

        private final User user;
        private final Payroll payroll;
        private final boolean existing;
        private final List<TraceRow> trace = new ArrayList<>();
        private String error;
        /** Khoản theo tháng lấy từ số Kế toán nhập trên phiếu import (đồng) */
        final Map<MonthlyItemType, Double> fromPayslip = new EnumMap<>(MonthlyItemType.class);

        public EmployeeCalc(User user, Payroll payroll, boolean existing) {
            this.user = user;
            this.payroll = payroll;
            this.existing = existing;
        }

        void add(String group, String name, String label, String source, String expression, double value) {
            trace.add(new TraceRow(group, name, label, source, expression, value));
        }

        public User getUser() {
            return user;
        }

        public Payroll getPayroll() {
            return payroll;
        }

        public boolean isExisting() {
            return existing;
        }

        public List<TraceRow> getTrace() {
            return trace;
        }

        public String getError() {
            return error;
        }

        public Map<String, List<TraceRow>> getTraceByGroup() {
            return trace.stream().collect(Collectors.groupingBy(TraceRow::getGroup, TreeMap::new, Collectors.toList()));
        }

        /** Tổng các khoản phụ cấp để hiện trên bảng xem trước */
        public double getTongPhuCap() {
            return nz(payroll.getTrachNhiemDuocNhan()) + nz(payroll.getPhuCapLevel()) + nz(payroll.getPhuCapThamNien())
                    + nz(payroll.getPhuCapKiemNhiem()) + nz(payroll.getThamNien5Nam());
        }

        public double getTongThuong() {
            return nz(payroll.getThuongDichVu()) + nz(payroll.getThuongChinhSach())
                    + nz(payroll.getThuongTuVanBanHang()) + nz(payroll.getBanLanhDao());
        }

        /** Phạt vi phạm + tạm ứng + phạt KD/DV + rủi ro (nghìn đồng) */
        public double getTongKhauTru() {
            return nz(payroll.getPhatViPham()) + nz(payroll.getTamUngLuong())
                    + nz(payroll.getPhongChongThienTai()) + nz(payroll.getRuiRo());
        }
    }

    public static class CalculationResult {

        private final int month;
        private final int year;
        private final String department;
        private final boolean saved;
        final List<EmployeeCalc> rows = new ArrayList<>();
        final List<EmployeeCalc> errors = new ArrayList<>();
        final List<String> skippedExisting = new ArrayList<>();
        final List<String> noAttendance = new ArrayList<>();
        final List<String> noBaseSalary = new ArrayList<>();
        final List<String> noFormula = new ArrayList<>();
        final List<String> skippedImported = new ArrayList<>();
        boolean locked;
        Set<MonthlyItemType> erpTypes = Set.of();
        String erpError;

        CalculationResult(int month, int year, String department, boolean saved) {
            this.month = month;
            this.year = year;
            this.department = department;
            this.saved = saved;
        }

        public int getMonth() {
            return month;
        }

        public int getYear() {
            return year;
        }

        public String getDepartment() {
            return department;
        }

        public List<EmployeeCalc> getRows() {
            return rows;
        }

        public List<EmployeeCalc> getErrors() {
            return errors;
        }

        public List<String> getSkippedExisting() {
            return skippedExisting;
        }

        public List<String> getNoAttendance() {
            return noAttendance;
        }

        public List<String> getNoBaseSalary() {
            return noBaseSalary;
        }

        public List<String> getNoFormula() {
            return noFormula;
        }

        public List<String> getSkippedImported() {
            return skippedImported;
        }

        public boolean isLocked() {
            return locked;
        }

        public Set<MonthlyItemType> getErpTypes() {
            return erpTypes;
        }

        public String getErpError() {
            return erpError;
        }

        /** Tổng 1 cột (nghìn đồng) - cho dòng tổng của file Excel */
        public double total(java.util.function.Function<Payroll, Double> f) {
            return rows.stream().mapToDouble(r -> nz(f.apply(r.getPayroll()))).sum();
        }

        public double getTotalTongThuNhap() {
            return rows.stream().mapToDouble(r -> nz(r.getPayroll().getNetSalary())).sum();
        }

        public double getTotalThucNhan() {
            return rows.stream().mapToDouble(r -> nz(r.getPayroll().getThucNhan())).sum();
        }

        public long getExistingCount() {
            return rows.stream().filter(EmployeeCalc::isExisting).count();
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(saved ? "Đã tính và lưu " : "Xem trước ")
                    .append(rows.size()).append(" phiếu lương tháng ").append(month).append("/").append(year);
            if (department != null && !department.isBlank()) {
                sb.append(" - phòng ").append(department);
            }
            if (locked) {
                sb.append(" | KỲ ĐÃ CHỐT");
            }
            if (!skippedImported.isEmpty()) {
                sb.append(" | Giữ nguyên phiếu Kế toán đã import: ").append(skippedImported.size());
            }
            if (!skippedExisting.isEmpty()) {
                sb.append(" | Bỏ qua vì đã có phiếu: ").append(skippedExisting.size());
            }
            if (!noAttendance.isEmpty()) {
                sb.append(" | Không có chấm công: ").append(noAttendance.size());
            }
            if (!noFormula.isEmpty()) {
                sb.append(" | Chưa chọn mẫu công thức (không tính): ").append(noFormula.size());
            }
            if (!noBaseSalary.isEmpty()) {
                sb.append(" | Thiếu lương cơ bản: ").append(noBaseSalary.size());
            }
            if (!errors.isEmpty()) {
                sb.append(" | Lỗi công thức: ").append(errors.size());
            }
            return sb.toString();
        }
    }

    // =====================================================================
    // ĐẾM CÔNG (gộp vào đây, không cần file WorkdayCalculator.java riêng)
    // =====================================================================

    /**
     * =====================================================================
     *  ĐẾM CÔNG 1 NHÂN VIÊN TRONG THÁNG (tính đến ngày asOf)
     * =====================================================================
     *  Công mỗi ngày, ưu tiên:
     *    1. Yêu cầu XÁC NHẬN CÔNG ĐÃ DUYỆT (AttendanceConfirmation.approvalStatus = APPROVED)
     *         PRESENT = 1, HALF_DAY = 0.5, ANNUAL_LEAVE = 1 (phép, hưởng lương), UNPAID_LEAVE = 0
     *         (bảng quy đổi đổi được: payroll.confirm.values)
     *         Phép / nghỉ không lương theo BUỔI (sessionType sáng/chiều) = nửa ngày, cộng với nửa ngày còn lại theo máy chấm.
     *    2. Công đã xác nhận trên bảng chấm công (Attendance.confirmedWork)
     *    3. Công máy chấm (Attendance.workDay)
     *
     *  NGÀY LỄ (HolidayConfig, không trùng Chủ nhật):
     *    - Mọi nhân viên được 1 công lễ hưởng lương (congLe), dù không đi làm.
     *    - Đi làm ngày lễ: toàn bộ giờ làm × hệ số của ngày lễ (wageMultiplier, VD 3.0 = 300%) cộng vào gioLeQuyDoi,
     *      KHÔNG tính thêm giờ thường cho ngày đó.
     *  Chủ nhật: giờ làm tính là thêm giờ Chủ nhật (như trước).
     */
    static final class WorkdayCalculator {

        public static final String APPROVED = "APPROVED";

        /** Mặc định quy đổi trạng thái xác nhận công -> số công */
        public static final String DEFAULT_VALUES = "PRESENT=1,HALF_DAY=0.5,ANNUAL_LEAVE=1,UNPAID_LEAVE=0";

        private static final Set<String> WORK_STATUSES = Set.of("PRESENT", "HALF_DAY");
        private static final String PAID_LEAVE = "ANNUAL_LEAVE";
        private static final String UNPAID_LEAVE = "UNPAID_LEAVE";

        private WorkdayCalculator() {
        }

        /** Kết quả đếm công */
        public static final class Result {
            public double congHL;
            public int soNgayDiLam;
            public double gioOtThuong;
            public double gioOtChuNhat;
            public double congLe;
            public double gioLamNgayLe;
            /** Σ giờ làm ngày lễ × hệ số ngày lễ */
            public double gioLeQuyDoi;
            public double soNgayPhep;
            public double soNgayKhongLuong;
            public int soNgayXacNhan;
            /** trạng thái xác nhận chưa có trong bảng quy đổi (bỏ qua, dùng công máy chấm) */
            public final Set<String> unknownStatuses = new TreeSet<>();
        }

        /** "PRESENT=1,HALF_DAY=0.5" -> map (chữ hoa) */
        public static Map<String, Double> parseValues(String csv) {
            Map<String, Double> m = new HashMap<>();
            String s = csv == null || csv.isBlank() ? DEFAULT_VALUES : csv;
            for (String part : s.split(",")) {
                String[] kv = part.split("[=:]");
                if (kv.length == 2) {
                    try {
                        m.put(kv[0].trim().toUpperCase(Locale.ROOT), Double.valueOf(kv[1].trim()));
                    } catch (NumberFormatException ignored) {
                        // bỏ dòng sai
                    }
                }
            }
            return m;
        }

        public static Result compute(List<Attendance> attendances,
                                     List<AttendanceConfirmation> confirmations,
                                     Map<LocalDate, HolidayConfig> holidays,
                                     Map<String, Double> statusValues,
                                     LocalDate from,
                                     LocalDate asOf,
                                     double gioChuanNgay,
                                     double gioNghiTrua,
                                     double otToiThieuPhut) {
            return compute(attendances, confirmations, holidays, statusValues, from, asOf,
                    gioChuanNgay, gioNghiTrua, otToiThieuPhut, null);
        }

        /** unit: lịch làm việc trong tuần của đơn vị (ngày nghỉ = tính như Chủ nhật bản cũ); null = nghỉ Chủ nhật */
        public static Result compute(List<Attendance> attendances,
                                     List<AttendanceConfirmation> confirmations,
                                     Map<LocalDate, HolidayConfig> holidays,
                                     Map<String, Double> statusValues,
                                     LocalDate from,
                                     LocalDate asOf,
                                     double gioChuanNgay,
                                     double gioNghiTrua,
                                     double otToiThieuPhut,
                                     CompanyUnit unit) {

            Result r = new Result();

            Map<LocalDate, List<Attendance>> attByDay = new TreeMap<>();
            for (Attendance a : attendances) {
                if (a.getDate() != null) {
                    attByDay.computeIfAbsent(a.getDate(), k -> new ArrayList<>()).add(a);
                }
            }
            Map<LocalDate, List<AttendanceConfirmation>> confByDay = new TreeMap<>();
            for (AttendanceConfirmation cf : confirmations) {
                if (cf.getAttendanceDate() != null && APPROVED.equalsIgnoreCase(trim(cf.getApprovalStatus()))) {
                    confByDay.computeIfAbsent(cf.getAttendanceDate(), k -> new ArrayList<>()).add(cf);
                }
            }

            SortedSet<LocalDate> days = new TreeSet<>();
            days.addAll(attByDay.keySet());
            days.addAll(confByDay.keySet());
            days.addAll(holidays.keySet());

            for (LocalDate day : days) {
                if (day.isBefore(from) || day.isAfter(asOf)) {
                    continue;
                }
                // "chuNhat" = NGÀY NGHỈ theo lịch đơn vị (mặc định Chủ nhật như bản cũ)
                boolean chuNhat = CompanyUnit.isOffDay(unit, day.getDayOfWeek());
                List<Attendance> atts = attByDay.getOrDefault(day, List.of());
                HolidayConfig hol = chuNhat ? null : holidays.get(day);

                // ----- ngày lễ -----
                if (hol != null) {
                    r.congLe += 1;
                    r.congHL += 1;
                    double gio = 0;
                    for (Attendance a : atts) {
                        gio += PayrollCalculationService.overtimeHours(a, true, gioChuanNgay, gioNghiTrua, otToiThieuPhut);
                    }
                    if (gio > 0) {
                        double heSo = hol.getWageMultiplier() == null || hol.getWageMultiplier() <= 0 ? 3.0 : hol.getWageMultiplier();
                        r.gioLamNgayLe += gio;
                        r.gioLeQuyDoi += gio * heSo;
                        r.soNgayDiLam++;
                    }
                    continue;
                }

                // ----- công máy chấm / công đã xác nhận trên bảng chấm công -----
                double cong = 0;
                for (Attendance a : atts) {
                    cong += a.getConfirmedWork() != null ? a.getConfirmedWork()
                            : a.getWorkDay() != null ? a.getWorkDay() : 0;
                }

                // ----- yêu cầu xác nhận công đã duyệt (theo thứ tự thời gian duyệt) -----
                List<AttendanceConfirmation> cfs = new ArrayList<>(confByDay.getOrDefault(day, List.of()));
                cfs.sort(Comparator.comparing(WorkdayCalculator::approvedAt));
                boolean confirmed = false;
                for (AttendanceConfirmation cf : cfs) {
                    String status = trim(cf.getConfirmStatus()).toUpperCase(Locale.ROOT);
                    Double val = statusValues.get(status);
                    if (val == null) {
                        if (!status.isEmpty()) {
                            r.unknownStatuses.add(status);
                        }
                        continue;
                    }
                    confirmed = true;
                    boolean half = isHalfSession(cf.getSessionType());
                    if (WORK_STATUSES.contains(status) || !half) {
                        cong = val;                                     // cả ngày: thay hẳn công máy chấm
                        count(r, status, half ? 0.5 : 1);
                    } else {
                        // nghỉ theo buổi: nửa ngày nghỉ + nửa ngày còn lại theo máy chấm
                        cong = Math.min(1, Math.min(cong, 0.5) + val * 0.5);
                        count(r, status, 0.5);
                    }
                }
                if (confirmed) {
                    r.soNgayXacNhan++;
                }

                r.congHL += cong;
                boolean diLam = cong > 0 && !(confirmed && onlyLeave(cfs, statusValues));
                if (diLam) {
                    r.soNgayDiLam++;
                }

                // ----- giờ thêm -----
                for (Attendance a : atts) {
                    double ot = PayrollCalculationService.overtimeHours(a, chuNhat, gioChuanNgay, gioNghiTrua, otToiThieuPhut);
                    if (chuNhat) {
                        r.gioOtChuNhat += ot;
                    } else {
                        r.gioOtThuong += ot;
                    }
                }
            }
            return r;
        }

        private static void count(Result r, String status, double days) {
            if (PAID_LEAVE.equals(status)) {
                r.soNgayPhep += days;
            } else if (UNPAID_LEAVE.equals(status)) {
                r.soNgayKhongLuong += days;
            }
        }

        /** Ngày chỉ có phép cả ngày (không đi làm) */
        private static boolean onlyLeave(List<AttendanceConfirmation> cfs, Map<String, Double> values) {
            for (AttendanceConfirmation cf : cfs) {
                String st = trim(cf.getConfirmStatus()).toUpperCase(Locale.ROOT);
                if (values.containsKey(st) && (WORK_STATUSES.contains(st) || isHalfSession(cf.getSessionType()))) {
                    return false;
                }
            }
            return true;
        }

        /** sessionType là 1 buổi (sáng / chiều / nửa ngày) */
        static boolean isHalfSession(String sessionType) {
            String s = PayrollAccessNorm.norm(sessionType);
            if (s.isEmpty()) {
                return false;
            }
            return s.contains("sang") || s.contains("chieu") || s.contains("morning") || s.contains("afternoon")
                    || s.contains("half") || s.contains("nua") || s.equals("am") || s.equals("pm");
        }

        private static LocalDateTime approvedAt(AttendanceConfirmation cf) {
            if (cf.getApprovedAt() != null) {
                return cf.getApprovedAt();
            }
            return cf.getCreatedAt() != null ? cf.getCreatedAt() : LocalDateTime.MIN;
        }

        private static String trim(String s) {
            return s == null ? "" : s.trim();
        }

        /** Chuẩn hoá chuỗi: bỏ dấu, chữ thường */
        static final class PayrollAccessNorm {
            private PayrollAccessNorm() {
            }

            static String norm(String s) {
                if (s == null) {
                    return "";
                }
                String n = java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
                return n.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
            }
        }
    }
}