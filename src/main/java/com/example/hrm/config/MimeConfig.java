package com.example.hrm.config;

import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MimeConfig {
    @Bean
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> mimeCustomizer() {
        return factory -> {
            MimeMappings m = new MimeMappings(MimeMappings.DEFAULT);
            m.add("mobileconfig", "application/x-apple-aspen-config");
            m.add("crt", "application/x-x509-ca-cert");
            factory.setMimeMappings(m);
        };
    }
}