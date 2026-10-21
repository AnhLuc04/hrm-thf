package com.example.hrm.repository;

import com.example.hrm.entity.ConfirmExtension;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ConfirmExtensionRepository extends JpaRepository<ConfirmExtension, Long> {

    /** Lần mở lại còn hiệu lực của 1 nhân viên */
    List<ConfirmExtension> findByEmployeeNoAndOpenUntilGreaterThanEqual(String employeeNo, LocalDate today);

    /** Mọi lần mở lại còn hiệu lực (trang quản lý lọc tiếp theo đơn vị) */
    List<ConfirmExtension> findByOpenUntilGreaterThanEqualOrderByOpenUntilAsc(LocalDate today);
}
