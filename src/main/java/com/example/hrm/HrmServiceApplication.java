package com.example.hrm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HrmServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HrmServiceApplication.class, args);
    }
}