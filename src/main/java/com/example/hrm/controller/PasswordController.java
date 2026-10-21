package com.example.hrm.controller;

import com.example.hrm.dto.ChangePasswordRequest;
import com.example.hrm.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class PasswordController {

    private final UserService userService;

    @PostMapping("/change-password")
    public String changePassword(ChangePasswordRequest request, RedirectAttributes redirectAttributes) {
        try {
            userService.changePassword(request);
            redirectAttributes.addFlashAttribute("message", "Đổi mật khẩu thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/profile"; // Chuyển hướng về trang profile hoặc trang bạn mong muốn
    }
}