package com.example.hrm.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Tự tạo / cập nhật PHIẾU LƯƠNG TẠM TÍNH của tháng đang chạy theo bảng công, mỗi ngày.
 *
 *  - Mặc định chạy 12:15 và 23:45 hằng ngày (giờ Việt Nam), đổi bằng payroll.auto.cron
 *  - Trong payroll.auto.prev-month-days ngày đầu tháng (mặc định 5, khớp hạn duyệt giải trình công)
 *    vẫn cập nhật thêm tháng trước, vì công tháng trước còn được giải trình / duyệt
 *  - MẶC ĐỊNH TẮT (không ghi gì vào bảng lương). Bật bằng payroll.auto.enabled=true trong application.properties.
 *    Khi tắt, nhân viên vẫn xem được phiếu dự kiến ở /salary-forecast (tính trực tiếp, không lưu).
 *  - Không đụng tới phiếu Kế toán đã import (chỉ tính lại phiếu hệ thống tự tính)
 */
@Component
public class PayrollDailyJob {

    private static final Logger log = LoggerFactory.getLogger(PayrollDailyJob.class);

    private final PayrollCalculationService calculationService;

    @Value("${payroll.auto.enabled:false}")
    private boolean enabled;

    @Value("${payroll.auto.prev-month-days:5}")
    private int prevMonthDays;

    public PayrollDailyJob(PayrollCalculationService calculationService) {
        this.calculationService = calculationService;
    }

    @Scheduled(cron = "${payroll.auto.cron:0 15 12,23 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        try {
            List<String> r = runNow();
            log.info("PHIEU LUONG TAM TINH: {}", r);
        } catch (RuntimeException e) {
            log.error("PHIEU LUONG TAM TINH LOI: {}", e.getMessage(), e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Cập nhật ngay (job + nút "Cập nhật phiếu tạm tính" trên trang Tính lương). Trả về tóm tắt từng tháng. */
    public List<String> runNow() {
        LocalDate today = calculationService.today();
        YearMonth current = YearMonth.from(today);

        List<String> out = new ArrayList<>();
        if (today.getDayOfMonth() <= prevMonthDays) {
            out.add(calculationService.refreshAuto(current.minusMonths(1)).summary());
        }
        out.add(calculationService.refreshAuto(current).summary());
        return out;
    }

    /** Bật @Scheduled (nếu ứng dụng đã bật sẵn thì khai báo lại cũng không sao) */
    @Configuration
    @EnableScheduling
    public static class SchedulingConfig {
    }
}