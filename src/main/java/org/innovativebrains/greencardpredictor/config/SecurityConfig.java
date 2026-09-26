package org.innovativebrains.greencardpredictor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * FIX (2026-09-26): with no explicit security config, Spring Boot's default
 * auto-configuration required authentication for every request AND left CSRF
 * protection on for state-changing methods -- fine for a browser app using
 * cookie/session auth, but this is a stateless JSON REST API authenticated
 * per-request via HTTP Basic. That combination made every POST to
 * /api/prediction/predict fail with 401 even with correct credentials: the
 * request had no CSRF token (there's no HTML form to embed one in), and
 * Spring Security's CSRF filter runs as part of the same chain guarding
 * authentication for state-changing requests.
 *
 * CSRF protection defends against a browser silently reusing an
 * authenticated SESSION COOKIE across sites. It doesn't apply here: this API
 * has no login page/session-based auth flow, and every request must present
 * its own Basic Authorization header to succeed -- a malicious page can't
 * forge that header on a victim's behalf the way it can silently ride a
 * session cookie. So CSRF is disabled and sessions are stateless (no
 * JSESSIONID issued at all), which is the standard, secure pattern for a
 * Basic-auth-protected JSON API, not a security regression.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
