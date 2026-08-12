package com.nexusiq.config;

import com.nexusiq.common.ApiError;
import com.nexusiq.common.CorrelationIdFilter;
import com.nexusiq.security.JwtAuthenticationFilter;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Stateless JWT security. See .claude/rules/security.md: BCrypt cost >= 12, no
 * sessions, explicit CORS allowlist, unauthenticated requests get the standard
 * error envelope rather than Spring's default HTML/text response.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final int BCRYPT_STRENGTH = 12;

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorrelationIdFilter correlationIdFilter;
    private final ObjectMapper objectMapper;
    private final String[] allowedOrigins;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            CorrelationIdFilter correlationIdFilter,
            ObjectMapper objectMapper,
            org.springframework.core.env.Environment env) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.correlationIdFilter = correlationIdFilter;
        this.objectMapper = objectMapper;
        String origins = env.getProperty("nexusiq.cors.allowed-origins", "http://localhost:5173");
        this.allowedOrigins = origins.split(",");
    }

    // Both filters are @Component (so Spring injects their own dependencies) but
    // are positioned explicitly inside the Spring Security chain below. Without
    // this, Spring Boot would ALSO auto-register them as generic servlet filters,
    // leaving two independent orderings of the same filter in play.
    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> disableCorrelationIdAutoRegistration(
            CorrelationIdFilter filter) {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> disableJwtFilterAutoRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Deliberately NOT "/api/v1/auth/**" — that wildcard would also
                        // cover /me, which must require authentication. An anonymous
                        // request to /me would otherwise reach the controller as
                        // principal "anonymousUser" and blow up as a 500 instead of a
                        // clean 401 (caught by AuthFlowIT).
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/actuator/health",
                                "/actuator/info",
                                // Phase 8: the real Prometheus container scrapes this with no
                                // Authorization header (infrastructure/docker/prometheus/prometheus.yml
                                // has no bearer_token/basic_auth block) — confirmed empirically that
                                // gating it behind hasRole("ADMIN") like the rest of /actuator/**
                                // makes every real scrape 401. Metrics counts/latencies aren't
                                // sensitive the way other actuator endpoints (env, beans, heapdump)
                                // are, and .claude/rules/backend-java.md already lists it alongside
                                // health/info as meant to be "exposed" — same trust level as those.
                                "/actuator/prometheus",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers("/actuator/**")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(eh -> eh.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    String requestId = CorrelationIdFilter.currentOrNew();
                    ApiError body = ApiError.of(
                            401, "UNAUTHORIZED", "Authentication required", request.getRequestURI(), requestId);
                    response.getWriter().write(objectMapper.writeValueAsString(body));
                }))
                // Correlation id must be established before the JWT filter runs, so an
                // unauthenticated-request error still carries a request id.
                .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(jwtAuthenticationFilter, CorrelationIdFilter.class);

        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
