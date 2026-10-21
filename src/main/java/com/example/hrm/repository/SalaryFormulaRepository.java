package com.example.hrm.repository;

import com.example.hrm.entity.FormulaScope;
import com.example.hrm.entity.SalaryFormula;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SalaryFormulaRepository extends JpaRepository<SalaryFormula, Long> {

    List<SalaryFormula> findByScope(FormulaScope scope);

    Optional<SalaryFormula> findFirstByScope(FormulaScope scope);

    Optional<SalaryFormula> findFirstByScopeAndDepartmentIgnoreCase(FormulaScope scope, String department);

    Optional<SalaryFormula> findFirstByScopeAndUserId(FormulaScope scope, Long userId);
}