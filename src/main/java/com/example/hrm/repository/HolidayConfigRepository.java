package com.example.hrm.repository;

import com.example.hrm.entity.HolidayConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface HolidayConfigRepository extends JpaRepository<HolidayConfig, Long> {
    // Lấy toàn bộ danh sách ngày lễ trong khoảng thời gian để kiểm tra nhanh
    List<HolidayConfig> findByHolidayDateBetween(LocalDate start, LocalDate end);
    // Tìm ngày lễ trong khoảng thời gian và sắp xếp tăng dần
    List<HolidayConfig> findByHolidayDateBetweenOrderByHolidayDateAsc(LocalDate start, LocalDate end);

    // Kiểm tra tồn tại trả về boolean cực nhanh
    boolean existsByHolidayDate(LocalDate date);
}