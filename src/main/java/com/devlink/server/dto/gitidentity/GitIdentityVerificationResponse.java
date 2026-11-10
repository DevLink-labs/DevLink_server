package com.devlink.server.dto.gitidentity;

public record GitIdentityVerificationResponse(boolean registered, boolean matched) {
}
