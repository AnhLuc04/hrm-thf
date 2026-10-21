package com.example.hrm.repository;

import com.example.hrm.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * Truy vấn chấm công dùng riêng cho tính lương.
 * Tách thành interface riêng để KHÔNG phải sửa AttendanceRepository hiện có
 * (Spring Data cho phép nhiều repository cho cùng 1 entity).
 */
public interface PayrollAttendanceRepository extends JpaRepository<Attendance, Long> {

    List<Attendance> findByDateBetween(LocalDate from, LocalDate to);

    List<Attendance> findByUserIdAndDateBetween(Long userId, LocalDate from, LocalDate to);
}