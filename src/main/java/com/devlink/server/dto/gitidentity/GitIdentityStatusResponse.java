package com.devlink.server.dto.gitidentity;

import java.time.Instant;

public record GitIdentityStatusResponse(boolean registered, Instant registeredAt) {
}
