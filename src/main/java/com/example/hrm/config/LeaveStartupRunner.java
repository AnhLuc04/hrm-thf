package com.example.hrm.config;

import com.example.hrm.service.LeaveBalanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@RequiredArgsConstructor
public class LeaveStartupRunner implements CommandLineRunner {

    private final LeaveBalanceService leaveBalanceService;

    @Override
    public void run(String... args) {

        LocalDate today = LocalDate.now();

        System.out.println("Ngày hiện tại: " + today);

        if (today.getDayOfMonth() >= 3) {

            System.out.println("Đã qua ngày 3 → kiểm tra đồng bộ phép năm");

            try {
                leaveBalanceService.accrueMonthlyLeave();

                System.out.println("ĐỒNG BỘ PHÉP HOÀN TẤT");

            } catch (Exception e) {
                System.err.println("LỖI ĐỒNG BỘ PHÉP");
                e.printStackTrace();
            }

        } else {
            System.out.println("Chưa đến ngày 3 → chưa kiểm tra cộng phép");
        }
    }
}