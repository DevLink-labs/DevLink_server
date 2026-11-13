package com.devlink.server.service;

import java.util.List;
import com.devlink.server.domain.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

@Component
public class UserSessionAuthentication {
    private final SecurityContextRepository userSecurityContextRepository;
    private final SessionAuthenticationStrategy userSessionAuthenticationStrategy;

    public UserSessionAuthentication(SecurityContextRepository userSecurityContextRepository,
            SessionAuthenticationStrategy userSessionAuthenticationStrategy) {
        this.userSecurityContextRepository = userSecurityContextRepository;
        this.userSessionAuthenticationStrategy = userSessionAuthenticationStrategy;
    }

    public void authenticate(long userId, HttpServletRequest request, HttpServletResponse response) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(new UserPrincipal(userId), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        userSessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        userSecurityContextRepository.saveContext(context, request, response);
        // 기존 작업 생성 API와 동일한 세션에 로그인 사용자 ID 저장
        request.getSession().setAttribute("userId", userId);
    }
}
