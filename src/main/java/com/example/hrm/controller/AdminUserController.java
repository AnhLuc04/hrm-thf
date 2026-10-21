
        package com.example.hrm.controller;

import com.example.hrm.entity.LeaveBalance;
import com.example.hrm.entity.User;
import com.example.hrm.repository.LeaveBalanceRepository;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.repository.CompanyUnitRepository;
import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.service.UnitScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserRepository userRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final UnitScope unitScope;                      // ĐƠN VỊ: HCNS chỉ quản lý nhân viên đơn vị mình
    private final CompanyUnitRepository unitRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.service.AttendanceService attendanceService;   // đổi phòng / trưởng phòng -> chuyển đơn đang chờ

    /** Sau khi đổi phòng ban / trưởng phòng / đơn vị: đơn giải trình ĐANG CHỜ của cả phòng cũ và mới chuyển sang đúng người duyệt */
    private void rerouteAfterChange(java.util.Collection<Long> ids, java.util.Collection<String> oldDepts) {
        try {
            entityManager.flush();
            entityManager.clear();
            java.util.Set<String> depts = new java.util.HashSet<>();
            oldDepts.stream().filter(d -> d != null && !d.isBlank()).forEach(d -> depts.add(d.trim()));
            java.util.List<User> changed = userRepository.findAllById(ids);
            changed.forEach(u -> { if (u.getDepartment() != null) depts.add(u.getDepartment().trim()); });
            java.util.Map<Long, User> affected = new java.util.LinkedHashMap<>();
            changed.forEach(u -> affected.put(u.getId(), u));
            for (User u : userRepository.findAll()) {   // cả phòng: trưởng phòng đổi thì nhân viên trong phòng đổi người duyệt
                if (u.getDepartment() != null && depts.contains(u.getDepartment().trim())) {
                    affected.put(u.getId(), u);
                }
            }
            attendanceService.rerouteOpenConfirmations(affected.values());
        } catch (RuntimeException e) {
            // không làm hỏng việc lưu nhân viên
        }
    }

    // 💡 HÀM KIỂM TRA: Chỉ cho phép người thuộc phòng "HCNS" hoặc Admin truy cập
    private boolean isHrDepartment(Principal principal) {
        if (principal == null) return false;

        // Lấy thông tin user đang đăng nhập từ DB dựa vào username/mã nhân viên
        User currentUser = userRepository.findByUsername(principal.getName()).orElse(null);
        if (currentUser == null) return false;

        String dept = currentUser.getDepartment();
        // Quản trị hệ thống (quản lý cả 4 đơn vị) cũng được vào
        if (unitScope.isGlobalAdmin(currentUser)) return true;
        // Quản trị ĐƠN VỊ (role ADMIN của 1 đơn vị) làm việc như HCNS đơn vị mình
        if (UnitScope.isAdmin(currentUser)) return true;
        // Kiểm tra phòng ban (Bạn có thể sửa lại chuỗi "HCNS" cho khớp chính xác với DB của bạn)
        return dept != null && (dept.equalsIgnoreCase("HCNS") || dept.contains("Hành chính"));
    }

    @GetMapping
    public String viewUsersManagementPage(
            Principal principal,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "deptFilter", required = false) String deptFilter,
            @RequestParam(value = "typeFilter", required = false) String typeFilter,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            Model model) {

        // 💡 Kiểm tra bộ phận, nếu không phải phòng nhân sự thì đá ngược về trang chủ
        if (!isHrDepartment(principal)) {
            return "redirect:/?error=no-permission";
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        Page<User> userPage;

        String cleanSearch = (search != null && !search.trim().isEmpty()) ? search.trim() : null;
        String cleanDept = (deptFilter != null && !deptFilter.trim().isEmpty()) ? deptFilter.trim() : null;
        String cleanType = (typeFilter != null && !typeFilter.trim().isEmpty()) ? typeFilter.trim() : null;

        // 🔒 ĐƠN VỊ: chỉ nhân viên của đơn vị đang xem (HCNS: đơn vị mình; quản trị hệ thống: đơn vị chọn ở menu)
        Long scopeUnit = unitScope.scopeUnitId();
        userPage = userRepository.findWithFilters(cleanSearch, cleanDept, cleanType, scopeUnit,
                scopeUnit != null && scopeUnit.equals(unitScope.legacyId()), pageable);

        List<User> filteredUsers = userPage.getContent();

        List<String> empNos = filteredUsers.stream()
                .map(User::getEmployeeNo)
                .filter(no -> no != null && !no.isBlank())
                .toList();

        // 🌟 THÊM MỚI: map lưu số phép còn lại (remaining) theo employeeNo, dùng để hiển thị cột "Phép Còn Lại"
        Map<String, Double> availableLeaveMap = new HashMap<>();

        if (!empNos.isEmpty()) {
            List<LeaveBalance> balances = leaveBalanceRepository.findByEmployeeNoIn(empNos);
            Map<String, LeaveBalance> balanceMap = balances.stream()
                    .collect(Collectors.toMap(LeaveBalance::getEmployeeNo, b -> b, (b1, b2) -> b1));

            for (User u : filteredUsers) {
                LeaveBalance balance = balanceMap.get(u.getEmployeeNo());
                u.setAnnualLeaveDays(balance != null ? balance.getTotalAnnualLeave() : 1.0);

                // 🌟 THÊM MỚI: đưa remaining vào map, nếu không có balance thì bỏ qua (giữ null ở view)
                if (balance != null) {
                    availableLeaveMap.put(u.getEmployeeNo(), balance.getRemaining());
                }
            }
        } else {
            filteredUsers.forEach(u -> u.setAnnualLeaveDays(1.0));
        }

        model.addAttribute("users", filteredUsers);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", userPage.getTotalPages());
        model.addAttribute("size", size);
        model.addAttribute("search", search);
        model.addAttribute("deptFilter", deptFilter);
        model.addAttribute("typeFilter", typeFilter);
        model.addAttribute("availableLeaveMap", availableLeaveMap);
        model.addAttribute("userPageTotalElements", userPage.getTotalElements());
        CompanyUnit su = unitScope.scopeUnit();
        model.addAttribute("scopeUnitName", su == null ? (unitRepository.count() > 1 ? "Tất cả đơn vị" : null) : su.getLabel());
        boolean canMoveUnit = unitScope.isGlobalAdmin(unitScope.find(principal.getName()));
        model.addAttribute("canMoveUnit", canMoveUnit);
        model.addAttribute("units", canMoveUnit ? unitRepository.findAllByOrderByCodeAsc() : List.of());

        return "users-management";
    }

    @PostMapping("/single-update")
    @ResponseBody
    @Transactional
    public ResponseEntity<String> updateSingleUserInline(
            Principal principal,
            @RequestParam("id") Long id,
            @RequestParam("fullName") String fullName,
            @RequestParam(value = "salaryCode", required = false) String salaryCode,
            @RequestParam("department") String department,
            @RequestParam("employeeType") String employeeType,
            @RequestParam("departmentManager") boolean departmentManager,
            @RequestParam("annualLeaveDays") String annualLeaveDaysStr,
            @RequestParam(value = "unitId", required = false) String unitIdStr) {

        if (!isHrDepartment(principal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Chỉ phòng HCNS mới có quyền chỉnh sửa!");
        }

        try {
            User user = userRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên!"));
            // 🔒 ĐƠN VỊ: không sửa nhân viên đơn vị khác
            if (!unitScope.inScope(user)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Nhân viên này thuộc đơn vị khác.");
            }

            /*
             * QUAN TRỌNG:
             * Không dùng userRepository.save(user) ở đây. Hibernate save(entity)
             * có thể phát sinh UPDATE toàn bộ users, bao gồm username/salary_code.
             * Nếu DB đang có dữ liệu '' trùng ở một cột UNIQUE thì sẽ lỗi
             * Duplicate entry ''. Chỉ UPDATE đúng các cột màn hình này cần.
             */
            entityManager.createNativeQuery("""
                    UPDATE users
                    SET full_name = ?,
                        department = ?,
                        employee_type = ?,
                        department_manager = ?
                    WHERE id = ?
                    """)
                    .setParameter(1, fullName == null ? "" : fullName.trim())
                    .setParameter(2, department == null ? "" : department.trim())
                    .setParameter(3, employeeType == null ? "" : employeeType.trim())
                    .setParameter(4, departmentManager)
                    .setParameter(5, id)
                    .executeUpdate();

            // Mã lương chỉ cập nhật khi người dùng thực sự nhập giá trị.
            // Không ghi chuỗi rỗng vào cột UNIQUE.
            if (salaryCode != null && !salaryCode.trim().isEmpty()) {
                entityManager.createNativeQuery("""
                        UPDATE users
                        SET salary_code = ?
                        WHERE id = ?
                        """)
                        .setParameter(1, salaryCode.trim())
                        .setParameter(2, id)
                        .executeUpdate();
            }

            moveUnit(principal, id, unitIdStr);
            updateLeaveBalance(user, employeeType, annualLeaveDaysStr);
            rerouteAfterChange(java.util.List.of(id), java.util.List.of(user.getDepartment() == null ? "" : user.getDepartment()));

            return ResponseEntity.ok("Cập nhật thành công!");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + rootMessage(e));
        }
    }

    @PostMapping("/bulk-update")
    @Transactional
    public String bulkUpdate(
            Principal principal,
            @RequestParam(value = "userIds", required = false) List<Long> userIds,
            @RequestParam(value = "selectAllFiltered", defaultValue = "false") boolean selectAllFiltered,
            @RequestParam(value = "targetDepartment", required = false) String targetDepartment,
            @RequestParam(value = "targetEmployeeType", required = false) String targetEmployeeType,
            @RequestParam(value = "targetIsManager", required = false) String targetIsManager,
            @RequestParam(value = "targetAnnualLeave", required = false) String targetAnnualLeaveStr,
            @RequestParam(value = "targetUnitId", required = false) String targetUnitIdStr,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "deptFilter", required = false) String deptFilter,
            @RequestParam(value = "typeFilter", required = false) String typeFilter,
            @RequestParam(value = "page", defaultValue = "0") int page,
            RedirectAttributes redirectAttributes) {

        if (!isHrDepartment(principal)) {
            return "redirect:/?error=no-permission";
        }

        try {
            String cleanSearch = clean(search);
            String cleanDept = clean(deptFilter);
            String cleanType = clean(typeFilter);

            List<User> users;
            if (selectAllFiltered) {
                users = userRepository.findWithFilters(
                        cleanSearch, cleanDept, cleanType, unitScope.scopeUnitId(),
                        unitScope.scopeUnitId() != null && unitScope.scopeUnitId().equals(unitScope.legacyId()),
                        Pageable.unpaged()).getContent();
            } else {
                if (userIds == null || userIds.isEmpty()) {
                    redirectAttributes.addFlashAttribute("bulkError", "Chưa chọn nhân viên nào để sửa.");
                    return redirectUrl(search, deptFilter, typeFilter, page);
                }
                users = userRepository.findAllById(userIds);
            }
            // 🔒 ĐƠN VỊ: bỏ những người không thuộc đơn vị đang quản lý
            users = unitScope.filter(users);

            if (users.isEmpty()) {
                redirectAttributes.addFlashAttribute("bulkError", "Không có nhân viên phù hợp để cập nhật.");
                return redirectUrl(search, deptFilter, typeFilter, page);
            }

            Double targetAnnualLeave = parseLeave(targetAnnualLeaveStr);
            if (targetAnnualLeaveStr != null && !targetAnnualLeaveStr.isBlank() && targetAnnualLeave == null) {
                redirectAttributes.addFlashAttribute("bulkError", "Số phép năm không hợp lệ.");
                return redirectUrl(search, deptFilter, typeFilter, page);
            }
            if (targetAnnualLeave != null && targetAnnualLeave < 0) {
                redirectAttributes.addFlashAttribute("bulkError", "Phép năm không được âm.");
                return redirectUrl(search, deptFilter, typeFilter, page);
            }

            boolean changeDepartment = targetDepartment != null && !targetDepartment.isBlank();
            boolean changeType = targetEmployeeType != null && !targetEmployeeType.isBlank();
            boolean changeManager = targetIsManager != null && !targetIsManager.isBlank();
            boolean changeUnit = targetUnitIdStr != null && !targetUnitIdStr.isBlank();

            for (User user : users) {
                String effectiveType = user.getEmployeeType();

                // Không save(User): chỉ update đúng cột cần đổi.
                if (changeDepartment) {
                    entityManager.createNativeQuery("""
                            UPDATE users SET department = ? WHERE id = ?
                            """)
                            .setParameter(1, targetDepartment.trim())
                            .setParameter(2, user.getId())
                            .executeUpdate();
                }

                if (changeType) {
                    entityManager.createNativeQuery("""
                            UPDATE users SET employee_type = ? WHERE id = ?
                            """)
                            .setParameter(1, targetEmployeeType.trim())
                            .setParameter(2, user.getId())
                            .executeUpdate();
                    effectiveType = targetEmployeeType;
                }

                if (changeManager) {
                    entityManager.createNativeQuery("""
                            UPDATE users SET department_manager = ? WHERE id = ?
                            """)
                            .setParameter(1, Boolean.parseBoolean(targetIsManager))
                            .setParameter(2, user.getId())
                            .executeUpdate();
                }

                if (changeUnit) {
                    moveUnit(principal, user.getId(), targetUnitIdStr);
                }
                // Chỉ cập nhật LeaveBalance nếu có mã nhân viên.
                if (user.getEmployeeNo() != null && !user.getEmployeeNo().isBlank()) {
                    updateLeaveBalance(user, effectiveType,
                            targetAnnualLeave == null ? null : String.valueOf(targetAnnualLeave));
                }
            }

            if (changeDepartment || changeManager || changeUnit) {
                rerouteAfterChange(users.stream().map(User::getId).toList(),
                        users.stream().map(u -> u.getDepartment() == null ? "" : u.getDepartment()).toList());
            }
            redirectAttributes.addFlashAttribute("bulkSuccess",
                    "Đã cập nhật " + users.size() + " nhân viên.");
        } catch (Exception e) {
            e.printStackTrace();
            redirectAttributes.addFlashAttribute("bulkError",
                    "Lỗi cập nhật: " + rootMessage(e));
        }

        return redirectUrl(search, deptFilter, typeFilter, page);
    }

    /** Chuyển đơn vị: chỉ quản trị hệ thống; giá trị trống thì bỏ qua */
    private void moveUnit(Principal principal, Long userId, String unitIdStr) {
        if (unitIdStr == null || unitIdStr.isBlank()) return;
        User me = unitScope.find(principal == null ? null : principal.getName());
        if (!unitScope.isGlobalAdmin(me)) return;
        Long unitId;
        try {
            unitId = Long.valueOf(unitIdStr.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (unitRepository.findById(unitId).isEmpty()) {
            throw new IllegalArgumentException("Đơn vị không tồn tại");
        }
        entityManager.createNativeQuery("UPDATE users SET unit_id = ? WHERE id = ?")
                .setParameter(1, unitId)
                .setParameter(2, userId)
                .executeUpdate();
    }

    private void updateLeaveBalance(User user, String employeeType, String annualLeaveDaysStr) {
        if (user.getEmployeeNo() == null || user.getEmployeeNo().isBlank()) return;

        LeaveBalance balance = leaveBalanceRepository.findByEmployeeNo(user.getEmployeeNo());
        double accrual = isProbationOrTraining(employeeType) ? 0.0 : 1.0;

        if (balance == null) {
            balance = new LeaveBalance();
            balance.setEmployeeNo(user.getEmployeeNo());
            balance.setUsedAnnualLeave(0.0);
            balance.setTotalAnnualLeave(1.0);
            balance.setRemaining(1.0);
        }

        balance.setAccrualPerMonth(accrual);

        if (annualLeaveDaysStr != null && !annualLeaveDaysStr.isBlank()) {
            double annualLeaveDays = Double.parseDouble(annualLeaveDaysStr);
            if (annualLeaveDays < 0) {
                throw new IllegalArgumentException("Phép năm không được âm");
            }
            balance.setTotalAnnualLeave(annualLeaveDays);
            double used = balance.getUsedAnnualLeave();
            balance.setRemaining(Math.max(0.0, annualLeaveDays - used));
        }

        leaveBalanceRepository.save(balance);
    }

    private boolean isProbationOrTraining(String type) {
        if (type == null) return false;
        String t = type.trim().toUpperCase();
        return t.contains("THU_VIEC") || t.contains("THU VIEC")
                || t.contains("HOC_VIEC") || t.contains("HOC VIEC");
    }

    private String clean(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private Double parseLeave(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String redirectUrl(String search, String deptFilter, String typeFilter, int page) {
        StringBuilder url = new StringBuilder("redirect:/admin/users?page=").append(Math.max(page, 0));
        if (search != null && !search.isBlank()) url.append("&search=").append(urlEncode(search));
        if (deptFilter != null && !deptFilter.isBlank()) url.append("&deptFilter=").append(urlEncode(deptFilter));
        if (typeFilter != null && !typeFilter.isBlank()) url.append("&typeFilter=").append(urlEncode(typeFilter));
        return url.toString();
    }

    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    private String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? e.getClass().getSimpleName() : t.getMessage();
    }

}
