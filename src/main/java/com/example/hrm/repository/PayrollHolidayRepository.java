package com.example.hrm.repository;

import com.example.hrm.entity.HolidayConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/** Ngày lễ dùng riêng cho tính lương (không sửa repository HolidayConfig hiện có). */
public interface PayrollHolidayRepository extends JpaRepository<HolidayConfig, Long> {

    List<HolidayConfig> findByHolidayDateBetween(LocalDate from, LocalDate to);
}