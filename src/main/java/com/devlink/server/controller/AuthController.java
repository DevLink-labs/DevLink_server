package com.devlink.server.controller;

import com.devlink.server.common.ApiResponse;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.UserPrincipal;
import com.devlink.server.dto.CsrfTokenResponse;
import com.devlink.server.dto.LoginRequest;
import com.devlink.server.dto.SignupRequest;
import com.devlink.server.dto.UserResponse;
import com.devlink.server.service.AuthService;
import com.devlink.server.service.RememberLoginService;
import com.devlink.server.service.UserSessionAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final UserSessionAuthentication sessions;
    private final RememberLoginService rememberLogin;

    @GetMapping("/csrf")
    public ApiResponse<CsrfTokenResponse> csrf(CsrfToken csrfToken, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ApiResponse.success(CsrfTokenResponse.from(csrfToken));
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<UserResponse>> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(authService.signup(request)));
    }

    @PostMapping("/login")
    public ApiResponse<UserResponse> login(@Valid @RequestBody LoginRequest loginRequest,
            HttpServletRequest request, HttpServletResponse response) {
        UserResponse user = authService.login(loginRequest);
        var rememberCookie = rememberLogin.onPasswordLogin(user.userId(), loginRequest.rememberMe(), request);
        sessions.authenticate(user.userId(), request, response);
        rememberCookie.ifPresent(cookie -> response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString()));
        return ApiResponse.success(user);
    }

    @GetMapping("/users/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return ApiResponse.success(authService.getCurrentUser(principal.userId()));
    }
}
