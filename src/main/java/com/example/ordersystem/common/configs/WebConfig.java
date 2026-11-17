package com.example.ordersystem.common.configs;

import com.example.ordersystem.common.auth.BoundedPasswordEncoder;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class WebConfig {

    @Bean
    public PasswordEncoder makePassword() {

        return new BoundedPasswordEncoder(PasswordEncoderFactories.createDelegatingPasswordEncoder());
    }
}
