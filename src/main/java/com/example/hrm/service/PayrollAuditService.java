package com.example.hrm.service;

import com.example.hrm.entity.PayrollAuditLog;
import com.example.hrm.repository.PayrollAuditLogRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Ghi nhật ký thao tác module lương. Lỗi ghi nhật ký KHÔNG làm hỏng thao tác chính. */
@Service
public class PayrollAuditService {

    private static final Logger log = LoggerFactory.getLogger(PayrollAuditService.class);
    private static final int MAX_DETAIL = 20_000;

    private final PayrollAuditLogRepository repository;
    /** Ghi nhật ký trong GIAO DỊCH RIÊNG: lỗi ghi nhật ký không làm thao tác chính bị rollback */
    private final TransactionTemplate tx;

    public PayrollAuditService(PayrollAuditLogRepository repository, PlatformTransactionManager txManager) {
        this.repository = repository;
        if (txManager != null) {
            this.tx = new TransactionTemplate(txManager);
            this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        } else {
            this.tx = null;
        }
    }

    /** ĐƠN VỊ: ghi đơn vị đang làm việc vào nhật ký (null trong kiểm thử) */
    private UnitScope unitScope;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUnitScope(UnitScope unitScope) {
        this.unitScope = unitScope;
    }

    public static final long GLOBAL = -1L;

    /** Nhật ký này hiện cho người đang xem? (Kế toán đơn vị chỉ thấy nhật ký đơn vị mình) */
    public boolean visible(PayrollAuditLog a) {
        if (unitScope == null) {
            return true;
        }
        Long scope = unitScope.scopeUnitId();
        if (scope == null) {
            return true;
        }
        Long k = a.getUnitId() != null ? a.getUnitId() : unitScope.legacyId();
        return scope.equals(k);
    }

    public void log(String username, String action, String target, String detail) {
        try {
            PayrollAuditLog a = new PayrollAuditLog();
            if (unitScope != null) {
                // -1 = thao tác chung (quản trị hệ thống xem "Tất cả" / job chạy nền): chỉ quản trị hệ thống thấy
                Long s = unitScope.scopeUnitId();
                a.setUnitId(s != null ? s : GLOBAL);
            }
            a.setTime(LocalDateTime.now());
            a.setUsername(username == null ? "?" : username);
            a.setAction(action);
            a.setTarget(target == null ? null : cut(target, 200));
            a.setDetail(detail == null ? null : cut(detail, MAX_DETAIL));
            if (tx != null) {
                tx.executeWithoutResult(status -> repository.save(a));
            } else {
                repository.save(a);
            }
        } catch (RuntimeException e) {
            log.warn("KHONG GHI DUOC NHAT KY LUONG: {} {} {}", action, target, e.getMessage());
        }
    }

    public List<PayrollAuditLog> between(LocalDate from, LocalDate to) {
        return repository.findTop500ByTimeBetweenOrderByTimeDesc(from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}