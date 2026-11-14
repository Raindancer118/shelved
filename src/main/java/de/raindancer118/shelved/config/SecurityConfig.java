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
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Configuration
public class SecurityConfig {

    static final String CSP = "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; style-src 'self'; "
            + "script-src 'self'; font-src 'self'; connect-src 'self'; manifest-src 'self'; worker-src 'self'; "
            + "frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ShelvedProperties props,
                                    ObjectProvider<ClientRegistrationRepository> registrations) throws Exception {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/manifest.webmanifest",
                                "/icons/**", "/fonts/**", "/favicon.svg")
                        .permitAll()
                        .anyRequest().authenticated())
                .csrf(c -> c.spa())
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .permissionsPolicyHeader(p -> p.policy("camera=(self), microphone=(), geolocation=(), payment=()")))
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")));

        if (props.localAuth()) {
            http.addFilterBefore(new LocalUserFilter(), AuthorizationFilter.class);
        } else {
            if (registrations.getIfAvailable() == null) {
                throw new IllegalStateException("shelved.auth-mode=oidc but no OIDC client is configured (use profile 'prod' or 'local')");
            }
            http.exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                            new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/authentik"), AnyRequestMatcher.INSTANCE))
                    .oauth2Login(Customizer.withDefaults())
                    .logout(l -> l.logoutSuccessUrl("/"));
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
