package com.devlink.server.controller;

import com.devlink.server.common.ApiResponse;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.UserPrincipal;
import com.devlink.server.dto.gitidentity.AgentGitIdentityResponse;
import com.devlink.server.dto.gitidentity.GitIdentityRequest;
import com.devlink.server.dto.gitidentity.GitIdentityStatusResponse;
import com.devlink.server.dto.gitidentity.GitIdentityVerificationResponse;
import com.devlink.server.service.GitIdentityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class GitIdentityController {
    private final GitIdentityService identities;

    @PutMapping("/auth/users/me/git-identity")
    public ResponseEntity<ApiResponse<GitIdentityStatusResponse>> register(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody GitIdentityRequest request) {
        return response(identities.register(userId(principal), request.gitEmail()));
    }

    @GetMapping("/auth/users/me/git-identity")
    public ResponseEntity<ApiResponse<GitIdentityStatusResponse>> status(
            @AuthenticationPrincipal UserPrincipal principal) {
        return response(identities.status(userId(principal)));
    }

    @DeleteMapping("/auth/users/me/git-identity")
    public ResponseEntity<ApiResponse<GitIdentityStatusResponse>> delete(
            @AuthenticationPrincipal UserPrincipal principal) {
        return response(identities.delete(userId(principal)));
    }

    @GetMapping("/agents/me/git-identity")
    public ResponseEntity<ApiResponse<AgentGitIdentityResponse>> agentIdentity(
            @AuthenticationPrincipal AgentPrincipal principal) {
        return response(identities.agentIdentity(ownerUserId(principal)));
    }

    @PostMapping("/agents/me/git-identity/verify")
    public ResponseEntity<ApiResponse<GitIdentityVerificationResponse>> verify(
            @AuthenticationPrincipal AgentPrincipal principal,
            @Valid @RequestBody GitIdentityRequest request) {
        return response(identities.verify(ownerUserId(principal), request.gitEmail()));
    }

    private static Long userId(UserPrincipal principal) {
        if (principal == null || principal.userId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.userId();
    }

    private static Long ownerUserId(AgentPrincipal principal) {
        if (principal == null || principal.agentId() <= 0
                || principal.ownerUserId() <= 0 || principal.teamId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.ownerUserId();
    }

    private static <T> ResponseEntity<ApiResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));
    }
}
