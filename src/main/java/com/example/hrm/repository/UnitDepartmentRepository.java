package com.example.hrm.repository;

import com.example.hrm.entity.UnitDepartment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UnitDepartmentRepository extends JpaRepository<UnitDepartment, Long> {

    List<UnitDepartment> findByUnitIdOrderByNameAsc(Long unitId);
}
