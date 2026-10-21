package com.example.hrm.controller;

import com.example.hrm.entity.User;
import com.example.hrm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
@Controller
@RequiredArgsConstructor
public class HomeController {

    private final UserRepository userRepository; // đổi tên nếu repo của bạn khác
    /** Trang test: mở cho mọi người, kể cả chưa đăng nhập. */
    @GetMapping("/test")
    public String test(Model model) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean loggedIn = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());

        model.addAttribute("loggedIn", loggedIn);
        model.addAttribute("username", loggedIn ? auth.getName() : null);
        model.addAttribute("now", java.time.LocalDateTime.now());

        return "test"; // -> src/main/resources/templates/test.html
    }
    @GetMapping("/")
    public String home(Model model) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return "redirect:/login";
        }

        String username = auth.getName();
        boolean isAdmin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        boolean isManager = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_MANAGER"));

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy người dùng: " + username));

        model.addAttribute("username", username);
        model.addAttribute("fullName", user.getFullName());
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("isManager", isManager);

        return "index";
    }

}