package com.example.hrm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "device.api")
public class HikvisionConfig {

    private String url;

    private String username;

    private String password;
}