package com.example.hrm.controller;

import com.example.hrm.entity.User;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.repository.CompanyUnitRepository;
import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.service.UnitScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.security.Principal;

/**
 * Đưa thông tin người đang đăng nhập vào MỌI trang Thymeleaf để menu dùng chung
 * (templates/fragments/shell.html) hiện đúng mục theo quyền.
 *
 *   navEmpNo      – mã nhân viên (dùng cho link "Giải trình công")
 *   navName       – họ tên
 *   navIsManager  – trưởng phòng (departmentManager = true)
 *   navIsAdmin    – quản trị (role/department ADMIN hoặc username admin)
 *   navIsAccountant – phòng Kế Toán (được nhập bảng lương)
 *   navIsHr       – phòng HCNS / Hành chính (cấu hình ngày nghỉ lễ)
 *
 * Tên thuộc tính có tiền tố "nav" để không đè lên biến sẵn có của các controller.
 * Chỉ áp dụng cho @Controller trả về view; bỏ qua khi chưa đăng nhập.
 */
@ControllerAdvice(annotations = Controller.class)
@RequiredArgsConstructor
public class NavModelAdvice {

    private final UserRepository userRepository;
    private final UnitScope unitScope;
    private final CompanyUnitRepository unitRepository;
    private final com.example.hrm.config.SystemAdminGuard systemAdminGuard;
    private final com.example.hrm.service.NotificationService notificationService;   // 🔔 số thông báo chưa đọc
    private final com.example.hrm.service.AttendanceService attendanceService;       // người duyệt phòng do HCNS giao   // quản trị hệ thống chỉ thấy menu hệ thống

    @ModelAttribute
    public void addNavUser(Model model, Principal principal) {
        if (principal == null) return;

        String login = principal.getName();
        User u = userRepository.findByEmployeeNo(login)
                .orElseGet(() -> userRepository.findByUsername(login).orElse(null));

        // Người duyệt cấp đơn vị (Giám đốc / GĐ dịch vụ / Kế toán trưởng của từng đơn vị) cũng thấy mục Quản lý
        boolean isManager = u != null && (Boolean.TRUE.equals(u.getDepartmentManager()) || unitScope.isUnitApprover(u)
                || attendanceService.isConfiguredApprover(u));
        boolean isAdmin = u != null && (
                "ADMIN".equalsIgnoreCase(trim(u.getRole()))
                        || "SUPER_ADMIN".equalsIgnoreCase(trim(u.getRole()))
                        || "ADMIN".equalsIgnoreCase(trim(u.getDepartment()))
                        || "admin".equalsIgnoreCase(trim(u.getUsername())));

        model.addAttribute("navEmpNo", u != null && u.getEmployeeNo() != null ? u.getEmployeeNo() : login);
        model.addAttribute("navName", u != null ? u.getFullName() : login);
        model.addAttribute("navIsManager", isManager);
        model.addAttribute("navIsAdmin", isAdmin);
        model.addAttribute("navIsAccountant", u != null && isAccountant(u.getDepartment()));
        model.addAttribute("navIsHr", u != null && isHr(u.getDepartment()));
        model.addAttribute("navUnread", u == null ? 0L : notificationService.unread(u.getId()));
        // VAI TRÒ hiển thị dưới tên (đúng lý do người này thấy mục Phê duyệt)
        String roleLabel = "Nhân viên";
        if (u != null) {
            if (unitScope.isGlobalAdmin(u)) roleLabel = "Quản trị hệ thống";
            else if (unitScope.isDirector(u)) roleLabel = "Giám đốc";
            else if (unitScope.isUnitApprover(u)) roleLabel = "Ban giám đốc";
            else if (Boolean.TRUE.equals(u.getDepartmentManager())) roleLabel = "Trưởng phòng";
            else if (isAdmin) roleLabel = "Quản trị đơn vị";
            else if (attendanceService.isConfiguredApprover(u)) roleLabel = "Người duyệt phòng";
            else if (isHr(u.getDepartment())) roleLabel = "HCNS";
            else if (isAccountant(u.getDepartment())) roleLabel = "Kế toán";
        }
        model.addAttribute("navRoleLabel", roleLabel);

        // ĐƠN VỊ: tên đơn vị trên menu; quản trị hệ thống được chọn đơn vị đang xem
        boolean global = unitScope.isGlobalAdmin(u);
        CompanyUnit mine = unitScope.unitOf(u);
        model.addAttribute("navIsGlobalAdmin", global);
        // QUẢN TRỊ HỆ THỐNG: chỉ menu Đơn vị & máy chấm công / Tài khoản & chia đơn vị / Bảng tin
        model.addAttribute("navSystemOnly", global && systemAdminGuard.isRestricted());
        if (global) {
            CompanyUnit view = unitScope.scopeUnit();
            model.addAttribute("navUnitLabel", view == null ? "Tất cả đơn vị" : view.getLabel());
            model.addAttribute("navUnits", unitRepository.findAllByOrderByCodeAsc());
            model.addAttribute("navViewUnitId", view == null ? null : view.getId());
        } else {
            model.addAttribute("navUnitLabel", mine == null ? null : mine.getLabel());
        }
    }

    private static boolean isAccountant(String dept) {
        String d = trim(dept).toLowerCase();
        return d.contains("kế toán") || d.equals("ke toan");
    }

    private static boolean isHr(String dept) {
        String d = trim(dept);
        return d.equalsIgnoreCase("HCNS") || d.contains("Hành chính");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}