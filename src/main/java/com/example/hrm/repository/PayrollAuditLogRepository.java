package com.example.hrm.repository;

import com.example.hrm.entity.PayrollAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PayrollAuditLogRepository extends JpaRepository<PayrollAuditLog, Long> {

    List<PayrollAuditLog> findTop500ByTimeBetweenOrderByTimeDesc(LocalDateTime from, LocalDateTime to);
}