package com.example.hrm.repository;

import com.example.hrm.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository
        extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);
    Optional<User> findByDepartmentAndDepartmentManagerTrue( String department );
    Optional<User> findByEmployeeNo(String employeeNo);
    @Query("SELECT u FROM User u LEFT JOIN u.unit un WHERE " +
            "(:search IS NULL OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(u.employeeNo) LIKE LOWER(CONCAT('%', :search, '%'))) AND " +
            "(:deptFilter IS NULL OR u.department = :deptFilter) AND " +
            "(:unitId IS NULL OR un.id = :unitId OR (un.id IS NULL AND :withUnassigned = true)) AND " +
            "(:typeFilter IS NULL OR " +
            "  (:typeFilter = 'CHINH_THUC' AND (u.employeeType IS NULL OR u.employeeType = '' OR u.employeeType = 'CHINH_THUC')) OR " +
            "  (u.employeeType = :typeFilter)" +
            ")")
    Page<User> findWithFilters(@Param("search") String search,
                               @Param("deptFilter") String deptFilter,
                               @Param("typeFilter") String typeFilter,
                               @Param("unitId") Long unitId,
                               @Param("withUnassigned") boolean withUnassigned,
                               Pageable pageable);

    // Dùng câu lệnh JPQL để tìm CHÍNH XÁC trưởng phòng của phòng ban truyền vào
    @Query("SELECT u FROM User u WHERE u.department = :department AND u.departmentManager = true")
    Optional<User> findDepartmentManager(@Param("department") String department);

    /** Mọi trưởng phòng của 1 phòng (4 đơn vị có thể trùng tên phòng) - lọc đơn vị ở service */
    @Query("SELECT u FROM User u WHERE u.department = :department AND u.departmentManager = true")
    List<User> findDepartmentManagers(@Param("department") String department);

    List<User> findByUnit_Id(Long unitId);

    long countByUnit_Id(Long unitId);

    @Query("SELECT u FROM User u WHERE u.department = 'Ban Giám Đốc Kinh Doanh' OR u.username = 'gdkd'")
    Optional<User> findBusinessDirector();




    boolean existsBySalaryCodeAndUsernameNot( String salaryCode, String username );
    Optional<User> findByAttendanceCode(
            String attendanceCode
    );


    boolean existsByEmployeeNo(
            String employeeNo
    );


    boolean existsByAttendanceCode(
            String attendanceCode
    );

    List<User> findByEmployeeNoIn(List<String> employeeNos);
}