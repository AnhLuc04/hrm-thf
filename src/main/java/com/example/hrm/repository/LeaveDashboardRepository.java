package com.example.hrm.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface LeaveDashboardRepository {

    @Query("""
        SELECT COUNT(l) FROM LeaveRequest l WHERE l.status = 'PENDING'
    """)
    long countPending();

    @Query("""
        SELECT SUM(l.totalDays) FROM LeaveRequest l WHERE l.status = 'APPROVED'
    """)
    Double totalApprovedDays();
}