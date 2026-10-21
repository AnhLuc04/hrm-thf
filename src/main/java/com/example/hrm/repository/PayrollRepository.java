package com.example.hrm.repository;


import com.example.hrm.entity.Payroll;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;



public interface PayrollRepository
        extends JpaRepository<Payroll, Long> {



    // Lịch sử phiếu lương theo mã lương Excel

    List<Payroll>
    findByEmployeeSalaryCodeOrderByYearDescMonthDesc(
            String salaryCode
    );



    // Kiểm tra trùng phiếu lương

    boolean existsByEmployeeIdAndMonthAndYear(
            Long employeeId,
            Integer month,
            Integer year
    );

    List<Payroll> findByEmployeeEmployeeNoOrderByYearDescMonthDesc(
            String employeeNo
    );

    // Danh sách theo tháng

    List<Payroll>
    findByMonthAndYear(
            Integer month,
            Integer year
    );


}