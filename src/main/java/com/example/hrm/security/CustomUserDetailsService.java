package com.example.hrm.security;

import com.example.hrm.entity.User;
import com.example.hrm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService
        implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(
            String username
    ) throws UsernameNotFoundException {

        User user =
                userRepository.findByUsername(username)
                        .orElseThrow(() ->
                                new UsernameNotFoundException(
                                        "User not found"
                                )
                        );

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPassword())
                .roles(rolesOf(user))
                .build();
    }

    /**
     * QUYỀN THẬT theo users.role. Bản cũ gán ADMIN cho MỌI tài khoản, nên nhân viên nào cũng mở được trang
     * cấu hình / tính lương của cả công ty (PayrollAccessInterceptor coi ai có ROLE_ADMIN là được vào).
     * Các trang quản trị vẫn tự kiểm tra phòng ban (HCNS, Kế toán...) như cũ.
     */
    static String[] rolesOf(com.example.hrm.entity.User user) {
        String r = roleOf(user);
        // quản trị toàn hệ thống cũng là ADMIN với các trang cũ
        return "SUPER_ADMIN".equals(r) ? new String[]{"SUPER_ADMIN", "ADMIN"} : new String[]{r};
    }

    static String roleOf(com.example.hrm.entity.User user) {
        String r = user.getRole() == null ? "" : user.getRole().trim().toUpperCase();
        if (r.startsWith("ROLE_")) {
            r = r.substring(5);
        }
        if ("admin".equalsIgnoreCase(user.getUsername() == null ? "" : user.getUsername().trim())
                || "ADMIN".equalsIgnoreCase(user.getDepartment() == null ? "" : user.getDepartment().trim())) {
            return "ADMIN";
        }
        return r.isEmpty() ? "USER" : r;
    }
}