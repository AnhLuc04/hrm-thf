package com.example.hrm.service;

import com.example.hrm.entity.LeaveRequest;
import com.example.hrm.entity.LeaveBalance;
import com.example.hrm.repository.LeaveRequestRepository;
import com.example.hrm.repository.LeaveBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LeaveService {

    private final LeaveRequestRepository leaveRepo;
    private final LeaveBalanceRepository balanceRepo;

    public List<LeaveRequest> myLeaves(String employeeNo) {
        return leaveRepo.findByEmployeeNoOrderByCreatedAtDesc(employeeNo);
    }

    @Transactional
    public void createLeave(LeaveRequest req, String employeeNo) {

        req.setEmployeeNo(employeeNo);
        req.setStatus("PENDING");

        long days = ChronoUnit.DAYS.between(req.getFromDate(), req.getToDate()) + 1;

        if ("FULL_DAY".equals(req.getSessionType())) {
            req.setTotalDays(days);
        } else {
            req.setTotalDays(days * 0.5);
        }

        req.setCreatedAt(java.time.LocalDateTime.now());

        leaveRepo.save(req);
    }

    @Transactional
    public void approve(Long id) {

        LeaveRequest req = leaveRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Not found"));

        LeaveBalance bal = balanceRepo.findByEmployeeNo(req.getEmployeeNo());

        bal.setUsedAnnualLeave(bal.getUsedAnnualLeave() + req.getTotalDays());
        bal.setRemaining(bal.getTotalAnnualLeave() - bal.getUsedAnnualLeave());

        balanceRepo.save(bal);

        req.setStatus("APPROVED");
        leaveRepo.save(req);
    }

    @Transactional
    public void reject(Long id) {

        LeaveRequest req = leaveRepo.findById(id).orElseThrow();

        req.setStatus("REJECTED");

        leaveRepo.save(req);
    }
}