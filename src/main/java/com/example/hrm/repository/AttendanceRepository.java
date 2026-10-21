package com.example.hrm.repository;

import com.example.hrm.entity.Attendance;
import com.example.hrm.entity.AttendanceConfirmation;
import com.example.hrm.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceRepository
        extends JpaRepository<Attendance, Long> {

    Optional<Attendance> findByUserAndDate(
            User user,
            LocalDate date
    );
    List<Attendance> findByDateBetween(
            LocalDate start,
            LocalDate end
    );
    List<Attendance> findByUser_EmployeeNoAndDateBetween(
            String employeeNo,
            LocalDate from,
            LocalDate to
    );
    @Query("SELECT ac FROM AttendanceConfirmation ac JOIN FETCH ac.user " +
            "WHERE ac.approvalStatus = 'PENDING' " +
            "AND ac.attendanceDate BETWEEN :from AND :to")
    List<AttendanceConfirmation> findPendingRequestsWithUser(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}