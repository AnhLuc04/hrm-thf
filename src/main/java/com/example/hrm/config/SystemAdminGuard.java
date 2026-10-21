package com.example.hrm.config;

import com.example.hrm.entity.User;
import com.example.hrm.service.UnitScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * QUẢN TRỊ HỆ THỐNG CHỈ LÀM 3 VIỆC:
 *   1. Nhập đơn vị + API máy chấm công (/units)
 *   2. Tạo tài khoản admin / user (/system/accounts)
 *   3. Chia người vào đơn vị (/system/accounts)
 * (+ đăng bảng tin toàn hệ thống, đổi mật khẩu của mình)
 *
 * Phân quyền trong đơn vị (Giám đốc / trưởng phòng / nhân viên, phòng ban), lịch làm việc, giờ chấm công,
 * lương... là việc của HCNS / Kế toán từng đơn vị, nên tài khoản quản trị hệ thống mở các trang đó sẽ bị
 * chuyển về trang Tài khoản.
 *
 * Tắt (quay lại như bản trước, admin vào được mọi trang): hrm.system-admin.restricted=false
 */
@Configuration
public class SystemAdminGuard implements HandlerInterceptor, WebMvcConfigurer {

    /** Đường dẫn quản trị hệ thống được mở (đúng đường dẫn hoặc đường dẫn con) */
    static final List<String> ALLOWED = List.of(
            "/units", "/system", "/news", "/notifications", "/push", "/manifest.webmanifest", "/sw.js", "/pwa", "/offline.html", "/profile", "/change-password", "/password",
            "/login", "/logout", "/error", "/uploads", "/api/hikvision", "/attendance/sync",
            "/favicon.ico", "/apple-touch-icon.png", "/apple-touch-icon-precomposed.png", "/sunclub-ca.cer", "/sunclub.mobileconfig");

    private final UnitScope unitScope;

    @Value("${hrm.system-admin.restricted:true}")
    private boolean restricted = true;

    public SystemAdminGuard(UnitScope unitScope) {
        this.unitScope = unitScope;
    }

    public boolean isRestricted() {
        return restricted;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!restricted || request.getUserPrincipal() == null) {
            return true;
        }
        User me = unitScope.currentUser();
        if (me == null || !unitScope.isGlobalAdmin(me)) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (allowed(path)) {
            return true;
        }
        String ctx = request.getContextPath();
        if (path.isEmpty() || "/".equals(path)) {
            response.sendRedirect(ctx + "/units");
        } else {
            response.sendRedirect(ctx + "/system/accounts?denied=1");
        }
        return false;
    }

    static boolean allowed(String path) {
        for (String p : ALLOWED) {
            if (path.equals(p) || path.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }
}
