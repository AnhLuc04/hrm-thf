package com.example.hrm.controller;

import com.example.hrm.entity.User;
import com.example.hrm.repository.UserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.security.Principal;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * PHÂN QUYỀN các trang quản trị lương (cấu hình, công thức, khoản tháng, tính lương, ERP, nhật ký,
 * xem phiếu dự kiến của người khác). Nhân viên thường chỉ xem được /salary-forecast của chính mình.
 *
 * Được vào nếu: role thuộc payroll.admin.roles  HOẶC  phòng ban chứa 1 trong payroll.admin.departments
 * (so sánh không dấu, không phân biệt hoa thường).
 *
 *   payroll.security.enabled=true                    # false = tắt kiểm tra (VD đã phân quyền bằng Spring Security)
 *   payroll.admin.roles=ADMIN,ROLE_ADMIN,HR,KETOAN
 *   payroll.admin.departments=ke toan,nhan su
 */
@Configuration
public class PayrollAccessInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(PayrollAccessInterceptor.class);

    static final String[] ADMIN_PATHS = {
            "/salary-config", "/salary-config/**",
            "/salary-formula", "/salary-formula/**",
            "/salary-monthly", "/salary-monthly/**",
            "/payroll/auto-calculate", "/payroll/auto-calculate/**",
            "/salary-erp", "/salary-erp/**",
            "/salary-audit", "/salary-audit/**",
            "/salary-forecast/slip", "/salary-forecast/slip/**"
    };

    private final UserRepository userRepository;

    @Value("${payroll.security.enabled:true}")
    private boolean enabled = true;

    @Value("${payroll.admin.roles:ADMIN,ROLE_ADMIN,HR,ROLE_HR,KETOAN,ROLE_KETOAN,ACCOUNTANT,ROLE_ACCOUNTANT}")
    private String roles = "ADMIN,ROLE_ADMIN";

    // HCNS = Hành chính nhân sự (bản cũ vào được vì mọi tài khoản đều mang quyền ADMIN)
    @Value("${payroll.admin.departments:ke toan,nhan su,hcns}")
    private String departments = "ke toan,nhan su";

    @Value("${payroll.security.login-url:/login}")
    private String loginUrl = "/login";

    @Value("${payroll.security.denied-url:/?error=no-permission}")
    private String deniedUrl = "/?error=no-permission";

    public PayrollAccessInterceptor(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(ADMIN_PATHS);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!enabled) {
            return true;
        }
        Principal principal = request.getUserPrincipal();
        if (principal == null) {
            response.sendRedirect(request.getContextPath() + loginUrl);
            return false;
        }
        String login = principal.getName();
        User user = userRepository.findByUsername(login)
                .orElseGet(() -> userRepository.findByEmployeeNo(login).orElse(null));

        if (allowed(user, request)) {
            return true;
        }
        log.warn("CHAN TRUY CAP LUONG: {} -> {}", login, request.getRequestURI());
        response.sendRedirect(request.getContextPath() + deniedUrl);
        return false;
    }

    boolean allowed(User user, HttpServletRequest request) {
        // Role của Spring Security (nếu có)
        for (String r : list(roles)) {
            if (request != null && request.isUserInRole(r.startsWith("ROLE_") ? r.substring(5) : r)) {
                return true;
            }
        }
        if (user == null) {
            return false;
        }
        String role = user.getRole() == null ? "" : norm(String.valueOf(user.getRole()));
        for (String r : list(roles)) {
            if (!role.isEmpty() && role.equals(norm(r))) {
                return true;
            }
        }
        String dept = norm(user.getDepartment());
        for (String d : list(departments)) {
            if (!dept.isEmpty() && dept.contains(norm(d))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> list(String csv) {
        return csv == null ? List.of() : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** "Kế Toán" -> "ke toan" */
    static String norm(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}