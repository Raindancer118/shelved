package de.raindancer118.shelved.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Configuration
public class SecurityConfig {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SecurityConfig.class);

    static final String CSP = "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; style-src 'self'; "
            + "script-src 'self'; font-src 'self'; connect-src 'self'; manifest-src 'self'; worker-src 'self'; "
            + "frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ShelvedProperties props,
                                    ObjectProvider<ClientRegistrationRepository> registrations,
                                    de.raindancer118.shelved.library.LibraryService libraries) throws Exception {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/auth/**", "/api/csrf").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/invitations/*").permitAll()
                        .requestMatchers("/api/**", "/actuator/**").authenticated()
                        // the app shell itself is public; it shows the login page when /api answers 401
                        .anyRequest().permitAll())
                .csrf(c -> c.spa())
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .permissionsPolicyHeader(p -> p.policy("camera=(self), microphone=(), geolocation=(), payment=()")))
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), AnyRequestMatcher.INSTANCE))
                .logout(l -> l.logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpStatus.NO_CONTENT.value())))
                .addFilterAfter(new de.raindancer118.shelved.library.TenantFilter(libraries), AuthorizationFilter.class);

        if (props.localAuth()) {
            http.addFilterBefore(new LocalUserFilter(), AuthorizationFilter.class);
        } else if (props.oidcAuth()) {
            if (registrations.getIfAvailable() == null) {
                throw new IllegalStateException("shelved.auth-mode=oidc but no OIDC client is configured (use profile 'prod', 'accounts' or 'local')");
            }
            http.oauth2Login(o -> o
                    .defaultSuccessUrl("/", true)
                    .failureHandler((request, response, ex) -> {
                        // Spring only logs this at DEBUG; without it a broken IdP setup is invisible
                        log.warn("OIDC login failed: {}", ex.getMessage());
                        response.sendRedirect("/#/login?error=sso");
                    }));
        }
        return http.build();
    }

    /** Dev mode: every request is the user "local". Never active unless shelved.auth-mode=local. */
    static class LocalUserFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var current = SecurityContextHolder.getContext().getAuthentication();
            if (current == null || current instanceof AnonymousAuthenticationToken) {
                var auth = UsernamePasswordAuthenticationToken.authenticated("local", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            chain.doFilter(request, response);
        }
    }
}
