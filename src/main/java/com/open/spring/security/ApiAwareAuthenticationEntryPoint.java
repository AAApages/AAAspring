package com.open.spring.security;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Entry point for the catch-all (MVC) chain.
 *
 * The rule this enforces: an API request is never answered with an HTML login page.
 * A browser navigating to a protected page still gets the form-login redirect; a
 * programmatic caller gets JSON 401 instead, whatever path it happened to land on.
 *
 * This is the structural guarantee behind the URL rules -- if a request reaches this
 * chain that should have been handled by the API chain, it still fails as an API call.
 */
public class ApiAwareAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final AuthenticationEntryPoint browserEntryPoint;
    private final AuthenticationEntryPoint apiEntryPoint;

    public ApiAwareAuthenticationEntryPoint(String loginFormUrl, AuthenticationEntryPoint apiEntryPoint) {
        this.browserEntryPoint = new LoginUrlAuthenticationEntryPoint(loginFormUrl);
        this.apiEntryPoint = apiEntryPoint;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        if (expectsApiResponse(request)) {
            apiEntryPoint.commence(request, response, authException);
            return;
        }
        browserEntryPoint.commence(request, response, authException);
    }

    private boolean expectsApiResponse(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && (uri.startsWith("/api/") || uri.equals("/api"))) {
            return true;
        }
        if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            return true;
        }
        String accept = request.getHeader("Accept");
        if (accept == null || accept.isBlank()) {
            return false;
        }
        // A browser navigation asks for text/html first; fetch() defaults to */*
        // and an explicit JSON client asks for application/json.
        return !accept.contains("text/html") && accept.contains("application/json");
    }
}
