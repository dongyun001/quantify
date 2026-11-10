package com.quantify.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码加密。
 *
 * <p>只引了 spring-security-crypto 这一个 jar 拿 BCrypt，
 * 没有引 spring-boot-starter-security——那会把所有接口默认加上认证，
 * 还得反过来一个个配放行，跟 Sa-Token 也容易打架。
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
