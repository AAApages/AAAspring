package com.open.spring.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for the auth cookies.
 *
 * A cookie is identified by the triple (name, domain, path). A delete cookie that
 * does not carry the same domain and path as the cookie it is trying to remove is
 * simply a different cookie, and the original survives. Every set/delete pair is
 * built here so those attributes cannot drift apart across call sites.
 */
@Component
public class CookieFactory {

    public static final String JWT_COOKIE = "jwt_java_spring";

    /** The JWT is only ever sent to the API chain, so it is scoped to /api. */
    private static final String JWT_PATH = "/api";

    private static final String PRODUCTION_DOMAIN = ".opencodingsociety.com";

    @Value("${jwt.cookie.secure:true}")
    private boolean cookieSecure;

    @Value("${jwt.cookie.same-site:None}")
    private String cookieSameSite;

    @Value("${jwt.cookie.max-age:604800}")
    private long cookieMaxAge;

    @Value("${server.servlet.session.cookie.name:sess_java_spring}")
    private String sessionCookieName;

    /**
     * Cross-subdomain sharing is only meaningful in production. Locally the cookie
     * must stay host-only: browsers reject a "localhost" domain attribute, and they
     * reject SameSite=None without Secure, which is the local configuration.
     */
    private ResponseCookie.ResponseCookieBuilder base(String name, String value, String path) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .path(path)
                .sameSite(cookieSecure ? cookieSameSite : "Lax");
        if (cookieSecure) {
            builder.domain(PRODUCTION_DOMAIN);
        }
        return builder;
    }

    /** Issued on successful authentication, by both /authenticate and MVC form login. */
    public ResponseCookie jwtCookie(String token) {
        return base(JWT_COOKIE, token, JWT_PATH).maxAge(cookieMaxAge).build();
    }

    /** Removes the cookie issued by {@link #jwtCookie(String)} -- same name, domain and path. */
    public ResponseCookie expiredJwtCookie() {
        return base(JWT_COOKIE, "", JWT_PATH).maxAge(0).build();
    }

    /**
     * The session cookie is written by Tomcat and is host-only, so its delete cookie
     * must be host-only too -- no domain here, deliberately.
     */
    public ResponseCookie expiredSessionCookie() {
        return ResponseCookie.from(sessionCookieName, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/")
                .maxAge(0)
                .sameSite(cookieSecure ? cookieSameSite : "Lax")
                .build();
    }

    /**
     * Older deployments set the JWT cookie without a domain (host-only). A browser
     * that still holds one of those needs it cleared too, or logout leaves it looking
     * logged in -- deliberately built without the domain() call {@link #jwtCookie}
     * always adds now.
     */
    public ResponseCookie expiredJwtHostOnlyCookie() {
        return ResponseCookie.from(JWT_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .path(JWT_PATH)
                .maxAge(0)
                .sameSite(cookieSecure ? cookieSameSite : "Lax")
                .build();
    }

    /**
     * Even older local-dev cookies were set with Domain=localhost. Harmless to send
     * in production (the domain won't match anything), but clears the zombie cookie
     * for anyone still carrying one from that era.
     */
    public ResponseCookie expiredJwtLegacyLocalhostCookie() {
        return ResponseCookie.from(JWT_COOKIE, "")
                .httpOnly(true)
                .secure(false)
                .path(JWT_PATH)
                .maxAge(0)
                .sameSite("Lax")
                .domain("localhost")
                .build();
    }
}
