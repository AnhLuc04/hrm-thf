package com.example.hrm.repository;

import com.example.hrm.entity.PayrollPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PayrollPeriodRepository extends JpaRepository<PayrollPeriod, Long> {

    Optional<PayrollPeriod> findFirstByYearAndMonth(Integer year, Integer month);

    /** ĐƠN VỊ: mọi bản ghi kỳ của 1 tháng (mỗi đơn vị 1 dòng; dòng cũ unit_id = null) */
    List<PayrollPeriod> findByYearAndMonth(Integer year, Integer month);
}