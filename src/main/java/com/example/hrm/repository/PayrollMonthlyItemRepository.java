package com.example.hrm.repository;

import com.example.hrm.entity.PayrollMonthlyItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayrollMonthlyItemRepository extends JpaRepository<PayrollMonthlyItem, Long> {

    List<PayrollMonthlyItem> findByYearAndMonth(Integer year, Integer month);

    List<PayrollMonthlyItem> findByUserIdAndYearAndMonth(Long userId, Integer year, Integer month);
}