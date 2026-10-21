package com.example.hrm.repository;

import com.example.hrm.entity.CompanyUnit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyUnitRepository extends JpaRepository<CompanyUnit, Long> {

    Optional<CompanyUnit> findByCodeIgnoreCase(String code);

    List<CompanyUnit> findAllByOrderByCodeAsc();

    Optional<CompanyUnit> findFirstByLegacyTrue();
}
