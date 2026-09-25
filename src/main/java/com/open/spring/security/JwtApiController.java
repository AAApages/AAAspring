package com.open.spring.security;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonDetailsService;
import com.open.spring.mvc.person.PersonJpaRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
public class JwtApiController {

	@Autowired
	private AuthenticationManager authenticationManager;

	@Autowired
	private JwtTokenUtil jwtTokenUtil;

	@Autowired
	private PersonDetailsService personDetailsService;

	@Autowired
	private PersonJpaRepository personJpaRepository;

	@Autowired
	private CookieFactory cookieFactory;

	// Cookie attributes live in CookieFactory.

	@PostMapping("/authenticate")
	public ResponseEntity<?> createAuthenticationToken(@RequestBody Person authenticationRequest, HttpServletRequest request) throws Exception {
		String resolvedUid = resolveUid(authenticationRequest);
		if (resolvedUid == null) {
			return new ResponseEntity<>("Authentication failed: INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED);
		}
		try {
			authenticate(resolvedUid, authenticationRequest.getPassword());
		} catch (Exception e) {
			return new ResponseEntity<>("Authentication failed: " + e.getMessage(), HttpStatus.UNAUTHORIZED);
		}
		
		final UserDetails userDetails = personDetailsService
				.loadUserByUsername(resolvedUid);

		// Get the roles of the user
		List<String> roles = userDetails.getAuthorities().stream()
			.map(GrantedAuthority::getAuthority)
			.collect(Collectors.toList());

		// Generate the token with the roles
		final String token = jwtTokenUtil.generateToken(userDetails, roles);

		if (token == null) {
			return new ResponseEntity<>("Token generation failed", HttpStatus.INTERNAL_SERVER_ERROR);
		}

		// Built by CookieFactory so that login and logout always agree on name,
		// domain and path -- a delete cookie that differs in any of the three is a
		// different cookie, and the original survives logout.
		ResponseCookie tokenCookie = cookieFactory.jwtCookie(token);

		return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, tokenCookie.toString()).body(resolvedUid + " was authenticated successfully");
	}

	private String resolveUid(Person authenticationRequest) {
		if (authenticationRequest == null) {
			return null;
		}
		String uid = authenticationRequest.getUid();
		if (uid != null && !uid.isBlank()) {
			if (uid.contains("@")) {
				Person person = personJpaRepository.findByEmail(uid);
				return person != null ? person.getUid() : null;
			}
			return uid;
		}
		String email = authenticationRequest.getEmail();
		if (email != null && !email.isBlank()) {
			Person person = personJpaRepository.findByEmail(email);
			return person != null ? person.getUid() : null;
		}
		return null;
	}

	private void authenticate(String username, String password) throws Exception {
		try {
			authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password));
		} catch (DisabledException e) {
			throw new Exception("USER_DISABLED", e);
		} catch (BadCredentialsException e) {
			throw new Exception("INVALID_CREDENTIALS", e);
		} catch (Exception e) {
			throw new Exception(e);
		}
	}
	@RestController
	public class CustomLogoutController {

    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();
	
		@PostMapping("/api/logout")
		public String performLogout(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
			// Perform logout using SecurityContextLogoutHandler
			logoutHandler.logout(request, response, authentication);

			// Mirrors the cookies issued at login, domain included.
			ResponseCookie jwtCookie = cookieFactory.expiredJwtCookie();
			ResponseCookie sessionCookie = cookieFactory.expiredSessionCookie();

			// Set the cookies in the response to effectively "remove" them
			response.addHeader(HttpHeaders.SET_COOKIE, jwtCookie.toString());
			response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie.toString());
			// Also clears any pre-CookieFactory JWT cookie shape a browser might still be
			// holding (host-only, or Domain=localhost from local dev).
			response.addHeader(HttpHeaders.SET_COOKIE, cookieFactory.expiredJwtHostOnlyCookie().toString());
			response.addHeader(HttpHeaders.SET_COOKIE, cookieFactory.expiredJwtLegacyLocalhostCookie().toString());
	
			// Optional: You can also clear the "Authorization" header if needed
			response.setHeader("Authorization", null);
	
			// Redirect user to home page after logout
			return "redirect:/home";
		}
	}

}




	

