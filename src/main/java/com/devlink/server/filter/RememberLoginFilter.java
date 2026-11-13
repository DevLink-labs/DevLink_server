package com.devlink.server.filter;

import java.io.IOException;
import com.devlink.server.service.RememberLoginService;
import com.devlink.server.service.UserSessionAuthentication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public class RememberLoginFilter extends OncePerRequestFilter {
    private final RequestMatcher userGet = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.GET, "/api/v1/**");
    private final RequestMatcher health = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.GET, "/api/v1/health");
    private final RememberLoginService rememberLogin;
    private final UserSessionAuthentication sessions;

    public RememberLoginFilter(RememberLoginService rememberLogin, UserSessionAuthentication sessions) {
        this.rememberLogin = rememberLogin;
        this.sessions = sessions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !userGet.matches(request) || health.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            rememberLogin.restoreUserId(request, response)
                    .ifPresent(userId -> sessions.authenticate(userId, request, response));
        }
        filterChain.doFilter(request, response);
    }
}
