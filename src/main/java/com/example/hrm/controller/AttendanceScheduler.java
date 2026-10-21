package com.example.hrm.controller;

import com.example.hrm.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AttendanceScheduler {

    private final AttendanceService attendanceService;

    // GIỜ KÉO CÔNG nay cài riêng từng đơn vị (UnitSyncRunner, mặc định vẫn 09:00 và 20:00).
    // 2 lịch cố định dưới đây của bản cũ nay TẮT (tránh kéo 2 lần). Muốn dùng lại: đặt hrm.sync.enabled=false và
    // hrm.sync.legacy-cron-morning=0 0 9 * * ?   hrm.sync.legacy-cron-evening=0 0 20 * * ?
    @Scheduled(cron = "${hrm.sync.legacy-cron-morning:-}", zone = "Asia/Ho_Chi_Minh")
    public void syncMorning() {
        try {
            log.info("Start sync attendance 9AM...");
            attendanceService.syncAttendance();
            log.info("Sync done!");
        } catch (Exception e) {
            log.error("Sync failed", e);
        }
    }

    @Scheduled(cron = "${hrm.sync.legacy-cron-evening:-}", zone = "Asia/Ho_Chi_Minh")
    public void syncEvening() {
        try {
            log.info("Start sync attendance 8PM...");
            attendanceService.syncAttendance();
            log.info("Sync done!");
        } catch (Exception e) {
            log.error("Sync failed", e);
        }
    }
}