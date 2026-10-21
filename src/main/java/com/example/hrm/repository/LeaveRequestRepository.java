package com.example.hrm.repository;

import com.example.hrm.entity.LeaveRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    // đơn của user
    List<LeaveRequest> findByEmployeeNoOrderByCreatedAtDesc(String employeeNo);

    // theo trạng thái
    List<LeaveRequest> findByStatus(String status);

    // theo phòng ban (nếu bạn join thêm user)
    List<LeaveRequest> findByEmployeeNoIn(List<String> employeeNos);
    List<LeaveRequest> findByEmployeeNoAndStatus(
            String employeeNo,
            String status
    );
}