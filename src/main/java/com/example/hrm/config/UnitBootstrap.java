package com.example.hrm.config;

import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.repository.CompanyUnitRepository;
import com.example.hrm.service.UnitScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Chạy 1 lần mỗi khi khởi động, an toàn khi chạy lại:
 *   1. Chưa có đơn vị nào: tạo "đơn vị gốc" từ cấu hình cũ (máy 172.16.3.251, người duyệt 99999/88888/77777,
 *      giờ 07:35 / 08:05 / 12:00 / 17:00) để hệ thống chạy y như trước.
 *   2. Gán mọi nhân viên chưa có đơn vị vào đơn vị gốc.
 *   3. Bảng payroll_period: bỏ khoá duy nhất cũ (năm, tháng) để mỗi đơn vị chốt kỳ riêng.
 */
@Component
public class UnitBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UnitBootstrap.class);

    private final CompanyUnitRepository unitRepository;
    private final UnitScope unitScope;
    private final TransactionTemplate tx;

    @PersistenceContext
    private EntityManager em;

    @Value("${hrm.units.legacy-code:TH}")
    private String legacyCode = "TH";

    @Value("${hrm.units.legacy-name:Thanh Hóa Ford}")
    private String legacyName = "Thanh Hóa Ford";

    @Value("${device.api.url:http://172.16.3.251}")
    private String deviceUrl = "http://172.16.3.251";

    @Value("${device.api.username:admin}")
    private String deviceUser = "admin";

    @Value("${device.api.password:Admin@36}")
    private String devicePassword = "Admin@36";

    public UnitBootstrap(CompanyUnitRepository unitRepository, UnitScope unitScope, PlatformTransactionManager txManager) {
        this.unitRepository = unitRepository;
        this.unitScope = unitScope;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            tx.executeWithoutResult(s -> createLegacyUnit());
            tx.executeWithoutResult(s -> assignUsers());
        } catch (RuntimeException e) {
            log.error("KHOI TAO DON VI LOI", e);
        }
        dropOldPeriodKey();
        unitScope.refresh();
    }

    private void createLegacyUnit() {
        if (unitRepository.count() > 0) {
            if (unitRepository.findFirstByLegacyTrue().isEmpty()) {
                log.warn("CHUA CO DON VI GOC: nhan vien chua gan don vi se khong thuoc don vi nao. Vao /units de dat 1 don vi goc.");
            }
            return;
        }
        CompanyUnit u = new CompanyUnit();
        u.setCode(legacyCode.trim().toUpperCase());
        u.setName(legacyName.trim());
        u.setLegacy(true);
        u.setActive(true);
        u.setLateAfter(CompanyUnit.DEFAULT_LATE_AFTER);
        u.setViolationAfter(CompanyUnit.DEFAULT_VIOLATION_AFTER);
        u.setNoonSplit(CompanyUnit.DEFAULT_NOON);
        u.setEarlyBefore(CompanyUnit.DEFAULT_EARLY_BEFORE);
        u.setDirectorNo("99999");
        u.setServiceDirectorNo("88888");
        u.setChiefAccountantNo("77777");
        u.setDeviceUrl(deviceUrl);
        u.setDeviceUsername(deviceUser);
        u.setDevicePassword(devicePassword);
        u.setSyncFromDate(LocalDate.of(2026, 8, 25));
        u.setCreatedAt(LocalDateTime.now());
        unitRepository.save(u);
        log.info("DA TAO DON VI GOC {} - {}", u.getCode(), u.getName());
        // Mẫu công thức có từ trước là của công ty gốc: Kế toán công ty gốc vẫn sửa được như cũ,
        // đơn vị mới không thấy / không sửa nhầm (quản trị hệ thống nhân bản sang đơn vị mới khi cần).
        int n = em.createNativeQuery("UPDATE salary_formula SET unit_id = ? WHERE unit_id IS NULL")
                .setParameter(1, u.getId())
                .executeUpdate();
        log.info("DA GAN {} MAU CONG THUC CU VAO DON VI GOC", n);
    }

    private void assignUsers() {
        unitRepository.findFirstByLegacyTrue().ifPresent(legacy -> {
            int n = em.createNativeQuery("UPDATE users SET unit_id = ? WHERE unit_id IS NULL")
                    .setParameter(1, legacy.getId())
                    .executeUpdate();
            if (n > 0) {
                log.info("DA GAN {} NHAN VIEN CU VAO DON VI GOC {}", n, legacy.getCode());
            }
        });
    }

    /** MySQL: khoá cũ uk_payroll_period(nam, thang) chặn 2 đơn vị chốt cùng tháng */
    private void dropOldPeriodKey() {
        try {
            Number n = (Number) em.createNativeQuery("""
                    SELECT COUNT(*) FROM information_schema.statistics
                    WHERE table_schema = DATABASE() AND table_name = 'payroll_period' AND index_name = 'uk_payroll_period'
                    """).getSingleResult();
            if (n != null && n.intValue() > 0) {
                tx.executeWithoutResult(s -> em.createNativeQuery("ALTER TABLE payroll_period DROP INDEX uk_payroll_period").executeUpdate());
                log.info("DA BO KHOA uk_payroll_period CU: moi don vi chot ky luong rieng");
            }
        } catch (RuntimeException e) {
            log.warn("Khong bo duoc khoa uk_payroll_period cu (bo qua neu khong dung MySQL): {}", e.getMessage());
        }
    }
}
