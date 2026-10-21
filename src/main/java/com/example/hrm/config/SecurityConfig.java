package com.example.hrm.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.UserDetailsService;

import com.example.hrm.entity.AppSetting;
import com.example.hrm.repository.AppSettingRepository;



@Configuration
public class SecurityConfig {



    @Bean
    public PasswordEncoder passwordEncoder() {

        return new BCryptPasswordEncoder();

    }





    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            UserDetailsService userDetailsService,
            AppSettingRepository settings
    ) throws Exception {


        http

                .csrf(csrf -> csrf.disable())


                .authorizeHttpRequests(auth -> auth



                        // Public
                        .requestMatchers(
                                "/login",
                                "/test",
                                // app cài trên điện thoại (PWA): manifest, service worker, biểu tượng, trang mất mạng
                                "/manifest.webmanifest",
                                "/sw.js",
                                "/pwa/**",
                                "/favicon.ico",
                                "/apple-touch-icon.png",
                                "/apple-touch-icon-precomposed.png",
                                "/sunclub-ca.cer",
                                "/sunclub.mobileconfig",
                                "/offline.html",
                                "/css/**",
                                "/js/**",
                                "/uploads/**"
                        )
                        .permitAll()





                        // ADMIN

                        // Bản cũ: hasAnyAuthority(ADMIN) nhưng mọi tài khoản đều được gán ADMIN.
                        // Nay quyền là thật, nên các trang này mở cho người đã đăng nhập và TỰ KIỂM TRA
                        // phòng ban / đơn vị trong controller (HCNS, Kế toán, quản trị...) như trước.
                        .requestMatchers(
                                "/admin/**",
                                "/holiday/**",
                                "/payroll/**",
                                "/units/**"
                        )
                        .authenticated()





                        // Các request còn lại

                        .anyRequest()
                        .authenticated()



                )



                .formLogin(form -> form

                        .loginPage("/login")

                        .defaultSuccessUrl(
                                "/",
                                true
                        )

                        .permitAll()

                )



                // LƯU ĐĂNG NHẬP 30 NGÀY: tích ô "Lưu đăng nhập 30 ngày" ở trang đăng nhập.
                // Đổi mật khẩu hoặc bấm Đăng xuất thì cookie cũ hết hiệu lực.
                .rememberMe(remember -> remember
                        .key(rememberMeKey(settings))
                        .rememberMeParameter("remember-me")
                        .rememberMeCookieName("sunclub-remember")
                        .tokenValiditySeconds(REMEMBER_DAYS * 24 * 60 * 60)
                        .useSecureCookie(true)
                        .userDetailsService(userDetailsService)
                )

                .logout(logout -> logout

                        .logoutUrl("/logout")

                        .deleteCookies("JSESSIONID", "sunclub-remember")

                        .logoutSuccessUrl("/login")

                        .invalidateHttpSession(true)

                        .clearAuthentication(true)

                )



                .exceptionHandling(exception -> exception

                        .accessDeniedPage("/")

                );



        return http.build();

    }

    /** Số ngày giữ đăng nhập khi tích "Lưu đăng nhập" */
    static final int REMEMBER_DAYS = 30;

    /**
     * Khoá ký cookie "lưu đăng nhập": tạo ngẫu nhiên 1 lần, cất trong bảng app_setting,
     * nên khởi động lại phần mềm KHÔNG làm mọi người bị đăng xuất.
     * Muốn bắt tất cả đăng nhập lại: xoá dòng remember_me_key trong bảng app_setting rồi khởi động lại.
     */
    private static String rememberMeKey(AppSettingRepository settings) {
        try {
            AppSetting s = settings.findById("remember_me_key").orElse(null);
            if (s != null && s.getValue() != null && s.getValue().length() >= 32) {
                return s.getValue();
            }
            byte[] b = new byte[48];
            new java.security.SecureRandom().nextBytes(b);
            String key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            settings.save(new AppSetting("remember_me_key", key));
            return key;
        } catch (RuntimeException e) {
            // chưa có bảng (lần chạy đầu) -> dùng khoá tạm, lần sau sẽ lưu được
            byte[] b = new byte[48];
            new java.security.SecureRandom().nextBytes(b);
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        }
    }


}