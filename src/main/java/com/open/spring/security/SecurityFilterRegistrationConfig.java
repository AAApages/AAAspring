package com.open.spring.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JwtRequestFilter and RateLimitFilter are @Component beans, so Spring Boot registers
 * them as global servlet filters in addition to the places SecurityConfig adds them to
 * the API chain. That made them run on every request in the application -- including
 * /login and the MVC pages, which the API chain never touches.
 *
 * Registering them explicitly with setEnabled(false) removes the global registration
 * and leaves only the deliberate placement inside the security filter chain.
 */
@Configuration
public class SecurityFilterRegistrationConfig {

    @Bean
    public FilterRegistrationBean<JwtRequestFilter> disableAutoRegisteredJwtFilter(JwtRequestFilter filter) {
        FilterRegistrationBean<JwtRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> disableAutoRegisteredRateLimitFilter(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
