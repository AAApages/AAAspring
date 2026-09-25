package com.open.spring.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.open.spring.mvc.person.PersonDetailsService;

import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtRequestFilter extends OncePerRequestFilter {

	@Autowired
	private PersonDetailsService personDetailsService;

	@Autowired
	private JwtTokenUtil jwtTokenUtil;

	/**
	 * This method is responsible for building a log message for the incoming HTTP request.
	 * @param request  the incoming HTTP request
	 * @return a string containing the request URI, method, remote address, remote host, and remote port
	 */	
	private String buildRequestLogMessage(HttpServletRequest request) {
    	return request.getRequestURI() + " " + request.getMethod() + " " + request.getRemoteAddr() + " " + request.getRemoteHost() + " " + request.getRemotePort();
	}

	/**
	 * This method works with stateless authentication. 
	 * It validates the JWT token and sets the authentication.
	 * @param request  the incoming HTTP request
	 * @param response
	 * @param chain
	 * @throws IOException
	 * @throws ServletException
	 */
	private void handleClientRequest(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
		Optional<String> jwtToken = getJwtTokenFromCookies(request.getCookies());
	
		if (!jwtToken.isPresent()) {
			logger.warn("No JWT cookie: " + buildRequestLogMessage(request));
			chain.doFilter(request, response);
			return;
		}

		// If there is a JWT token, extract the username and set the authentication
		try {
			String username = jwtTokenUtil.getUsernameFromToken(jwtToken.get());
	
			if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
				UserDetails userDetails = this.personDetailsService.loadUserByUsername(username);
	
				if (jwtTokenUtil.validateToken(jwtToken.get(), userDetails)) {
					UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
					usernamePasswordAuthenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
					SecurityContextHolder.getContext().setAuthentication(usernamePasswordAuthenticationToken);
					logger.warn("Cookie: " + userDetails.getUsername() + " " + userDetails.getAuthorities());
				}
			}
		} catch (IllegalArgumentException e) {
			logger.warn("JWT token could not be read; continuing unauthenticated: " + e.getMessage());
		} catch (ExpiredJwtException e) {
			logger.warn("JWT token expired for request: " + buildRequestLogMessage(request));
		} catch (Exception e) {
			logger.warn("JWT token was rejected; continuing unauthenticated: " + e.getMessage());
		}

		// A bad token must not end the request here.
		//
		// response.sendError() triggers a container ERROR dispatch, which re-enters the
		// filter chain as /error and is answered by the MVC chain with a 302 to the HTML
		// login page. It also broke permitAll endpoints: a stale cookie left in the browser
		// stopped signup from working at all.
		//
		// Instead leave the context unauthenticated and continue. AuthorizationFilter then
		// allows permitAll endpoints through and denies protected ones, and
		// JwtAuthenticationEntryPoint writes the JSON 401 directly (no error dispatch).
		chain.doFilter(request, response);

	}

	/**
	 * This method is responsible for filtering incoming HTTP requests. 
	 * API reuests are handled by the handleClientRequest method.
	 * @param request  the incoming HTTP request
	 * @param response the HTTP response
	 * @param chain    the filter chain
	 */
	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain) throws ServletException, IOException {
		// Skip JWT validation for authentication endpoint
		if ("/authenticate".equals(request.getRequestURI())) {
			chain.doFilter(request, response);
			return;
		}

		String requestUri = request.getRequestURI();
		if (requestUri != null && requestUri.startsWith("/api/")) {
			logger.warn("API request: " + buildRequestLogMessage(request));
			handleClientRequest(request, response, chain);
			return;
		}

		logger.warn("Session request: " + buildRequestLogMessage(request));
		chain.doFilter(request, response);
	}
	
	/**
	 * This method is responsible for extracting the JWT token from the cookies. It returns an Optional<String> that
	 * contains the JWT token if it exists, or an empty Optional if it doesn't exist.
	 *
	 * @param cookies the array of cookies from the HTTP request
	 * @return an Optional<String> containing the JWT token, or an empty Optional if the token doesn't exist
	 */
	private Optional<String> getJwtTokenFromCookies(Cookie[] cookies) {
		if (cookies == null || cookies.length == 0) {
			//logger.warn("No cookies");
			return Optional.empty();
		}
	
		return Arrays.stream(cookies)
			.filter(cookie -> cookie.getName().equals("jwt_java_spring"))
			.map(Cookie::getValue)
			.findFirst();
	}
}