package com.example.hrm.repository;

import com.example.hrm.entity.Overtime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface OvertimeRepository extends JpaRepository<Overtime, Long> {
    List<Overtime> findByRequestDateBetweenAndApprovalStatus(LocalDate start, LocalDate end, String status);
}