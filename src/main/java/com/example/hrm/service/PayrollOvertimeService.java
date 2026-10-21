package com.example.hrm.service;

import com.example.hrm.entity.PayrollOvertime;
import com.example.hrm.repository.PayrollOvertimeRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * TĂNG CA NHẬP TAY theo tháng: mỗi nhân viên nhiều dòng "số giờ × hệ số".
 * <p>
 * Cách gõ trên bảng (1 ô): các dòng cách nhau bằng dấu + hoặc ; hoặc xuống dòng
 * "4x2 + 5x3"      = 4 giờ hệ số 2  và 5 giờ hệ số 3  -> quy đổi 4×2 + 5×3 = 23 giờ
 * "10x1,5; 4x200%" = 10 giờ × 1,5  và 4 giờ × 2
 * "8"              = 8 giờ, hệ số mặc định 1,5
 */
@Service
public class PayrollOvertimeService {

    /**
     * Hệ số khi chỉ gõ số giờ
     */
    public static final double DEFAULT_FACTOR = 1.5;

    private final PayrollOvertimeRepository repository;
    private final PayrollPeriodService periodService;
    private final PayrollAuditService audit;

    public PayrollOvertimeService(PayrollOvertimeRepository repository,
                                  PayrollPeriodService periodService,
                                  PayrollAuditService audit) {
        this.repository = repository;
        this.periodService = periodService;
        this.audit = audit;
    }

    /**
     * 1 dòng tăng ca
     */
    public record Line(double hours, double factor) {
        public double converted() {
            return hours * factor;
        }
    }

    // =====================================================================
    // ĐỌC
    // =====================================================================

    /**
     * userId -> các dòng tăng ca của tháng
     */
    public Map<Long, List<Line>> forMonth(YearMonth ym) {
        Map<Long, List<PayrollOvertime>> raw = new HashMap<>();
        for (PayrollOvertime o : repository.findByYearAndMonth(ym.getYear(), ym.getMonthValue())) {
            raw.computeIfAbsent(o.getUserId(), k -> new ArrayList<>()).add(o);
        }
        Map<Long, List<Line>> out = new HashMap<>();
        raw.forEach((u, list) -> out.put(u, toLines(list)));
        return out;
    }

    public List<Line> forUser(Long userId, YearMonth ym) {
        return toLines(repository.findByUserIdAndYearAndMonth(userId, ym.getYear(), ym.getMonthValue()));
    }

