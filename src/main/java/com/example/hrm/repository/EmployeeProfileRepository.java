package com.example.hrm.repository;

import com.example.hrm.entity.EmployeeProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmployeeProfileRepository extends JpaRepository<EmployeeProfile, Long> {
    Optional<EmployeeProfile> findByUserId(Long userId);
    List<EmployeeProfile> findByUserIdIn(Collection<Long> userIds);
}
