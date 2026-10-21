package com.example.hrm.service;

import com.example.hrm.entity.MonthlyItemType;
import com.example.hrm.entity.PayrollMonthlyItem;
import com.example.hrm.repository.PayrollMonthlyItemRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/** Khoản phát sinh theo tháng (phạt, tạm ứng, rủi ro, thưởng nhập tay). */
@Service
public class PayrollMonthlyItemService {

    private final PayrollMonthlyItemRepository repository;
    private final PayrollPeriodService periodService;
    private final PayrollAuditService audit;

    public PayrollMonthlyItemService(PayrollMonthlyItemRepository repository,
                                     PayrollPeriodService periodService,
                                     PayrollAuditService audit) {
        this.repository = repository;
        this.periodService = periodService;
        this.audit = audit;
    }

    /** userId -> (loại -> số tiền đồng) của 1 tháng */
    public Map<Long, Map<MonthlyItemType, Double>> forMonth(YearMonth ym) {
        Map<Long, Map<MonthlyItemType, Double>> m = new HashMap<>();
        for (PayrollMonthlyItem it : repository.findByYearAndMonth(ym.getYear(), ym.getMonthValue())) {
            if (it.getAmount() != null) {
                m.computeIfAbsent(it.getUserId(), k -> new EnumMap<>(MonthlyItemType.class)).put(it.getType(), it.getAmount());
            }
        }
        return m;
    }

    public Map<MonthlyItemType, Double> forUser(Long userId, YearMonth ym) {
        Map<MonthlyItemType, Double> m = new EnumMap<>(MonthlyItemType.class);
        for (PayrollMonthlyItem it : repository.findByUserIdAndYearAndMonth(userId, ym.getYear(), ym.getMonthValue())) {
            if (it.getAmount() != null) {
                m.put(it.getType(), it.getAmount());
            }
        }
        return m;
    }

    /**
     * Lưu bảng nhập của các nhân viên đã sửa.
     * @param values userId -> (loại -> số tiền; null = xoá khoản đó)
     * @return số ô thay đổi
     */
    @Transactional
    public int save(YearMonth ym, Map<Long, Map<MonthlyItemType, Double>> values,
                    Map<Long, String> labels, String username) {

        periodService.requireOpen(ym);

        Map<String, PayrollMonthlyItem> existing = new HashMap<>();
        for (PayrollMonthlyItem it : repository.findByYearAndMonth(ym.getYear(), ym.getMonthValue())) {
            existing.put(it.getUserId() + "|" + it.getType(), it);
        }

        int changed = 0;
        StringBuilder log = new StringBuilder();

        for (Map.Entry<Long, Map<MonthlyItemType, Double>> e : values.entrySet()) {
            Long userId = e.getKey();
            List<String> rowLog = new ArrayList<>();
            for (Map.Entry<MonthlyItemType, Double> v : e.getValue().entrySet()) {
                PayrollMonthlyItem it = existing.get(userId + "|" + v.getKey());
                Double old = it == null ? null : it.getAmount();
                Double now = v.getValue();
                if (same(old, now)) {
                    continue;
                }
                if (now == null) {
                    repository.deleteById(it.getId());
                } else {
                    if (it == null) {
                        it = new PayrollMonthlyItem();
                        it.setUserId(userId);
                        it.setYear(ym.getYear());
                        it.setMonth(ym.getMonthValue());
                        it.setType(v.getKey());
                    }
                    it.setAmount(now);
                    it.setUpdatedBy(username);
                    it.setUpdatedAt(LocalDateTime.now());
                    repository.save(it);
                }
                changed++;
                rowLog.add(v.getKey().getLabel() + ": " + money(old) + " → " + money(now));
            }
            if (!rowLog.isEmpty()) {
                log.append(labels.getOrDefault(userId, "#" + userId)).append(": ")
                        .append(String.join("; ", rowLog)).append('\n');
            }
        }

        if (changed > 0) {
            audit.log(username, "MONTHLY_SAVE", "Khoản tháng " + ym.getMonthValue() + "/" + ym.getYear(), log.toString());
        }
        return changed;
    }

