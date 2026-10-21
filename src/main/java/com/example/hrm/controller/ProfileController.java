
        package com.example.hrm.controller;

import com.example.hrm.entity.User;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.security.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class ProfileController {

    private final UserRepository userRepository;

    /**
     * Trang thông tin cá nhân
     */
    @GetMapping("/profile")
    public String showProfile(Model model) {

        String username = SecurityUtil.getCurrentEmployeeNo();

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        model.addAttribute("user", user);

        return "profile";
    }

    /**
     * Cập nhật mã lương
     */
    @PostMapping("/profile/update-salary-code")
    public String updateSalaryCode(
            @RequestParam("salaryCode") String salaryCode,
            RedirectAttributes redirectAttributes) {

        String username = SecurityUtil.getCurrentEmployeeNo();

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Loại bỏ khoảng trắng đầu/cuối
        salaryCode = salaryCode.trim();

        // Không cho để trống
        if (salaryCode.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "salaryCodeError",
                    "Mã lương không được để trống!"
            );

            return "redirect:/profile";
        }

        // Kiểm tra mã lương đã được người khác sử dụng chưa
        boolean exists = userRepository.existsBySalaryCodeAndUsernameNot(
                salaryCode,
                username
        );

        if (exists) {
            redirectAttributes.addFlashAttribute(
                    "salaryCodeError",
                    "Mã lương này đã được sử dụng bởi nhân viên khác!"
            );

            return "redirect:/profile";
        }

        // Cập nhật
        user.setSalaryCode(salaryCode);
        userRepository.save(user);

        redirectAttributes.addFlashAttribute(
                "salaryCodeMessage",
                "Cập nhật mã lương thành công!"
        );

        return "redirect:/profile";
    }
}
