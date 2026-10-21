package com.example.hrm.service;

import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.entity.PayrollPeriod;
import com.example.hrm.repository.PayrollPeriodRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Chốt / mở kỳ lương. Tháng chưa có bản ghi = đang mở.
 *
 * ĐƠN VỊ: mỗi đơn vị chốt kỳ riêng (Kế toán SG chốt tháng 9 không khoá tháng 9 của TH).
 * Bản ghi cũ (unit_id = null) thuộc đơn vị gốc.
 * Các hàm không truyền đơn vị dùng đơn vị đang xem (UnitScope); quản trị hệ thống xem "Tất cả"
 * thì coi là đã chốt nếu có bất kỳ đơn vị nào chốt (để không sửa nhầm dữ liệu đơn vị đã chốt).
 */
@Service
public class PayrollPeriodService {

    private final PayrollPeriodRepository repository;
    private final PayrollAuditService audit;
    private final UnitScope unitScope;

    public PayrollPeriodService(PayrollPeriodRepository repository, PayrollAuditService audit, UnitScope unitScope) {
        this.repository = repository;
        this.audit = audit;
        this.unitScope = unitScope;
    }

    /** Khoá đơn vị của 1 bản ghi kỳ (null = đơn vị gốc) */
    private Long key(PayrollPeriod p) {
        return p.getUnitId() != null ? p.getUnitId() : unitScope.legacyId();
    }

    /** Các đơn vị đã chốt tháng này */
    public Set<Long> lockedUnits(YearMonth ym) {
        Set<Long> s = new HashSet<>();
        for (PayrollPeriod p : repository.findByYearAndMonth(ym.getYear(), ym.getMonthValue())) {
            if (p.isLocked()) {
                s.add(key(p));
            }
        }
        return s;
    }

    public boolean isLocked(YearMonth ym, Long unitKey) {
        return lockedUnits(ym).contains(unitKey);
    }

    /** Đã chốt với phạm vi đang xem */
    public boolean isLocked(YearMonth ym) {
        Long scope = unitScope.scopeUnitId();
        Set<Long> locked = lockedUnits(ym);
        return scope == null ? !locked.isEmpty() : locked.contains(scope);
    }

    /** Bản ghi kỳ của đơn vị đang xem (để hiện người chốt, ghi chú) */
    public PayrollPeriod get(YearMonth ym) {
        Long scope = unitScope.scopeUnitId();
        PayrollPeriod any = null;
        for (PayrollPeriod p : repository.findByYearAndMonth(ym.getYear(), ym.getMonthValue())) {
            if (scope != null && Objects.equals(key(p), scope)) {
                return p;
            }
            if (scope == null && (any == null || p.isLocked())) {
                any = p;
            }
        }
        return any;
    }

    /** Ném IllegalStateException nếu kỳ đã chốt (dùng trước mọi thao tác ghi dữ liệu của tháng) */
    public void requireOpen(YearMonth ym) {
        if (isLocked(ym)) {
            throw new IllegalStateException("Kỳ lương " + ym.getMonthValue() + "/" + ym.getYear()
                    + " đã CHỐT, không sửa được. Mở lại kỳ ở trang Tính lương tháng nếu cần.");
        }
    }

    private PayrollPeriod findOrNew(YearMonth ym, Long unitKey) {
        for (PayrollPeriod p : repository.findByYearAndMonth(ym.getYear(), ym.getMonthValue())) {
            if (Objects.equals(key(p), unitKey)) {
                return p;
            }
        }
        PayrollPeriod n = new PayrollPeriod();
        n.setYear(ym.getYear());
        n.setMonth(ym.getMonthValue());
        n.setUnitId(unitKey);
        return n;
    }

    private Long requireScope() {
        Long scope = unitScope.scopeUnitId();
        if (scope == null && unitScope.legacyId() != null) {
            throw new IllegalStateException("Chọn 1 đơn vị ở menu trái trước khi chốt / mở kỳ lương (mỗi đơn vị chốt riêng).");
        }
        return scope;
    }

    private String unitLabel(Long unitKey) {
        CompanyUnit u = unitScope.scopeUnit();
        return u != null && Objects.equals(u.getId(), unitKey) ? " [" + u.getCode() + "]" : "";
    }

    @Transactional
    public void lock(YearMonth ym, String username, String note) {
        Long scope = requireScope();
        PayrollPeriod p = findOrNew(ym, scope);
        p.setStatus(PayrollPeriod.LOCKED);
        p.setLockedBy(username);
        p.setLockedAt(LocalDateTime.now());
        p.setNote(note);
        repository.save(p);
        audit.log(username, "PERIOD_LOCK", "Kỳ " + ym.getMonthValue() + "/" + ym.getYear() + unitLabel(scope), note);
    }

    @Transactional
    public void unlock(YearMonth ym, String username, String reason) {
        Long scope = requireScope();
        for (PayrollPeriod p : repository.findByYearAndMonth(ym.getYear(), ym.getMonthValue())) {
            if (Objects.equals(key(p), scope)) {
                p.setStatus(PayrollPeriod.OPEN);
                p.setNote(reason);
                repository.save(p);
            }
        }
        audit.log(username, "PERIOD_UNLOCK", "Kỳ " + ym.getMonthValue() + "/" + ym.getYear() + unitLabel(scope), reason);
    }
}