    /** ĐƠN VỊ: phạm vi người đang đăng nhập (null trong kiểm thử = không lọc) */
    private UnitScope unitScope;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUnitScope(UnitScope unitScope) {
        this.unitScope = unitScope;
    }

    /** Chép các khoản của tháng trước sang tháng này cho những ô tháng này còn trống */
    @Transactional
    public int copyFromPrevious(YearMonth ym, Set<MonthlyItemType> types, String username) {
        periodService.requireOpen(ym);
        YearMonth prev = ym.minusMonths(1);
        Map<Long, Map<MonthlyItemType, Double>> cur = forMonth(ym);
        // 🔒 ĐƠN VỊ: chỉ chép cho nhân viên của đơn vị đang làm việc
        java.util.Set<Long> allowed = unitScope == null || unitScope.scopeUnitId() == null ? null
                : unitScope.users().stream().map(com.example.hrm.entity.User::getId).collect(java.util.stream.Collectors.toSet());
        int n = 0;
        for (PayrollMonthlyItem p : repository.findByYearAndMonth(prev.getYear(), prev.getMonthValue())) {
            if (p.getAmount() == null || !types.contains(p.getType())) {
                continue;
            }
            if (allowed != null && !allowed.contains(p.getUserId())) {
                continue;
            }
            if (cur.getOrDefault(p.getUserId(), Map.of()).containsKey(p.getType())) {
                continue;
            }
            PayrollMonthlyItem it = new PayrollMonthlyItem();
            it.setUserId(p.getUserId());
            it.setYear(ym.getYear());
            it.setMonth(ym.getMonthValue());
            it.setType(p.getType());
            it.setAmount(p.getAmount());
            it.setNote("Chép từ " + prev.getMonthValue() + "/" + prev.getYear());
            it.setUpdatedBy(username);
            it.setUpdatedAt(LocalDateTime.now());
            repository.save(it);
            n++;
        }
        if (n > 0) {
            audit.log(username, "MONTHLY_COPY", "Khoản tháng " + ym.getMonthValue() + "/" + ym.getYear(),
                    "Chép " + n + " khoản từ tháng " + prev.getMonthValue() + "/" + prev.getYear() + " (" + types + ")");
        }
        return n;
    }

    /**
     * Chuyển các khoản Kế toán đã nhập trên phiếu lương import sang "Khoản theo tháng" (chỉ ô đang trống),
     * để từ đó về sau mọi lần tính lại đều lấy cùng 1 nguồn. Gọi trong giao dịch tính lương.
     */
    @Transactional
    public int adoptFromPayslips(YearMonth ym, Map<Long, Map<MonthlyItemType, Double>> values) {
        Map<Long, Map<MonthlyItemType, Double>> cur = forMonth(ym);
        int n = 0;
        StringBuilder log = new StringBuilder();
        for (Map.Entry<Long, Map<MonthlyItemType, Double>> e : values.entrySet()) {
            for (Map.Entry<MonthlyItemType, Double> v : e.getValue().entrySet()) {
                if (v.getValue() == null || cur.getOrDefault(e.getKey(), Map.of()).containsKey(v.getKey())) {
                    continue;
                }
                PayrollMonthlyItem it = new PayrollMonthlyItem();
                it.setUserId(e.getKey());
                it.setYear(ym.getYear());
                it.setMonth(ym.getMonthValue());
                it.setType(v.getKey());
                it.setAmount(v.getValue());
                it.setNote("Lấy từ phiếu lương Kế toán import");
                it.setUpdatedBy("he-thong");
                it.setUpdatedAt(LocalDateTime.now());
                repository.save(it);
                n++;
                log.append("#").append(e.getKey()).append(' ').append(v.getKey().getLabel())
                        .append(": ").append(money(v.getValue())).append('\n');
            }
        }
        if (n > 0) {
            audit.log("he-thong", "MONTHLY_FROM_PAYSLIP", "Khoản tháng " + ym.getMonthValue() + "/" + ym.getYear(), log.toString());
        }
        return n;
    }

    private static boolean same(Double a, Double b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return Math.abs(a - b) < 0.5;
    }

    private static String money(Double v) {
        return v == null ? "(trống)" : String.format(Locale.ROOT, "%,.0f", v).replace(',', '.');
    }
}