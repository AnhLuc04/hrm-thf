package com.example.hrm.repository;

import com.example.hrm.entity.Payroll;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Truy vấn phiếu lương dùng riêng cho tính lương tự động
 * (tách riêng để không phải sửa PayrollRepository hiện có).
 */
public interface PayrollCalcRepository extends JpaRepository<Payroll, Long> {

    Optional<Payroll> findFirstByEmployeeIdAndMonthAndYear(Long employeeId, Integer month, Integer year);

    List<Payroll> findByMonthAndYear(Integer month, Integer year);
}