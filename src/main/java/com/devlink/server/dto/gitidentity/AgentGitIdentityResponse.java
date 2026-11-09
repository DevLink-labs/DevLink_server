package com.devlink.server.dto.gitidentity;

public record AgentGitIdentityResponse(boolean registered, String gitEmailHash,
        String hashAlgorithm, String normalization) {
}
