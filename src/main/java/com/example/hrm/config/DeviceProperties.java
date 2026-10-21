package com.example.hrm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "device.api")
public class DeviceProperties {

    private String url;

    private String username;

    private String password;
}