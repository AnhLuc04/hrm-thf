package com.example.hrm.repository;

import com.example.hrm.entity.SalaryConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SalaryConfigRepository extends JpaRepository<SalaryConfig, Long> {

    Optional<SalaryConfig> findByUserId(Long userId);
}