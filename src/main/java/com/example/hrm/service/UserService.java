package com.example.hrm.service;

import com.example.hrm.dto.ChangePasswordRequest;
import com.example.hrm.entity.User;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.security.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class UserService {


    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;



    public void changePassword(
            ChangePasswordRequest request
    ) {


        String username =
                SecurityUtil.getCurrentEmployeeNo();



        User user =
                userRepository
                        .findByUsername(username)

                        .orElseThrow(
                                () -> new RuntimeException(
                                        "User not found"
                                )
                        );



        if(
                !passwordEncoder.matches(
                        request.getOldPassword(),
                        user.getPassword()
                )
        ){

            throw new RuntimeException(
                    "Mật khẩu cũ không chính xác!"
            );

        }




        if(
                !request.getNewPassword()
                        .equals(
                                request.getConfirmPassword()
                        )
        ){

            throw new RuntimeException(
                    "Mật khẩu mới không khớp!"
            );

        }




        user.setPassword(
                passwordEncoder.encode(
                        request.getNewPassword()
                )
        );



        userRepository.save(user);


    }


}