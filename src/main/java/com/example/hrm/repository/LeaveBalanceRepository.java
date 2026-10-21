package com.example.hrm.repository;

import com.example.hrm.entity.LeaveBalance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Long> {
    List<LeaveBalance> findByEmployeeNoIn(List<String> employeeNos);
    LeaveBalance findByEmployeeNo(String employeeNo);
}