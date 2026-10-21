package com.example.hrm.controller;

import com.example.hrm.entity.LeaveRequest;
import com.example.hrm.service.LeaveService;
import com.example.hrm.service.UnitScope;
import com.example.hrm.entity.User;
import com.example.hrm.repository.LeaveRequestRepository;
import com.example.hrm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/leave")
@RequiredArgsConstructor
public class LeaveController {

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.service.NotificationService notificationService;   // 🔔 chuông thông báo

    private final LeaveService leaveService;
    private final LeaveRequestRepository leaveRequestRepository;
    private final UserRepository userRepository;
    private final UnitScope unitScope;

    /**
     * 🔒 Bản cũ: ai đăng nhập cũng gọi được /leave/approve/{id}. Nay chỉ người quản lý (trưởng phòng,
     * Giám đốc / Kế toán trưởng của đơn vị, HCNS, quản trị) CÙNG ĐƠN VỊ với người xin nghỉ, không tự duyệt.
     */
    private boolean canDecide(Long id) {
        User me = unitScope.currentUser();
        if (me == null) return false;
        var req = leaveRequestRepository.findById(id).orElse(null);
        if (req == null || req.getEmployeeNo() == null) return false;
        if (req.getEmployeeNo().equalsIgnoreCase(me.getEmployeeNo())) return false;
        User emp = userRepository.findByEmployeeNo(req.getEmployeeNo()).orElse(null);
        if (emp == null) return false;
        if (unitScope.isGlobalAdmin(me)) return true;
        String dept = me.getDepartment() == null ? "" : me.getDepartment().trim();
        boolean manager = Boolean.TRUE.equals(me.getDepartmentManager()) || unitScope.isUnitApprover(me)
                || UnitScope.isAdmin(me) || dept.equalsIgnoreCase("HCNS") || dept.contains("Hành chính");
        return manager && unitScope.sameUnit(me, emp);
    }

    private String getUser() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    // ==============================
    // LIST MY LEAVES
    // ==============================
    @GetMapping("/my")
    public String myLeaves(Model model) {

        String emp = getUser();

        model.addAttribute("leaves", leaveService.myLeaves(emp));
        model.addAttribute("newLeave", new LeaveRequest());

        return "my-leave";
    }

    // ==============================
    // CREATE LEAVE
    // ==============================
    @PostMapping("/create")
    public String create(@ModelAttribute LeaveRequest req) {

        leaveService.createLeave(req, getUser());

        return "redirect:/leave/my";
    }

    // ==============================
    // ADMIN APPROVE
    // ==============================
    @GetMapping("/approve/{id}")
    public String approve(@PathVariable Long id) {

        if (canDecide(id)) {
            leaveService.approve(id);
            notifyLeave(id, "ĐÃ DUYỆT");   // 🔔
        }

        return "redirect:/leave/my";
    }

    // ==============================
    // ADMIN REJECT
    // ==============================
    @GetMapping("/reject/{id}")
    public String reject(@PathVariable Long id) {

        if (canDecide(id)) {
            leaveService.reject(id);
            notifyLeave(id, "BỊ TỪ CHỐI");   // 🔔
        }

        return "redirect:/leave/my";
    }

    private void notifyLeave(Long id, String result) {
        leaveRequestRepository.findById(id).ifPresent(r -> notificationService.sendToEmployeeNo(r.getEmployeeNo(), "LEAVE",
                "Đơn nghỉ phép " + result,
                r.getFromDate() == null || r.getToDate() == null ? "Xem chi tiết đơn nghỉ phép"
                        : "Từ " + r.getFromDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM"))
                        + " đến " + r.getToDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                "/leave/my", null));
    }
}