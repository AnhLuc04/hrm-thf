package com.example.hrm.config;

import com.example.hrm.entity.User;
import com.example.hrm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {

        if (userRepository.count() == 0) {

            // ADMIN
            userRepository.save(User.builder()
                    .username("admin")
                    .password(passwordEncoder.encode("123456"))
                    .role("ADMIN")
                    .fullName("Administrator")
                    .department("SYSTEM")
                    .employeeNo("0000")
                    .employeeType("CHINH_THUC")
                    .departmentManager(true)
                    .build());

            // Dịch Vụ
            userRepository.save(User.builder()
                    .username("dv_9999")
                    .password(passwordEncoder.encode("123456"))
                    .role("USER")
                    .fullName("Mai Văn Thịnh")
                    .department("Dịch Vụ")
                    .employeeNo("9999")
                    .employeeType("CHINH_THUC")
                    .departmentManager(false)
                    .build());

            // Ban Giám Đốc
            userRepository.save(User.builder()
                    .username("gdkd_99999")
                    .password(passwordEncoder.encode("123456"))
                    .role("USER")
                    .fullName("gdkd")
                    .department("Ban Giám Đốc")
                    .employeeNo("99999")
                    .employeeType("CHINH_THUC")
                    .departmentManager(true)
                    .build());

            // Kế Toán
            userRepository.save(User.builder()
                    .username("kt_999999")
                    .password(passwordEncoder.encode("123456"))
                    .role("USER")
                    .fullName("Phan Thị Khuyên")
                    .department("Kế Toán")
                    .employeeNo("999999")
                    .employeeType("CHINH_THUC")
                    .departmentManager(false)
                    .build());

            System.out.println(">>> CREATED DEFAULT USERS <<<");
        }
    }
}