package com.example.hrm.repository;

import com.example.hrm.entity.AttendanceLeaveMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AttendanceLeaveMappingRepository extends JpaRepository<AttendanceLeaveMapping, Long> {

    List<AttendanceLeaveMapping> findByEmployeeNoAndDateBetween(
            String employeeNo,
            LocalDate from,
            LocalDate to
    );
}