package com.example.hrm.repository;

import com.example.hrm.entity.LeaveApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeaveApprovalRepository extends JpaRepository<LeaveApproval, Long> {

    List<LeaveApproval> findByLeaveRequestId(Long leaveRequestId);
}