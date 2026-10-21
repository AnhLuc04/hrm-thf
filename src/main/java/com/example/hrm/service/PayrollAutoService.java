package com.example.hrm.service;

import com.example.hrm.entity.PayrollAutoMark;
import com.example.hrm.repository.PayrollAutoMarkRepository;
import com.example.hrm.repository.PayrollCalcRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tiện ích cho phiếu lương TỰ TÍNH, dùng từ code có sẵn (PayrollService, PayrollController)
 * mà không phải sửa nhiều.
 */
@Service
public class PayrollAutoService {

    private final PayrollAutoMarkRepository markRepository;
    private final PayrollCalcRepository payrollRepository;

    public PayrollAutoService(PayrollAutoMarkRepository markRepository, PayrollCalcRepository payrollRepository) {
        this.markRepository = markRepository;
        this.payrollRepository = payrollRepository;
    }

    /**
     * Gọi trong PayrollService.importExcel TRƯỚC khi kiểm tra trùng:
     * nếu nhân viên đang có phiếu TẠM TÍNH (tự động) của tháng đó thì xoá đi để phiếu Excel của Kế toán thay thế.
     * Phiếu do Kế toán import trước đó (không có dấu tự động) thì giữ nguyên -> vẫn báo "Trùng" như cũ.
     *
     * @return true nếu đã xoá phiếu tạm tính
     */
    @Transactional
    public boolean releaseForImport(Long userId, Integer month, Integer year) {
        return markRepository.findFirstByUserIdAndMonthAndYear(userId, month, year)
                .map(m -> {
                    payrollRepository.deleteById(m.getPayrollId());
                    markRepository.deleteById(m.getId());
                    return true;
                })
                .orElse(false);
    }

    /** Thông tin tự tính của 1 phiếu (null = phiếu import / nhập tay) - hiện nhãn "Tạm tính" trên phiếu */
    public PayrollAutoMark info(Long payrollId) {
        return payrollId == null ? null : markRepository.findByPayrollId(payrollId).orElse(null);
    }
}