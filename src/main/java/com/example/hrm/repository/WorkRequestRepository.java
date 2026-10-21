package com.example.hrm.repository;

import com.example.hrm.entity.User;
import com.example.hrm.entity.WorkRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface WorkRequestRepository extends JpaRepository<WorkRequest, Long> {

    Optional<WorkRequest> findByUserAndLeaveDateAndStatus(
            User user,
            LocalDate leaveDate,
            String status
    );
}