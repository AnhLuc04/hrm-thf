package com.example.hrm.repository;

import com.example.hrm.entity.PayrollOvertime;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayrollOvertimeRepository extends JpaRepository<PayrollOvertime, Long> {

    List<PayrollOvertime> findByYearAndMonth(Integer year, Integer month);

    List<PayrollOvertime> findByUserIdAndYearAndMonth(Long userId, Integer year, Integer month);
}