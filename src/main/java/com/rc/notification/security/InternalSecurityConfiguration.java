package com.rc.notification.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.notification.api.ApiErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CallerProperties.class)
public class InternalSecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CallerProperties properties, ObjectMapper mapper)
            throws Exception {
        Set<String> tokens = new HashSet<>();
        Set<String> identities = new HashSet<>();
        for (var caller : properties.callers()) {
            if (!tokens.add(caller.token()) || !identities.add(caller.id())) {
                throw new IllegalArgumentException("Caller identities and credentials must be unique");
            }
        }
        return http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable()).formLogin(form -> form.disable())
                .logout(logout -> logout.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/health/live", "/health/ready").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> ApiErrors.write(mapper, request, response,
                                401, "UNAUTHENTICATED", "Valid internal credentials are required"))
                        .accessDeniedHandler((request, response, failure) -> ApiErrors.write(mapper, request, response,
                                403, "FORBIDDEN_OPERATION", "Access is not authorized")))
                .addFilterBefore(new CredentialsFilter(properties), AnonymousAuthenticationFilter.class)
                .build();
    }

    private static final class CredentialsFilter extends OncePerRequestFilter {
        private final CallerProperties properties;
        private CredentialsFilter(CallerProperties properties) { this.properties = properties; }
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader("X-Request-Id", ApiErrors.requestId(request));
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ") && authorization.length() <= 8192) {
                byte[] received = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
                CallerProperties.Caller match = null;
                for (var caller : properties.callers()) {
                    if (MessageDigest.isEqual(received, caller.token().getBytes(StandardCharsets.UTF_8))) match = caller;
                }
                if (match != null) {
                    var principal = match.principal();
                    var authorities = principal.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
                    var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
            chain.doFilter(request, response);
        }
    }
}
