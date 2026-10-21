package com.example.hrm.repository;

import com.example.hrm.entity.PayrollAutoMark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PayrollAutoMarkRepository extends JpaRepository<PayrollAutoMark, Long> {

    Optional<PayrollAutoMark> findByPayrollId(Long payrollId);

    Optional<PayrollAutoMark> findFirstByUserIdAndMonthAndYear(Long userId, Integer month, Integer year);

    List<PayrollAutoMark> findByMonthAndYear(Integer month, Integer year);
}