package com.devlink.server.config;

import java.util.List;
import java.util.Optional;
import com.devlink.server.filter.AgentTokenAuthenticationFilter;
import com.devlink.server.handler.RestAccessDeniedHandler;
import com.devlink.server.handler.RestAuthenticationEntryPoint;
import com.devlink.server.handler.RestLogoutSuccessHandler;
import com.devlink.server.service.AgentTokenVerifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain brokerSecurityFilterChain(HttpSecurity http,
            ObjectProvider<AgentTokenVerifier> verifiers,
            RestAuthenticationEntryPoint entryPoint, RestAccessDeniedHandler deniedHandler) throws Exception {
        AgentTokenVerifier verifier = token -> {
            var delegate = verifiers.getIfAvailable();
            // 등록된 검증기로 현재 유효한 Agent 신원 확인
            return delegate == null ? Optional.empty() : delegate.verify(token);
        };
        http.securityMatcher("/ws/broker")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/ws/broker").hasRole("AGENT")
                        .anyRequest().denyAll())
                .addFilterBefore(new AgentTokenAuthenticationFilter(verifier, entryPoint),
                        AnonymousAuthenticationFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain agentApiSecurityFilterChain(HttpSecurity http,
            @Value("${devlink.cors.allowed-origins}") List<String> allowedOrigins,
            ObjectProvider<AgentTokenVerifier> verifiers,
            RestAuthenticationEntryPoint entryPoint, RestAccessDeniedHandler deniedHandler) throws Exception {
        AgentTokenVerifier verifier = token -> {
            var delegate = verifiers.getIfAvailable();
            return delegate == null ? Optional.empty() : delegate.verify(token);
        };
        var agentList = PathPatternRequestMatcher.withDefaults()
                .matcher(HttpMethod.GET, "/api/v1/agents/teams/{teamId}");
        http.securityMatchers(matchers -> matchers
                // 목록 GET의 Authorization 헤더가 있으면 Agent 인증 우선 적용
                .requestMatchers(request -> agentList.matches(request)
                        && request.getHeader(HttpHeaders.AUTHORIZATION) != null)
                .requestMatchers("/api/v1/agents/me", "/api/v1/agents/me/task-messages")
                .requestMatchers("/api/v1/tasks", "/api/v1/tasks/**")
        		.requestMatchers(
        				HttpMethod.GET,
        				"/api/v1/teams/*/tasks",
        				"/api/v1/tasks/*"
        		)
        		.requestMatchers(HttpMethod.POST,
        				"/api/v1/tasks/*/complete"
        				))
                .cors(cors -> cors.configurationSource(apiCorsConfigurationSource(allowedOrigins)))
                // Agent 토큰 인증 시 기존 사용자 세션 ID 변경 방지
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                        .sessionFixation(fixation -> fixation.none()))
                .requestCache(AbstractHttpConfigurer::disable)
                // 쿠키 인증을 사용하지 않는 Agent 전용 Bearer 경로에만 CSRF 예외 적용
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole("AGENT"))
                .addFilterBefore(new AgentTokenAuthenticationFilter(verifier, entryPoint),
                        AnonymousAuthenticationFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Value("${devlink.cors.allowed-origins}") List<String> allowedOrigins,
            RestAuthenticationEntryPoint entryPoint, RestAccessDeniedHandler deniedHandler,
            RestLogoutSuccessHandler logoutSuccessHandler,
            SecurityContextRepository userSecurityContextRepository,
            HttpSessionCsrfTokenRepository userCsrfTokenRepository) throws Exception {
        // 사용자 세션 인증·CSRF 보호·로그아웃 처리 연결
        http.cors(cors -> cors.configurationSource(apiCorsConfigurationSource(allowedOrigins)))
                .securityContext(context -> context
                        .securityContextRepository(userSecurityContextRepository)
                        .requireExplicitSave(true))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.csrfTokenRepository(userCsrfTokenRepository))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/signup", "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf",
                                "/api/v1/health", "/actuator/health").permitAll()
                        .requestMatchers("/api/v1/auth/users/me").hasRole("USER")
                        .requestMatchers("/api/v1/teams", "/api/v1/teams/**", "/api/v1/agents/**")
                                .hasRole("USER")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler(logoutSuccessHandler))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler));
        return http.build();
    }

    @Bean
    SecurityContextRepository userSecurityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    @Bean
    HttpSessionCsrfTokenRepository userCsrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SessionAuthenticationStrategy userSessionAuthenticationStrategy(
            HttpSessionCsrfTokenRepository userCsrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(userCsrfTokenRepository)));
    }

    private CorsConfigurationSource apiCorsConfigurationSource(List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Accept", "Authorization", "Content-Type", "X-CSRF-TOKEN", "X-XSRF-TOKEN"));
        configuration.setExposedHeaders(List.of("X-CSRF-TOKEN", "X-XSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
}
