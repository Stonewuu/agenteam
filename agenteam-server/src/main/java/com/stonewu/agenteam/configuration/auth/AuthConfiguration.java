package com.stonewu.agenteam.configuration.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.ZoneOffset;

/**
 * 基础登录使用的密码哈希配置。
 */
@Configuration
public class AuthConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new AccountPasswordEncoder();
    }

    @Bean
    public Clock authClock() {
        return Clock.tickMillis(ZoneOffset.UTC);
    }
}