    private static List<Line> toLines(List<PayrollOvertime> list) {
        List<PayrollOvertime> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparing((PayrollOvertime o) -> o.getSeq() == null ? 0 : o.getSeq())
                .thenComparing(o -> o.getId() == null ? 0 : o.getId()));
        List<Line> out = new ArrayList<>();
        for (PayrollOvertime o : sorted) {
            if (o.getHours() != null && o.getFactor() != null) {
                out.add(new Line(o.getHours(), o.getFactor()));
            }
        }
        return out;
    }

    // =====================================================================
    // GHI
    // =====================================================================

    /**
     * Lưu tăng ca của các nhân viên đã sửa (thay toàn bộ dòng của người đó trong tháng).
     *
     * @param values userId -> các dòng (rỗng = xoá hết)
     * @return số nhân viên thay đổi
     */
    @Transactional
    public int save(YearMonth ym, Map<Long, List<Line>> values, Map<Long, String> labels, String username) {
        periodService.requireOpen(ym);

        Map<Long, List<PayrollOvertime>> existing = new HashMap<>();
        for (PayrollOvertime o : repository.findByYearAndMonth(ym.getYear(), ym.getMonthValue())) {
            existing.computeIfAbsent(o.getUserId(), k -> new ArrayList<>()).add(o);
        }

        int changed = 0;
        StringBuilder log = new StringBuilder();
        for (Map.Entry<Long, List<Line>> e : values.entrySet()) {
            List<PayrollOvertime> old = existing.getOrDefault(e.getKey(), List.of());
            List<Line> oldLines = toLines(old);
            List<Line> now = e.getValue() == null ? List.of() : e.getValue();
            if (oldLines.equals(now)) {
                continue;
            }
            for (PayrollOvertime o : old) {
                repository.deleteById(o.getId());
            }
            int seq = 1;
            for (Line l : now) {
                PayrollOvertime o = new PayrollOvertime();
                o.setUserId(e.getKey());
                o.setYear(ym.getYear());
                o.setMonth(ym.getMonthValue());
                o.setSeq(seq++);
                o.setHours(l.hours());
                o.setFactor(l.factor());
                o.setUpdatedBy(username);
                o.setUpdatedAt(LocalDateTime.now());
                repository.save(o);
            }
            changed++;
            log.append(labels.getOrDefault(e.getKey(), "#" + e.getKey())).append(": ")
                    .append(oldLines.isEmpty() ? "(trống)" : format(oldLines)).append(" → ")
                    .append(now.isEmpty() ? "(trống)" : format(now)).append('\n');
        }
        if (changed > 0) {
            audit.log(username, "OVERTIME_SAVE", "Tăng ca tháng " + ym.getMonthValue() + "/" + ym.getYear(), log.toString());
        }
        return changed;
    }

    // =====================================================================
    // ĐỌC / VIẾT CHUỖI "4x2 + 5x3"
    // =====================================================================

    /**
     * "4x2 + 5x3; 10*1,5" -> các dòng. Trống -> rỗng.
     *
     * @throws IllegalArgumentException kèm lời nhắc tiếng Việt nếu gõ sai
     */
    public static List<Line> parse(String text) {
        List<Line> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String part : text.split("[+;\\n\\r]+")) {
            String p = part.trim().replace(" ", "").toLowerCase(Locale.ROOT);
            if (p.isEmpty()) {
                continue;
            }
            p = p.replace("giờ", "").replace("gio", "").replace("h", "");
            String[] hf = p.split("[x×*]");
            if (hf.length > 2) {
                throw new IllegalArgumentException("'" + part.trim() + "' - mỗi dòng dạng số_giờ x hệ_số, VD 4x2");
            }
            double hours = number(hf[0], part);
            double factor = hf.length == 2 ? factor(hf[1], part) : DEFAULT_FACTOR;
            if (hours <= 0 || hours > 400) {
                throw new IllegalArgumentException("'" + part.trim() + "' - số giờ phải từ 0 đến 400");
            }
            if (factor <= 0 || factor > 10) {
                throw new IllegalArgumentException("'" + part.trim() + "' - hệ số phải từ 0 đến 10 (VD 1,5 / 2 / 3 / 300%)");
            }
            out.add(new Line(hours, factor));
        }
        return out;
    }

    /**
     * các dòng -> "4×2 + 5×3"
     */
    public static String format(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringJoiner j = new StringJoiner(" + ");
        for (Line l : lines) {
            j.add(num(l.hours()) + "x" + num(l.factor()));
        }
        return j.toString();
    }

    public static double totalHours(List<Line> lines) {
        return lines == null ? 0 : lines.stream().mapToDouble(Line::hours).sum();
    }

    public static double totalConverted(List<Line> lines) {
        return lines == null ? 0 : lines.stream().mapToDouble(Line::converted).sum();
    }

    private static double factor(String s, String part) {
        String t = s.trim();
        if (t.endsWith("%")) {
            return number(t.substring(0, t.length() - 1), part) / 100.0;
        }
        return number(t, part);
    }

    private static double number(String s, String part) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + part.trim() + "' - không đọc được số (VD 4x2, 2,5x1,5, 4x300%)");
        }
    }

    public static String num(double v) {
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.ROOT);
        sym.setDecimalSeparator(',');
        return new DecimalFormat("0.##", sym).format(v);
    }
}